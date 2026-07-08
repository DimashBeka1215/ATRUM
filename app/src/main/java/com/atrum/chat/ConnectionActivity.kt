package com.atrum.chat

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.databinding.ActivityConnectionBinding
import com.atrum.chat.nostr.ConnectionStats
import com.atrum.chat.transport.NostrTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Экран «Соединение» (Настройки → Сеть → Соединение).
 *
 * Показывает живую телеметрию реле (только фиолетовые тона статуса, БЕЗ адресов —
 * см. ConnectionStats.kt) и позволяет настроить пользовательский SOCKS5-прокси для
 * прямого (не-Tor) пути. Часть данных приходит из уже идущего опроса
 * SyncEngine/NostrTransport.queryAllRelays() через ConnectionStats.version (см.
 * CLAUDE.md §1). ⚠️ Дополнительно, ПОКА ЭТОТ ЭКРАН ОТКРЫТ, идёт свой лёгкий
 * live-пинг всех реле (см. startLivePing/stopLivePing ниже) — специально, чтобы
 * реле, не успевающие в боевой хедж-таймаут доставки (READ_GRACE_MS), тоже
 * получали шанс отчитаться и не висели вечно с «нет данных». Это НЕ отдельный
 * фоновый polling-цикл: запускается строго в onResume и останавливается в
 * onPause — при закрытии экрана никакого анализа не происходит. Использует уже
 * существующий NostrRelayPool (то же персистентное соединение, что и боевой
 * sync) через NostrTransport.pingRelayForConnectionScreen — новых сетевых
 * клиентов не создаёт, SyncEngine/PatchQueue/тайминги доставки не трогает.
 *
 * Прокси действует ТОЛЬКО когда чат подключается напрямую (preferTor=false). Для
 * Tor-чатов это ничего не меняет — см. doc-comment NostrTransport.useTor.
 */
class ConnectionActivity : SecureActivity() {

    private lateinit var binding: ActivityConnectionBinding
    private lateinit var prefs: Prefs

    /** Job живого пинга — активен строго между onResume и onPause. */
    private var livePingJob: Job? = null

    /** Интервал живого пинга, пока экран открыт. Не связан с интервалами SyncEngine. */
    private val livePingIntervalMs = 3_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnBack.setOnClickListener { finish() }

        // ─── Прокси: восстанавливаем сохранённую конфигурацию ────────────────
        val hasSavedHost = prefs.customProxyHost.isNotBlank()
        binding.rgProxyMode.check(if (hasSavedHost) R.id.rbProxySocks5 else R.id.rbProxyOff)
        binding.proxyFieldsContainer.visibility = if (hasSavedHost) View.VISIBLE else View.GONE
        binding.etProxyHost.setText(prefs.customProxyHost)
        binding.etProxyPort.setText(prefs.customProxyPort.toString())
        binding.etProxyLogin.setText(prefs.customProxyLogin)
        binding.etProxyPassword.setText(prefs.customProxyPassword)

        binding.rgProxyMode.setOnCheckedChangeListener { _, checkedId ->
            binding.proxyFieldsContainer.visibility =
                if (checkedId == R.id.rbProxySocks5) View.VISIBLE else View.GONE
        }

        binding.btnSaveProxy.setOnClickListener { saveProxyConfig() }

