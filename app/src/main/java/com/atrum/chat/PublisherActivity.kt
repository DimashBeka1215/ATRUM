package com.atrum.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.nostr.NostrEvent
import com.atrum.chat.nostr.Schnorr
import com.atrum.chat.nostr.hexToBytes
import com.atrum.chat.nostr.toHex
import com.atrum.chat.transport.NostrTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран издателя списка реле. Доступен только тому, у кого есть приватный ключ (вход —
 * долгое нажатие на «Реле сообщений» в настройках). Без ключа показывает ввод/генерацию;
 * с ключом — публичный ключ (для вшивания), редактор добавляемых реле и публикацию.
 *
 * Безопасность: подписанный этим ключом список примут ТОЛЬКО клиенты с тем же вшитым
 * публичным ключом. Чужой ключ → публикация бесполезна (никто не примет). Поэтому экран
 * можно показывать кому угодно — эффективен лишь держатель правильного ключа.
 */
class PublisherActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }
    private val relays = ArrayList<String>()

    private lateinit var llNoKey: LinearLayout
    private lateinit var llHasKey: LinearLayout
    private lateinit var etKey: EditText
    private lateinit var llRelays: LinearLayout
    private lateinit var etNewRelay: EditText
    private lateinit var tvPubKey: TextView
    private lateinit var tvCurrentVer: TextView
    private lateinit var tvNotBaked: TextView
    private lateinit var tvPubKeyLabel: TextView
    private lateinit var tvPublishStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_publisher)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        llNoKey = findViewById(R.id.llNoKey)
        llHasKey = findViewById(R.id.llHasKey)
        etKey = findViewById(R.id.etKey)
        llRelays = findViewById(R.id.llRelays)
        etNewRelay = findViewById(R.id.etNewRelay)
        tvPubKey = findViewById(R.id.tvPubKey)
        tvCurrentVer = findViewById(R.id.tvCurrentVer)
        tvNotBaked = findViewById(R.id.tvNotBaked)
        tvPubKeyLabel = findViewById(R.id.tvPubKeyLabel)
        tvPublishStatus = findViewById(R.id.tvPublishStatus)

        findViewById<View>(R.id.btnSaveKey).setOnClickListener { onSaveKey() }
        findViewById<View>(R.id.btnGenPair).setOnClickListener { onGenerate() }
        findViewById<View>(R.id.btnCopyPub).setOnClickListener { copy(tvPubKey.text.toString()) }
        findViewById<View>(R.id.btnAddRelay).setOnClickListener { onAddRelay() }
        findViewById<View>(R.id.btnPublish).setOnClickListener { onPublish() }
        findViewById<View>(R.id.btnDeleteKey).setOnClickListener { onDeleteKey() }

        render()
    }

    // ─── режимы ────────────────────────────────────────────────────────────────
    private fun render() {
        val priv = prefs.getPublisherPriv()
        if (priv == null) {
            llNoKey.visibility = View.VISIBLE
            llHasKey.visibility = View.GONE
            return
        }
        llNoKey.visibility = View.GONE
        llHasKey.visibility = View.VISIBLE

        val pub = try { Schnorr.pubkeyFromPrivkey(priv).toHex() } catch (_: Exception) { "" }
        tvPubKey.text = pub
        val baked = RelayListStore.PUBLISHER_PUBKEY_HEX
        val isBaked = baked.isNotBlank() && baked.equals(pub, ignoreCase = true)
        tvNotBaked.visibility = if (isBaked) View.GONE else View.VISIBLE
        tvPubKeyLabel.setText(if (isBaked) R.string.pub_pubkey_label_baked else R.string.pub_pubkey_label)
        tvCurrentVer.text = getString(R.string.pub_current_ver, maxOf(RelayListStore.currentVersion(this), ownerVersion()))

        relays.clear()
        relays.addAll(RelayListStore.extraRelays(this))
        renderRelayRows()
    }

    private fun renderRelayRows() {
        llRelays.removeAllViews()
        relays.forEachIndexed { i, url ->
            // строим строку вручную (без отдельного layout-файла)
            val ll = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(9), dp(2), dp(9))
            }
            val tv = TextView(this).apply {
                text = url
                setTextColor(androidx.core.content.ContextCompat.getColor(this@PublisherActivity, R.color.text_primary))
                textSize = 12.5f
                typeface = android.graphics.Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = ImageView(this).apply {
                setImageResource(R.drawable.ic_trash_menu)
                setColorFilter(androidx.core.content.ContextCompat.getColor(this@PublisherActivity, R.color.error))
                val s = dp(20)
                layoutParams = LinearLayout.LayoutParams(s, s).also { it.marginStart = dp(10) }
                setOnClickListener { relays.removeAt(i); renderRelayRows() }
            }
            ll.addView(tv); ll.addView(del)
            llRelays.addView(ll)
        }
    }

    // ─── действия ──────────────────────────────────────────────────────────────
    private fun onSaveKey() {
        val hex = etKey.text.toString().trim().lowercase()
        val priv = parsePriv(hex)
        if (priv == null) { toast(getString(R.string.pub_invalid_key)); return }
        prefs.setPublisherPriv(priv)
        toast(getString(R.string.pub_key_saved))
        render()
    }

    private fun onGenerate() {
        val rnd = java.security.SecureRandom()
        var priv = ByteArray(32)
        while (true) {
            rnd.nextBytes(priv)
            try { Schnorr.pubkeyFromPrivkey(priv); break } catch (_: Exception) { /* вне диапазона — повтор */ }
        }
        etKey.setText(priv.toHex())
        toast("OK")
    }

    private fun onAddRelay() {
        val url = etNewRelay.text.toString().trim().lowercase()
        if (!url.startsWith("wss://") || url.length < 8) { toast(getString(R.string.pub_relay_bad)); return }
        if (url !in relays) relays.add(url)
        etNewRelay.setText("")
        renderRelayRows()
    }

    private fun onPublish() {
        val priv = prefs.getPublisherPriv() ?: return
        val pub = try { Schnorr.pubkeyFromPrivkey(priv).toHex() } catch (_: Exception) { return }
        val nextVer = maxOf(RelayListStore.currentVersion(this), ownerVersion()) + 1
        val content = RelayListStore.buildContent(nextVer, relays.toList())
        val ev = NostrEvent.create(
            privkeyBytes = priv,
            kind = RelayListStore.KIND,
            tags = listOf(listOf("d", RelayListStore.D_TAG)),
            content = content
        )
        val useTor = TorManager.status.value == TorManager.TorStatus.READY
        tvPublishStatus.visibility = View.VISIBLE
        tvPublishStatus.setTextColor(color(R.color.text_secondary))
        tvPublishStatus.text = getString(R.string.pub_verify_checking)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching { NostrTransport.publishRelayListEvent(ev, useTor) }.getOrDefault(0)
            }
            if (count <= 0) {
                toast(getString(R.string.pub_publish_fail))
                tvPublishStatus.setTextColor(color(R.color.warning))
                tvPublishStatus.text = getString(R.string.pub_verify_none)
                return@launch
            }
            setOwnerVersion(nextVer)
            tvCurrentVer.text = getString(R.string.pub_current_ver, nextVer)
            toast(getString(R.string.pub_published_ok, count))
            // Проверка доставки: читаем список обратно с реле (даём время проиндексировать).
            val readable = withContext(Dispatchers.IO) {
                kotlinx.coroutines.delay(1500)
                runCatching { NostrTransport.countRelaysWithRelayList(pub, nextVer, useTor) }.getOrDefault(0)
            }
            val total = NostrTransport.relayCount()
            if (readable > 0) {
                tvPublishStatus.setTextColor(color(R.color.text_secondary))
                tvPublishStatus.text = getString(R.string.pub_verify_ok, readable, total)
            } else {
                tvPublishStatus.setTextColor(color(R.color.warning))
                tvPublishStatus.text = getString(R.string.pub_verify_none)
            }
        }
    }

    private fun color(res: Int): Int = androidx.core.content.ContextCompat.getColor(this, res)

    private fun onDeleteKey() {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.pub_delete_key),
            message = null,
            positiveText = getString(R.string.action_delete),
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel)
        ) {
            prefs.clearPublisherPriv()
            etKey.setText("")
            render()
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────
    private fun parsePriv(hex: String): ByteArray? = try {
        if (hex.length != 64 || !hex.all { it in "0123456789abcdef" }) null
        else hex.hexToBytes().also { Schnorr.pubkeyFromPrivkey(it) } // бросит, если вне диапазона
    } catch (_: Exception) { null }

    private fun ownerVersion(): Int =
        getSharedPreferences("atrum_relaylist", Context.MODE_PRIVATE).getInt("owner_v", -1)

    private fun setOwnerVersion(v: Int) {
        getSharedPreferences("atrum_relaylist", Context.MODE_PRIVATE).edit().putInt("owner_v", v).apply()
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("pubkey", text))
        toast(getString(R.string.msg_copied))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
