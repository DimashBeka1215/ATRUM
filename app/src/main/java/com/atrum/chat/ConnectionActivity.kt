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
import kotlinx.coroutines.launch

/**
 * Экран «Соединение» (Настройки → Сеть → Соединение).
 *
 * Показывает живую телеметрию реле (только фиолетовые тона статуса, БЕЗ адресов —
 * см. ConnectionStats.kt) и позволяет настроить пользовательский SOCKS5-прокси для
 * прямого (не-Tor) пути. Никакого нового сетевого polling-цикла тут нет — данные
 * приходят из уже идущего опроса SyncEngine/NostrTransport.queryAllRelays() через
 * ConnectionStats.version (см. CLAUDE.md §1).
 *
 * Прокси действует ТОЛЬКО когда чат подключается напрямую (preferTor=false). Для
 * Tor-чатов это ничего не меняет — см. doc-comment NostrTransport.useTor.
 */
class ConnectionActivity : SecureActivity() {

    private lateinit var binding: ActivityConnectionBinding
    private lateinit var prefs: Prefs

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

        // Реактивно обновляемся на КАЖДЫЙ новый сэмпл из уже идущего опроса реле —
        // никакого собственного таймера/цикла (см. doc-comment класса).
        lifecycleScope.launch {
            ConnectionStats.version.collect {
                rebuildRelayRows()
                refreshLiveStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildRelayRows()
        refreshLiveStatus()
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