        // ─── Мастер-тумблер «Соединение» — применяется СРАЗУ (см. CLAUDE.md §1.5) ───
        binding.switchConnection.isChecked = ConnectionPrefs.customProxyEnabled
        binding.switchConnection.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !ConnectionPrefs.isConfigValid()) {
                Toast.makeText(this, R.string.connection_proxy_not_configured, Toast.LENGTH_SHORT).show()
                binding.switchConnection.isChecked = false
                return@setOnCheckedChangeListener
            }
            ConnectionPrefs.save(
                prefs, isChecked,
                ConnectionPrefs.proxyHost, ConnectionPrefs.proxyPort,
                ConnectionPrefs.proxyLogin, ConnectionPrefs.proxyPassword
            )
            refreshLiveStatus()
        }

        rebuildRelayRows()
        refreshLiveStatus()

        // Реактивно обновляемся на новые сэмплы (из боевого sync И из своего live-пинга
        // ниже — оба пишут в один ConnectionStats, см. doc-comment класса). ⚠️ debounce:
        // live-пинг бьёт по 12 реле ПАРАЛЛЕЛЬНО, каждое завершение инкрементит version —
        // без debounce это давало бы до 12 полных removeAllViews()+reinflate подряд за
        // один цикл (видимое мерцание списка). 150мс схлопывает пачку в один rebuild.
        lifecycleScope.launch {
            ConnectionStats.version.debounce(150).collect {
                rebuildRelayRows()
                refreshLiveStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildRelayRows()
        refreshLiveStatus()
        startLivePing()
    }

    override fun onPause() {
        super.onPause()
        stopLivePing()
    }

    /**
     * Живой пинг ВСЕХ реле, пока экран открыт. В отличие от боевого хеджа
     * ([NostrTransport.queryAllRelays]) каждое реле пингуется независимо и ждёт
     * СВОЙ полный таймаут — медленное реле не отменяется из-за того, что другое
     * ответило быстрее, поэтому телеметрия набегает даже для «медленных» узлов.
     * Строго foreground: висит в lifecycleScope, но реально бегает только пока
     * job жив (стартует в onResume, отменяется в onPause) — никакого анализа,
     * когда экран не на экране.
     */
    private fun startLivePing() {
        if (livePingJob?.isActive == true) return
        livePingJob = lifecycleScope.launch {
            while (isActive) {
                val urls = NostrTransport.activeRelays()
                urls.map { url ->
                    async(Dispatchers.IO) {
                        val latencyMs = NostrTransport.pingRelayForConnectionScreen(url)
                        ConnectionStats.record(url, latencyMs)
                    }
                }.awaitAll()
                delay(livePingIntervalMs)
            }
        }
    }

    /** Останавливает live-пинг — вызывается из onPause, экран закрыт → тишина. */
    private fun stopLivePing() {
        livePingJob?.cancel()
        livePingJob = null
    }

    private fun saveProxyConfig() {
        if (binding.rgProxyMode.checkedRadioButtonId == R.id.rbProxyOff) {
            ConnectionPrefs.save(prefs, false, "", 1080, "", "")
            binding.switchConnection.isChecked = false
            Toast.makeText(this, R.string.connection_proxy_disabled_toast, Toast.LENGTH_SHORT).show()
            refreshLiveStatus()
            return
        }
        val host = binding.etProxyHost.text?.toString()?.trim().orEmpty()
        val port = binding.etProxyPort.text?.toString()?.trim()?.toIntOrNull() ?: -1
        if (host.isEmpty() || port !in 1..65535) {
            Toast.makeText(this, R.string.connection_proxy_invalid_toast, Toast.LENGTH_SHORT).show()
            return
        }
        val login = binding.etProxyLogin.text?.toString()?.trim().orEmpty()
        val password = binding.etProxyPassword.text?.toString().orEmpty()
        // Сохраняем конфиг, СОХРАНЯЯ текущее состояние мастер-тумблера (Сохранить ≠ Включить —
        // фактическое использование прокси управляется только нижним тумблером).
        ConnectionPrefs.save(prefs, binding.switchConnection.isChecked, host, port, login, password)
        Toast.makeText(this, R.string.connection_proxy_saved_toast, Toast.LENGTH_SHORT).show()
        refreshLiveStatus()
    }

    /** Пересобирает список строк статуса реле — «Реле N», без реальных адресов. */
    private fun rebuildRelayRows() {
        val urls = NostrTransport.activeRelays()
        val states = ConnectionStats.snapshot(urls)
        binding.relayListContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val barWidthPx = (2.5f * density).toInt().coerceAtLeast(1)
        val gapPx = (2f * density).toInt()
        val emptyColor = ContextCompat.getColor(this, R.color.border)

        states.forEach { st ->
            val row = layoutInflater.inflate(R.layout.item_connection_relay, binding.relayListContainer, false)
            val tvLabel = row.findViewById<TextView>(R.id.tvRelayLabel)
            val tvLatency = row.findViewById<TextView>(R.id.tvRelayLatency)
            val dot = row.findViewById<ImageView>(R.id.dotStatus)
            val sparkContainer = row.findViewById<LinearLayout>(R.id.sparklineContainer)

            val color = colorForStatus(st.status)
            tvLabel.text = getString(R.string.connection_relay_label, st.index)
            dot.setColorFilter(color)
            tvLatency.text = st.latencyMs?.let { getString(R.string.connection_ms_value, it.toInt()) }
                ?: getString(R.string.connection_no_data)
            tvLatency.setTextColor(color)

            sparkContainer.removeAllViews()
            val maxMs = 2000L
            st.sparkline.forEach { sample ->
                val bar = View(this)
                val heightDp = if (sample == null) 3f
                    else 4f + (sample.coerceAtMost(maxMs).toFloat() / maxMs) * 16f
                val params = LinearLayout.LayoutParams(barWidthPx, (heightDp * density).toInt())
                params.marginEnd = gapPx
                params.gravity = Gravity.BOTTOM
                bar.layoutParams = params
                bar.setBackgroundColor(if (sample == null) emptyColor else color)
                sparkContainer.addView(bar)
            }

            binding.relayListContainer.addView(row)
        }
    }

    private fun refreshLiveStatus() {
        val urls = NostrTransport.activeRelays()
        val states = ConnectionStats.snapshot(urls)
        val healthy = states.count {
            it.status == ConnectionStats.Status.OK || it.status == ConnectionStats.Status.DEGRADED
        }
        val avgMs = states.mapNotNull { it.latencyMs }.let { if (it.isEmpty()) null else it.average().toInt() }

        val modeLabel = if (ConnectionPrefs.customProxyEnabled && ConnectionPrefs.isConfigValid())
            getString(R.string.connection_mode_socks5)
        else getString(R.string.connection_mode_direct)

        val parts = mutableListOf(modeLabel)
        avgMs?.let { parts.add(getString(R.string.connection_ms_value, it)) }
        parts.add(getString(R.string.connection_relays_count, healthy, states.size))
        binding.tvConnectionStatus.text = parts.joinToString(" · ")
    }

    private fun colorForStatus(status: ConnectionStats.Status): Int = when (status) {
        ConnectionStats.Status.OK -> ContextCompat.getColor(this, R.color.accent_light)
        ConnectionStats.Status.DEGRADED -> ContextCompat.getColor(this, R.color.accent)
        ConnectionStats.Status.DOWN, ConnectionStats.Status.UNKNOWN ->
            androidx.core.graphics.ColorUtils.setAlphaComponent(
                ContextCompat.getColor(this, R.color.accent_dark), 140
            )
    }
}
