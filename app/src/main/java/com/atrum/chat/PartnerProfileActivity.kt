package com.atrum.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.transport.TransportFactory
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PartnerProfileActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    companion object {
        const val EXTRA_NAME          = "name"
        const val EXTRA_TAG           = "tag"
        const val EXTRA_STATUS        = "status"
        const val EXTRA_AVATAR_BASE64 = "avatar_base64"
        const val EXTRA_CHANNEL_ID    = "channel_id"
        const val EXTRA_TRANSPORT_TOKEN = "transport_token"
        const val EXTRA_CHAT_PASSWORD = "chat_password"
        const val EXTRA_CHAT_ID       = "chat_id"
        const val EXTRA_IMAGE_REFS    = "image_refs"
        const val EXTRA_IMAGE_MSGIDS  = "image_msgids"
        const val EXTRA_IMAGE_SELF    = "image_self"
        const val EXTRA_VOICE_REFS    = "voice_refs"
        const val EXTRA_VOICE_MSGIDS  = "voice_msgids"
        const val EXTRA_VOICE_SELF    = "voice_self"
        const val EXTRA_LINKS         = "links"
        const val EXTRA_LINK_MSGIDS   = "link_msgids"
        const val EXTRA_LINK_SELF     = "link_self"
        const val EXTRA_IDENTITY_PUB          = "identity_pub"
        const val EXTRA_EPH_PUB               = "eph_pub"
        const val EXTRA_EPH_SIG               = "eph_sig"
        const val EXTRA_VERIFIED_PARTNER_IDK  = "verified_partner_idk"
    }

    private var shieldPulse: android.animation.Animator? = null
    private var activeVoiceIcon: ImageView? = null

    // Данные для перехода в список медиа и к исходным сообщениям.
    private var chatIdForMedia: Long = -1L
    private var mchatId = ""; private var mtransportToken = ""; private var mChatPassword = ""
    private var photoRefsAll = ArrayList<String>(); private var photoMsgIds = ArrayList<String>(); private var photoSelf = ArrayList<String>()
    private var voiceItemsAll = ArrayList<String>(); private var voiceMsgIds = ArrayList<String>(); private var voiceSelf = ArrayList<String>()
    private var linkItemsAll = ArrayList<String>(); private var linkMsgIds = ArrayList<String>(); private var linkSelf = ArrayList<String>()

    // ── Взаимная сверка (живой статус) ─────────────────────────────────────────
    private var verifychatId = ""
    private var verifyToken = ""
    private var verifyPassword = ""
    private var verifySyncJob: Job? = null   // лёгкий опрос профиля партнёра, пока экран открыт
    private var dotsJob: Job? = null          // анимированные точки «ждём…»
    private var lastBothConfirmed = false     // чтобы pop-анимацию проигрывать только при переходе

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_partner_profile)

        val name         = intent.getStringExtra(EXTRA_NAME) ?: ""
        val tag          = intent.getStringExtra(EXTRA_TAG)
        val status       = intent.getStringExtra(EXTRA_STATUS)
        val avatarBase64 = intent.getStringExtra(EXTRA_AVATAR_BASE64)
        val chatId       = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ""
        val transportToken    = intent.getStringExtra(EXTRA_TRANSPORT_TOKEN) ?: ""
        val chatPassword = intent.getStringExtra(EXTRA_CHAT_PASSWORD) ?: ""
        val imageRefs    = intent.getStringArrayListExtra(EXTRA_IMAGE_REFS) ?: arrayListOf()
        val voiceItems   = intent.getStringArrayListExtra(EXTRA_VOICE_REFS) ?: arrayListOf()
        val linkItems    = intent.getStringArrayListExtra(EXTRA_LINKS) ?: arrayListOf()

        chatIdForMedia = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        mchatId = chatId; mtransportToken = transportToken; mChatPassword = chatPassword
        photoRefsAll = imageRefs
        photoMsgIds = intent.getStringArrayListExtra(EXTRA_IMAGE_MSGIDS) ?: arrayListOf()
        photoSelf   = intent.getStringArrayListExtra(EXTRA_IMAGE_SELF) ?: arrayListOf()
        voiceItemsAll = voiceItems
        voiceMsgIds = intent.getStringArrayListExtra(EXTRA_VOICE_MSGIDS) ?: arrayListOf()
        voiceSelf   = intent.getStringArrayListExtra(EXTRA_VOICE_SELF) ?: arrayListOf()
        linkItemsAll = linkItems
        linkMsgIds = intent.getStringArrayListExtra(EXTRA_LINK_MSGIDS) ?: arrayListOf()
        linkSelf   = intent.getStringArrayListExtra(EXTRA_LINK_SELF) ?: arrayListOf()

        // Сохраняем для живой сверки (публикация подтверждения + опрос профиля партнёра).
        verifychatId = chatId
        verifyToken = transportToken
        verifyPassword = chatPassword

        // Security fingerprint
        val tvFingerprint = findViewById<TextView>(R.id.tv_security_fingerprint)
        val fingerprint = CryptoHelper.getSessionKeyFingerprint(chatId)
        if (fingerprint != null) {
            tvFingerprint.text = fingerprint
        } else {
            tvFingerprint.text = getString(R.string.fingerprint_none)
        }

        // QR-код сверки (SAS) — кодирует тот же fingerprint для удобной сверки.
        val ivQr = findViewById<android.widget.ImageView>(R.id.iv_security_qr)
        if (fingerprint != null) {
            generateSasQr(fingerprint)?.let {
                ivQr.setImageBitmap(it)
                ivQr.visibility = View.VISIBLE
            }
        }

        // Статус проверки идентичности партнёра (пункт 7, информативно).
        applyIdentityBadge(chatId)

        // Back button
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // Name
        findViewById<TextView>(R.id.tv_profile_name).text = name

        // Tag
        val tvTag = findViewById<TextView>(R.id.tv_profile_tag)
        if (!tag.isNullOrBlank()) {
            tvTag.text = tag
            tvTag.visibility = View.VISIBLE
        } else {
            tvTag.visibility = View.GONE
        }

        // Avatar
        val ivAvatar = findViewById<ShapeableImageView>(R.id.iv_profile_avatar)
        val tvAvatarInitial = findViewById<TextView>(R.id.tv_avatar_initial)
        val avatarBitmap = AvatarUtils.fromBase64(avatarBase64)
        if (avatarBitmap != null) {
            ivAvatar.setImageBitmap(avatarBitmap)
            ivAvatar.visibility = View.VISIBLE
            tvAvatarInitial.visibility = View.GONE
        } else {
            ivAvatar.visibility = View.GONE
            tvAvatarInitial.visibility = View.VISIBLE
            tvAvatarInitial.text = name.trim().firstOrNull()?.uppercase() ?: "?"
        }

        // Status card
        val statusCard = findViewById<View>(R.id.card_status)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        if (!status.isNullOrBlank()) {
            tvStatus.text = status
            statusCard.visibility = View.VISIBLE
        } else {
            statusCard.visibility = View.GONE
        }

        // Photos grid
        val photosSection = findViewById<View>(R.id.section_photos)
        val gridContainer = findViewById<LinearLayout>(R.id.ll_photo_grid_row1)
        val photosCount = findViewById<TextView>(R.id.tv_photos_count)
        val photosAllBtn = findViewById<View>(R.id.btn_photos_all)

        if (imageRefs.isEmpty()) {
            photosSection.visibility = View.GONE
        } else {
            photosSection.visibility = View.VISIBLE
            loadPhotoGrid(imageRefs, chatId, transportToken, chatPassword, gridContainer, photosCount, photosAllBtn)
        }

        // Голосовые и Ссылки
        setupVoiceSection(voiceItems, chatId, transportToken, chatPassword)
        setupLinksSection(linkItems)
    }

    private fun loadPhotoGrid(
        refs: List<String>,
        chatId: String,
        transportToken: String,
        chatPassword: String,
        row1: LinearLayout,
        countView: TextView,
        allBtn: View
    ) {
        val transport = TransportFactory.forChat(this@PartnerProfileActivity, chatId, transportToken, chatPassword, prefs.myUserId)
        val loader = ImageLoader(transport, chatPassword)

        countView.text = refs.size.toString()

        // Показываем 3 самых свежих; остальные доступны по «+N» / «все».
        val display = refs.takeLast(3)
        val more = refs.size - display.size

        if (more > 0) {
            allBtn.visibility = View.VISIBLE
            allBtn.setOnClickListener { openMediaList("photos") }
        } else {
            allBtn.visibility = View.GONE
        }

        row1.removeAllViews()

        display.forEachIndexed { index, ref ->
            val cell = layoutInflater.inflate(R.layout.item_photo_grid_cell, row1, false)
            val iv = cell.findViewById<ImageView>(R.id.iv_photo_cell)
            row1.addView(cell)

            if (ref.startsWith("base64:")) {
                val b64 = ref.removePrefix("base64:")
                val bmp = AvatarUtils.fromBase64(b64)
                if (bmp != null) iv.setImageBitmap(bmp)
            } else {
                lifecycleScope.launch {
                    val bmp: Bitmap? = withContext(Dispatchers.IO) {
                        try { loader.loadBitmap(ref) } catch (_: Exception) { null }
                    }
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
            }

            // «+N» на последней видимой плитке, если фото больше трёх.
            if (index == display.lastIndex && more > 0) {
                cell.findViewById<View>(R.id.overlay_more).visibility = View.VISIBLE
                cell.findViewById<TextView>(R.id.tv_more_count).apply {
                    text = "+$more"
                    visibility = View.VISIBLE
                }
            }

            if (index == display.lastIndex && more > 0) {
                cell.setOnClickListener { openMediaList("photos") }
            } else {
                cell.setOnClickListener { openPhotoGallery(refs, refs.size - display.size + index) }
            }
        }
    }

    private fun openMediaList(mode: String) {
        val items: ArrayList<String>; val msgIds: ArrayList<String>; val self: ArrayList<String>; val title: String
        when (mode) {
            "photos" -> { items = photoRefsAll; msgIds = photoMsgIds; self = photoSelf; title = getString(R.string.profile_photos_label) }
            "voice"  -> { items = voiceItemsAll; msgIds = voiceMsgIds; self = voiceSelf; title = getString(R.string.profile_voice_label) }
            else     -> { items = linkItemsAll; msgIds = linkMsgIds; self = linkSelf; title = getString(R.string.profile_links_label) }
        }
        val intent = android.content.Intent(this, MediaListActivity::class.java).apply {
            putExtra(MediaListActivity.EXTRA_MODE, mode)
            putExtra(MediaListActivity.EXTRA_TITLE, title)
            putExtra(MediaListActivity.EXTRA_CHAT_ID, chatIdForMedia)
            putExtra(MediaListActivity.EXTRA_CHANNEL_ID, mchatId)
            putExtra(MediaListActivity.EXTRA_TRANSPORT_TOKEN, mtransportToken)
            putExtra(MediaListActivity.EXTRA_CHAT_PASSWORD, mChatPassword)
            putStringArrayListExtra(MediaListActivity.EXTRA_ITEMS, items)
            putStringArrayListExtra(MediaListActivity.EXTRA_MSGIDS, msgIds)
            putStringArrayListExtra(MediaListActivity.EXTRA_SELF, self)
        }
        startActivity(intent)
    }

    private fun openPhotoGallery(refs: List<String>, startIndex: Int) {
        val intent = android.content.Intent(this, ImageViewActivity::class.java).apply {
            putExtra(ImageViewActivity.EXTRA_REFS, ArrayList(refs))
            putExtra(ImageViewActivity.EXTRA_START_INDEX, startIndex)
        }
        startActivity(intent)
    }

    // ─── Ссылки ──────────────────────────────────────────────────────────────
    private fun setupLinksSection(links: List<String>) {
        val section = findViewById<View>(R.id.section_links)
        if (links.isEmpty()) { section.visibility = View.GONE; return }
        section.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_links_count).text = links.size.toString()
        val container = findViewById<LinearLayout>(R.id.ll_links_container)
        val allBtn = findViewById<View>(R.id.btn_links_all)
        val preview = 3
        renderLinks(container, links.take(preview))
        if (links.size > preview) {
            allBtn.visibility = View.VISIBLE
            allBtn.setOnClickListener { openMediaList("links") }
        } else allBtn.visibility = View.GONE
    }

    private fun renderLinks(container: LinearLayout, links: List<String>) {
        container.removeAllViews()
        for (url in links) {
            val row = layoutInflater.inflate(R.layout.item_link_row, container, false)
            val host = linkHost(url)
            row.findViewById<TextView>(R.id.tv_link_letter).text =
                host.firstOrNull()?.uppercase() ?: "#"
            row.findViewById<TextView>(R.id.tv_link_title).text =
                url.removePrefix("https://").removePrefix("http://").trimEnd('/')
            row.findViewById<TextView>(R.id.tv_link_url).text = host
            row.setOnClickListener { openUrl(url) }
            container.addView(row)
        }
    }

    private fun linkHost(url: String): String = try {
        android.net.Uri.parse(if (url.startsWith("http", true)) url else "http://$url").host ?: url
    } catch (_: Exception) { url }

    private fun openUrl(url: String) {
        try {
            AppLock.beginShareGrace()
            val u = if (url.startsWith("http", true)) url else "http://$url"
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)))
        } catch (_: Exception) {}
    }

    // ─── Голосовые ───────────────────────────────────────────────────────────
    private fun setupVoiceSection(items: List<String>, chatId: String, transportToken: String, chatPassword: String) {
        val section = findViewById<View>(R.id.section_voice)
        val parsed = items.mapNotNull {
            val parts = it.split('\u0001')
            if (parts.firstOrNull().isNullOrBlank()) null
            else parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }
        if (parsed.isEmpty()) { section.visibility = View.GONE; return }
        section.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_voice_count).text = parsed.size.toString()
        val container = findViewById<LinearLayout>(R.id.ll_voice_container)
        val allBtn = findViewById<View>(R.id.btn_voice_all)
        val transport = TransportFactory.forChat(this, chatId, transportToken, chatPassword, prefs.myUserId)
        val loader = ImageLoader(transport, chatPassword)
        val preview = 3
        renderVoice(container, parsed.take(preview), loader)
        if (parsed.size > preview) {
            allBtn.visibility = View.VISIBLE
            allBtn.setOnClickListener { openMediaList("voice") }
        } else allBtn.visibility = View.GONE
    }

    private fun renderVoice(container: LinearLayout, items: List<Pair<String, Int>>, loader: ImageLoader) {
        container.removeAllViews()
        for ((ref, durSec) in items) {
            val row = layoutInflater.inflate(R.layout.item_voice_row, container, false)
            val playIcon = row.findViewById<ImageView>(R.id.iv_voice_play)
            row.findViewById<TextView>(R.id.tv_voice_label).text = getString(R.string.msg_preview_voice)
            row.findViewById<TextView>(R.id.tv_voice_dur).text = formatVoiceDur(durSec)
            row.setOnClickListener { toggleVoice(ref, playIcon, loader) }
            container.addView(row)
        }
    }

    private fun toggleVoice(ref: String, playIcon: ImageView, loader: ImageLoader) {
        val key = "pp_$ref"
        if (VoicePlayer.isPlaying(key)) {
            VoicePlayer.pause()
            playIcon.setImageResource(R.drawable.ic_play)
            activeVoiceIcon = null
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { loadVoiceFile(loader, ref) } ?: return@launch
            activeVoiceIcon?.setImageResource(R.drawable.ic_play)  // сбросить предыдущую строку
            activeVoiceIcon = playIcon
            playIcon.setImageResource(R.drawable.ic_pause)
            VoicePlayer.toggle(key, file, { _, _, _ -> }, { _ ->
                runOnUiThread {
                    playIcon.setImageResource(R.drawable.ic_play)
                    if (activeVoiceIcon === playIcon) activeVoiceIcon = null
                }
            })
        }
    }

    private suspend fun loadVoiceFile(loader: ImageLoader, ref: String): java.io.File? {
        val dir = java.io.File(cacheDir, "voice_play").apply { mkdirs() }
        val f = java.io.File(dir, "v_" + Integer.toHexString(ref.hashCode()) + ".m4a")
        if (f.exists() && f.length() > 0) return f
        val bytes = loader.loadRawBytes(ref) ?: return null
        return try { f.writeBytes(bytes); f } catch (_: Exception) { null }
    }

    private fun formatVoiceDur(sec: Int): String {
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    /** Генерирует QR-код из строки сверки (SAS). Чёрно-белый, для сканера/глаза. */
    private fun generateSasQr(text: String): android.graphics.Bitmap? {
        return try {
            val size = (160 * resources.displayMetrics.density).toInt()
            val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) {
                    pixels[off + x] =
                        if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (_: Exception) {
            null
        }
    }


    /**
     * Щит у ника + сворачиваемая секция безопасности (пункт 7).
     * Цвет щита: белый — подтверждено обоими; жёлтый — нужна личная сверка;
     * красный (с крестиком) — ключ партнёра изменился. Данные берём из intent
     * (надёжно, не зависит от тайминга polling).
     */
    private fun applyIdentityBadge(chatId: String) {
        val shield = findViewById<android.widget.ImageButton>(R.id.btn_shield_status)
        val card = findViewById<View>(R.id.card_security)

        val idk = intent.getStringExtra(EXTRA_IDENTITY_PUB)
        val eph = intent.getStringExtra(EXTRA_EPH_PUB)
        val esig = intent.getStringExtra(EXTRA_EPH_SIG)
        // intent — лишь снимок на момент открытия; живой статус берём из IdentityState
        // (его обновляет либо polling ChatActivity, либо наш sync-цикл на этом экране).
        val intentVpk = intent.getStringExtra(EXTRA_VERIFIED_PARTNER_IDK)

        // Старый клиент без identity-ключа — щит и секцию не показываем.
        if (idk == null) {
            shield.visibility = View.GONE
            card.visibility = View.GONE
            stopShieldPulse(shield)
            return
        }

        // Авто-статус: подпись эфемерного ключа + TOFU.
        val sigValid = eph != null && esig != null && try {
            val data = android.util.Base64.decode(eph, android.util.Base64.NO_WRAP) +
                chatId.toByteArray(Charsets.UTF_8)
            CryptoHelper.verifyIdentitySignature(idk, data, esig)
        } catch (_: Exception) { false }

        val myIdk = prefs.myIdentityPubKey
        val confirmed = prefs.getConfirmedPartnerIdentity(chatId) == idk
        // Партнёр подтвердил меня: живой флаг из IdentityState ИЛИ снимок из intent (фолбэк).
        val partnerConfirmedMe = IdentityState.get(chatId).partnerVerifiedMe ||
            (intentVpk != null && intentVpk == myIdk)
        val knownIdk = prefs.getKnownPartnerIdentity(chatId)
        val keyChanged = knownIdk != null && knownIdk != idk

        // TOFU: запоминаем identity-ключ партнёра при первом появлении.
        if (knownIdk == null) prefs.setKnownPartnerIdentity(chatId, idk)

        val badge     = findViewById<ImageView>(R.id.iv_security_badge)
        val tvBadge   = findViewById<TextView>(R.id.tv_security_badge)
        val tvHint    = findViewById<TextView>(R.id.tv_verify_hint)
        val btnVerify = findViewById<android.widget.Button>(R.id.btn_verify)

        // В XML бейдж/подсказка/кнопка стоят gone — включаем их, раз идентичность есть.
        findViewById<View>(R.id.ll_identity_badge).visibility = View.VISIBLE
        tvHint.visibility = View.VISIBLE
        btnVerify.visibility = View.VISIBLE
        findViewById<View>(R.id.warn_identity_changed).visibility =
            if (keyChanged) View.VISIBLE else View.GONE

        // Состояние идентичности → иконка щита, цвет-токен, текст, пульсация.
        val iconRes: Int
        val tintRes: Int
        val statusText: String
        val pulse: Boolean
        when {
            keyChanged -> {
                iconRes = R.drawable.ic_shield_x; tintRes = R.color.error
                statusText = getString(R.string.identity_changed); pulse = true
            }
            !sigValid -> {
                iconRes = R.drawable.ic_shield_x; tintRes = R.color.error
                statusText = getString(R.string.identity_unverified); pulse = true
            }
            confirmed && partnerConfirmedMe -> {
                iconRes = R.drawable.ic_shield_check; tintRes = R.color.accent
                statusText = getString(R.string.identity_confirmed_both); pulse = false
            }
            confirmed -> {
                iconRes = R.drawable.ic_shield_check; tintRes = R.color.accent
                statusText = getString(R.string.identity_confirmed_waiting); pulse = false
            }
            else -> {
                iconRes = R.drawable.ic_shield; tintRes = R.color.warning
                statusText = getString(R.string.identity_not_confirmed); pulse = true
            }
        }

        val tint = ContextCompat.getColor(this, tintRes)
        val tintList = android.content.res.ColorStateList.valueOf(tint)
        shield.setImageResource(iconRes)
        shield.imageTintList = tintList
        shield.visibility = View.VISIBLE
        badge.setImageResource(iconRes)
        badge.imageTintList = tintList
        tvBadge.text = statusText
        tvBadge.setTextColor(tint)
        tvHint.text = if (keyChanged) getString(R.string.identity_changed_warn)
                      else getString(R.string.verify_hint)

        if (pulse) startShieldPulse(shield) else stopShieldPulse(shield)

        // Живая плашка взаимной сверки (waiting / both).
        renderVerifyPill(confirmed, partnerConfirmedMe, chatId, shield)

        // Щит раскрывает/прячет секцию безопасности.
        shield.setOnClickListener {
            card.visibility = if (card.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Кнопка ручного подтверждения подлинности (сверка SAS/QR лично).
        btnVerify.text = if (confirmed) getString(R.string.verify_cancel_btn)
                         else getString(R.string.verify_confirm_btn)
        btnVerify.setOnClickListener {
            if (prefs.getConfirmedPartnerIdentity(chatId) == idk) {
                prefs.clearConfirmedPartnerIdentity(chatId)
                // Снятие подтверждения долетит до собеседника при возврате в чат
                // (ChatActivity заново публикует профиль уже без vpk).
            } else {
                prefs.setConfirmedPartnerIdentity(chatId, idk)
                // Публикуем подтверждение СРАЗУ — собеседник узнает, не дожидаясь чата.
                publishMyConfirmation(chatId, idk)
            }
            applyIdentityBadge(chatId)
        }
    }

    /** Пульсация щита — мягко привлекает внимание к непроверенной идентичности. */
    private fun startShieldPulse(shield: View) {
        stopShieldPulse(shield)
        shieldPulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            shield,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f)
        ).apply {
            duration = 700
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** Останавливает пульсацию щита и возвращает исходный размер. */
    private fun stopShieldPulse(shield: View) {
        shieldPulse?.cancel()
        shieldPulse = null
        shield.scaleX = 1f
        shield.scaleY = 1f
    }

    // ── Живая плашка взаимной сверки ───────────────────────────────────────────

    /** Рисует плашку: «ждём собеседника» (с точками) ↔ «подтверждено обоими» (pop-анимация). */
    private fun renderVerifyPill(
        confirmed: Boolean,
        partnerConfirmedMe: Boolean,
        chatId: String,
        shield: View
    ) {
        val pill  = findViewById<LinearLayout>(R.id.ll_verify_status)
        val icon  = findViewById<ImageView>(R.id.iv_verify_status_icon)
        val title = findViewById<TextView>(R.id.tv_verify_status_title)
        val sub   = findViewById<TextView>(R.id.tv_verify_status_sub)
        val white = ContextCompat.getColor(this, R.color.white)

        when {
            confirmed && partnerConfirmedMe -> {
                stopDots(); stopVerifySync()  // оба подтвердили — опрашивать нечего
                pill.visibility = View.VISIBLE
                pill.setBackgroundResource(R.drawable.bg_verify_both)
                icon.setImageResource(R.drawable.ic_shield_check)
                icon.setColorFilter(white)
                title.text = getString(R.string.verify_status_both_title); title.setTextColor(white)
                sub.text = getString(R.string.verify_status_both_sub)
                sub.setTextColor((0xCCFFFFFF).toInt())   // белый 80% на акценте
                if (!lastBothConfirmed) { popAnim(icon); popAnim(shield) }  // только при переходе
                lastBothConfirmed = true
            }
            confirmed -> {
                pill.visibility = View.VISIBLE
                pill.setBackgroundResource(R.drawable.bg_verify_waiting)
                icon.setImageResource(R.drawable.ic_clock)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.accent_light))
                title.text = getString(R.string.verify_status_waiting_title)
                title.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                sub.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                startDots(sub)
                lastBothConfirmed = false
                startVerifySync(chatId)   // пока экран открыт — ловим подтверждение партнёра
            }
            else -> {
                pill.visibility = View.GONE
                stopDots(); stopVerifySync()
                lastBothConfirmed = false
            }
        }
    }

    private fun startDots(sub: TextView) {
        stopDots()
        val base = getString(R.string.verify_status_waiting_sub)
        dotsJob = lifecycleScope.launch {
            var i = 0
            while (true) {
                sub.text = base + ".".repeat(i % 4)
                i++
                delay(450L)
            }
        }
    }

    private fun stopDots() { dotsJob?.cancel(); dotsJob = null }

    private fun popAnim(v: View) {
        android.animation.ObjectAnimator.ofPropertyValuesHolder(
            v,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1.25f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1.25f, 1f)
        ).apply { duration = 460; start() }
    }

    /** Одноразово публикует моё подтверждение. pushPresence сохраняет eph/sig — FS не ломается. */
    private fun publishMyConfirmation(chatId: String, partnerIdk: String) {
        if (verifyToken.isBlank() || verifyPassword.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val transport = TransportFactory.forChat(this@PartnerProfileActivity, chatId, verifyToken, verifyPassword, prefs.myUserId)
                ProfileSync.pushPresence(
                    api = transport, password = verifyPassword, myUserId = prefs.myUserId,
                    typingTs = 0L, onlineTs = 0L,
                    myName = prefs.myName, myTag = prefs.myTag, myAvatarBase64 = prefs.myAvatarBase64,
                    myIdentityPubKey = prefs.myIdentityPubKey,
                    myVerifiedPartnerIdk = partnerIdk
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Лёгкий опрос профиля партнёра, ПОКА открыт экран сверки и пока не «подтверждено обоими».
     * Это не сообщенческий цикл: ChatActivity на паузе → второго опросчика одновременно нет.
     * Только через ChatTransport, ~2.5 c, авто-стоп на «оба»/паузе. Тайминги чата не трогаются.
     */
    private fun startVerifySync(chatId: String) {
        if (verifySyncJob != null) return
        if (verifyToken.isBlank() || verifyPassword.isBlank()) return
        verifySyncJob = lifecycleScope.launch {
            val transport = TransportFactory.forChat(this@PartnerProfileActivity, chatId, verifyToken, verifyPassword, prefs.myUserId)
            while (true) {
                try {
                    val profiles = withContext(Dispatchers.IO) {
                        ProfileSync.pullProfiles(transport, verifyPassword)
                    }
                    val partner = profiles.values.firstOrNull { it.userId != prefs.myUserId }
                    val partnerVerifiedMe = partner?.verifiedPartnerIdk != null &&
                        partner.verifiedPartnerIdk == prefs.myIdentityPubKey
                    val cur = IdentityState.get(chatId)
                    if (cur.partnerVerifiedMe != partnerVerifiedMe) {
                        IdentityState.set(chatId, cur.copy(partnerVerifiedMe = partnerVerifiedMe))
                        applyIdentityBadge(chatId)  // перерисует плашку; при «оба» сам остановит цикл
                    }
                    if (partnerVerifiedMe && prefs.getConfirmedPartnerIdentity(chatId) != null) break
                } catch (_: Exception) {}
                delay(2500L)
            }
        }
    }

    private fun stopVerifySync() { verifySyncJob?.cancel(); verifySyncJob = null }

    override fun onResume() {
        super.onResume()
        if (verifychatId.isNotEmpty()) applyIdentityBadge(verifychatId)  // живой ре-рендер + рестарт sync
    }

    override fun onPause() {
        super.onPause()
        stopVerifySync(); stopDots()  // уходим с экрана — не опрашиваем в фоне
    }
}
