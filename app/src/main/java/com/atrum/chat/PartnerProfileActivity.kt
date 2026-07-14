package com.atrum.chat

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.transport.TransportFactory
import com.google.android.material.imageview.ShapeableImageView
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
        /** Демо-экран профиля группы (TesterSettingsActivity) — см. setupDemoGroupProfile(). */
        const val EXTRA_DEMO_GROUP = "demo_group"
        /** true — этот чат реально групповой (ADR-001), см. setupRealGroupExtras(). */
        const val EXTRA_IS_GROUP = "is_group"
        /** true — системный чат «Уведомления» (read-only инфо-экран, см. setupSystemNotifProfile). */
        const val EXTRA_SYSTEM_NOTIF = "system_notif"
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

    // ── Групповой чат, реальные данные (ADR-001) ────────────────────────────────
    private var groupChatRoomId: Long = -1L
    private var groupIsAdmin: Boolean = false
    /** Мультиподпись (Этап 2): я главный ИЛИ делегат с правом MODERATE — могу мутить/банить. */
    private var groupCanModerate: Boolean = false
    /** Я главный ИЛИ делегат с правом STATS — могу открыть статистику беседы. */
    private var groupCanStats: Boolean = false
    private var groupChatCached: com.atrum.chat.data.Chat? = null

    /**
     * Последние подтянутые живые профили участников группы (имя/аватар/онлайн),
     * кэш для перерисовки списка при живых апдейтах ChatParticipantDao (см.
     * loadAndRenderGroupMembers) — чтобы не делать сетевой pullProfiles на КАЖДОЕ
     * изменение таблицы участников, а переиспользовать уже известные профили.
     */
    private var lastGroupProfiles: Map<String, Profile> = emptyMap()

    /**
     * Сигнатура последнего отрисованного состояния списка участников группы (см.
     * renderGroupMembersRows) — нужна, чтобы новый периодический опрос профилей
     * (раз в 3с, см. loadAndRenderGroupMembers) не пересобирал весь container с нуля
     * (removeAllViews + заново все View), когда по факту ничего видимого не изменилось.
     * Без этого список "мигал" бы каждые 3с даже когда все данные те же самые —
     * нарушение CLAUDE.md §14 п.1 (лишние полные ребайнды/мигание).
     */
    private var lastRenderedMembersSignature: String? = null
    private var groupAvatarPendingBitmap: Bitmap? = null
    private var lastBothConfirmed = false     // чтобы pop-анимацию проигрывать только при переходе

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_partner_profile)

        // Демо-профиль группы — полностью отдельная ветка, не трогает реальный
        // 1:1-путь ниже (нет transport/chatId/password, поэтому live-сверка и
        // подгрузка медиа по сети тут не запускаются вообще).
        if (intent.getBooleanExtra(EXTRA_DEMO_GROUP, false)) {
            setupDemoGroupProfile()
            return
        }

        // Системный чат «Уведомления» — минимальный read-only инфо-экран: колокольчик,
        // имя и карточка описания; всё остальное (участники, медиа, безопасность,
        // редактирование) скрыто. Отдельная ветка, реальный 1:1-путь ниже не тронут.
        if (intent.getBooleanExtra(EXTRA_SYSTEM_NOTIF, false)) {
            setupSystemNotifProfile()
            return
        }

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

        // Групповой чат (ADR-001): дополнительная real-секция поверх уже отрисованного
        // общего профиля (имя/аватар/фото/голос/ссылки выше УЖЕ реальные и общие для
        // 1:1 и групп — ничего не дублируем). Тут только то, чего нет у 1:1: карточка
        // безопасности (ECDH-сверка) скрывается, список участников и управление ими.
        if (intent.getBooleanExtra(EXTRA_IS_GROUP, false)) {
            setupRealGroupExtras(chatIdForMedia, chatId, chatPassword)
        }
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

        // ECDH/SAS-сверка личности имеет смысл ТОЛЬКО для 1:1 (пара эфемерных ключей,
        // один код на двоих). Для группы это концептуально не работает: общий пароль
        // без per-pair forward-secrecy сессии, участников может быть больше двух — сверять
        // "код" не с кем и не с чем (репорт: щит был кликабелен и открывал SAS-карточку в
        // группе). Проверяем на каждый вызов (а не только один раз в setupRealGroupExtras),
        // потому что applyIdentityBadge дёргается ещё и из onResume/периодической сверки —
        // так надёжнее, чем полагаться на порядок вызовов.
        if (intent.getBooleanExtra(EXTRA_IS_GROUP, false)) {
            shield.visibility = View.GONE
            card.visibility = View.GONE
            stopShieldPulse(shield)
            return
        }

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

    // ══════════════════════════════════════════════════════════════════════════
    // ═══ Демо-профиль группы (EXTRA_DEMO_GROUP) ═══
    // ⚠️ Полностью локальный предпросмотр интерфейса. НИЧЕГО не сохраняется и не
    // синкается — группы как реальной сущности ещё нет (нет multi-party
    // крипто-схемы/списка участников/ролей/бан-листа, см. ROADMAP_TODO.md §8-9).
    // Доступ: Настройки → «Для тестировщиков» → «Предпросмотр: профиль группы».
    // ══════════════════════════════════════════════════════════════════════════

    private data class DemoMember(
        val name: String,
        val isAdmin: Boolean,
        val online: Boolean,
        val lastSeenText: String?,
        val avatarColor: Int,
        var banned: Boolean = false
    )

    private var demoIsAdminView = false
    private var demoGroupName = ""
    private var demoGroupAvatarBitmap: Bitmap? = null

    private val demoMembers by lazy {
        mutableListOf(
            DemoMember("Игорь", isAdmin = true,  online = true,  lastSeenText = null, avatarColor = 0xFF3A5A78.toInt()),
            DemoMember("Аня",   isAdmin = false, online = false, lastSeenText = getString(R.string.demo_member_last_seen), avatarColor = 0xFF6B4A5C.toInt()),
            DemoMember("Марк",  isAdmin = false, online = false, lastSeenText = null, avatarColor = 0xFF5A4A3D.toInt()),
            DemoMember("Соня",  isAdmin = false, online = true,  lastSeenText = null, avatarColor = 0xFF4A4A52.toInt())
        )
    }

    /**
     * Инфо-экран системного чата «Уведомления» (мокап одобрен): колокольчик, имя и
     * карточка описания под ним — только чтение. Все секции беседы/1:1 (участники,
     * медиа, безопасность, статус, редактирование) скрыты. Строки берутся из ресурсов
     * (RU/EN автоматически по локали приложения/системы).
     */
    private fun setupSystemNotifProfile() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tv_profile_name).text = getString(R.string.notif_chat_name)

        // Аватар-колокольчик: фиолетовая заглушка уже под iv_profile_avatar, кладём
        // иконку по центру (не centerCrop — маленький вектор растянулся бы).
        findViewById<TextView>(R.id.tv_avatar_initial).visibility = View.GONE
        findViewById<ShapeableImageView>(R.id.iv_profile_avatar).apply {
            visibility = View.VISIBLE
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            // Колокольчик крупнее (репорт: «аватарка будто маленькая»): меньше отступ —
            // иконка ~68dp на 112dp-круге, читается как полноценный аватар.
            val pad = (resources.displayMetrics.density * 22).toInt()
            setPadding(pad, pad, pad, pad)
            setImageResource(R.drawable.ic_bell)
            setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.white))
        }

        // Подпись «системные уведомления» — в поле тега под именем.
        findViewById<TextView>(R.id.tv_profile_tag).apply {
            text = getString(R.string.notif_chat_subtitle)
            visibility = View.VISIBLE
        }

        // Карточка описания — переиспользуем группового описания, но БЕЗ карандаша.
        findViewById<View>(R.id.card_group_description).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_group_description).text = getString(R.string.notif_chat_description)
        findViewById<ImageButton>(R.id.btn_group_description_edit).visibility = View.GONE

        // Всё остальное — скрыто (read-only инфо-экран).
        findViewById<ImageButton>(R.id.btn_avatar_edit_demo).visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_name_edit_demo).visibility = View.GONE
        findViewById<ImageButton>(R.id.btn_shield_status).visibility = View.GONE
        findViewById<View>(R.id.card_status).visibility = View.GONE
        findViewById<View>(R.id.section_members).visibility = View.GONE
        findViewById<View>(R.id.card_security).visibility = View.GONE
        findViewById<View>(R.id.section_photos).visibility = View.GONE
        findViewById<View>(R.id.section_voice).visibility = View.GONE
        findViewById<View>(R.id.section_links).visibility = View.GONE
        findViewById<View>(R.id.demo_role_toggle)?.visibility = View.GONE
    }

    private fun setupDemoGroupProfile() {
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        demoGroupName = getString(R.string.demo_group_name)
        findViewById<TextView>(R.id.tv_profile_name).text = demoGroupName

        val tvTag = findViewById<TextView>(R.id.tv_profile_tag)
        tvTag.text = getString(R.string.group_members_count_fmt, demoMembers.size + 1)
        tvTag.visibility = View.VISIBLE

        findViewById<ShapeableImageView>(R.id.iv_profile_avatar).visibility = View.GONE
        findViewById<TextView>(R.id.tv_avatar_initial).apply {
            visibility = View.VISIBLE
            text = demoGroupName.trim().firstOrNull()?.uppercase() ?: "?"
        }

        findViewById<View>(R.id.card_status).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_status).text = getString(R.string.demo_group_status_text)

        val avatarEditBtn = findViewById<ImageButton>(R.id.btn_avatar_edit_demo)
        avatarEditBtn.setOnClickListener { openDemoAvatarPicker() }

        val nameEditBtn = findViewById<ImageButton>(R.id.btn_name_edit_demo)
        nameEditBtn.setOnClickListener { renameDemoGroup() }

        val roleToggle = findViewById<LinearLayout>(R.id.demo_role_toggle)
        roleToggle.visibility = View.VISIBLE
        val roleMember = findViewById<TextView>(R.id.demo_role_member)
        val roleAdmin = findViewById<TextView>(R.id.demo_role_admin)
        roleMember.setOnClickListener { demoIsAdminView = false; applyDemoRole(roleMember, roleAdmin, avatarEditBtn, nameEditBtn) }
        roleAdmin.setOnClickListener { demoIsAdminView = true; applyDemoRole(roleMember, roleAdmin, avatarEditBtn, nameEditBtn) }
        applyDemoRole(roleMember, roleAdmin, avatarEditBtn, nameEditBtn)

        buildDemoPhotos()
        buildDemoVoice()
        buildDemoLinks()
    }

    private fun applyDemoRole(
        roleMember: TextView,
        roleAdmin: TextView,
        avatarEditBtn: ImageButton,
        nameEditBtn: ImageButton
    ) {
        roleMember.setBackgroundResource(if (demoIsAdminView) R.drawable.bg_chip_default else R.drawable.bg_chip_selected)
        roleMember.setTextColor(ContextCompat.getColor(this, if (demoIsAdminView) R.color.text_secondary else R.color.accent_light))
        roleAdmin.setBackgroundResource(if (demoIsAdminView) R.drawable.bg_chip_selected else R.drawable.bg_chip_default)
        roleAdmin.setTextColor(ContextCompat.getColor(this, if (demoIsAdminView) R.color.accent_light else R.color.text_secondary))

        avatarEditBtn.visibility = if (demoIsAdminView) View.VISIBLE else View.GONE
        nameEditBtn.visibility = if (demoIsAdminView) View.VISIBLE else View.GONE

        val membersSection = findViewById<View>(R.id.section_members)
        membersSection.visibility = if (demoIsAdminView) View.VISIBLE else View.GONE
        if (demoIsAdminView) buildDemoMembers()
    }

    private fun buildDemoMembers() {
        val container = findViewById<LinearLayout>(R.id.ll_members_container)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        demoMembers.forEach { member ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(7), dp(24), dp(7))
                alpha = if (member.banned) 0.4f else 1f
            }

            val avatar = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(member.avatarColor)
                }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).also { it.marginEnd = dp(12) }
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTv = TextView(this).apply {
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_primary))
                text = if (member.isAdmin) {
                    val suffix = "  · " + getString(R.string.demo_role_admin_suffix)
                    android.text.SpannableStringBuilder(member.name + suffix).apply {
                        setSpan(
                            android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_tertiary)),
                            member.name.length, member.name.length + suffix.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                } else member.name
            }
            val statusTv = TextView(this).apply {
                textSize = 11.5f
                val (txt, colorRes) = when {
                    member.banned -> getString(R.string.demo_member_banned) to R.color.error
                    member.online -> getString(R.string.demo_member_online) to R.color.accent
                    else -> (member.lastSeenText ?: getString(R.string.demo_member_offline)) to R.color.text_tertiary
                }
                text = txt
                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, colorRes))
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(nameTv); col.addView(statusTv)
            row.addView(avatar); row.addView(col)

            if (!member.isAdmin && !member.banned) {
                val rippleVal = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleVal, true)
                val banBtn = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_tertiary))
                    setBackgroundResource(rippleVal.resourceId)
                    contentDescription = getString(R.string.demo_ban_cd, member.name)
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener { confirmBanDemo(member) }
                }
                row.addView(banBtn)
            }

            container.addView(row)
        }
    }

    private fun confirmBanDemo(member: DemoMember) {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.demo_ban_title, member.name),
            message = getString(R.string.demo_ban_message),
            positiveText = getString(R.string.demo_ban_confirm),
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel),
            onPositive = {
                member.banned = true
                buildDemoMembers()
            }
        )
    }

    private fun renameDemoGroup() {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.cc_group_name_label),
            initialText = demoGroupName,
            positiveText = getString(R.string.btn_save),
            negativeText = getString(R.string.btn_cancel),
            onPositive = { newName ->
                val trimmed = newName.trim()
                if (trimmed.isNotEmpty()) {
                    demoGroupName = trimmed
                    findViewById<TextView>(R.id.tv_profile_name).text = demoGroupName
                    if (demoGroupAvatarBitmap == null) {
                        findViewById<TextView>(R.id.tv_avatar_initial).text =
                            demoGroupName.trim().firstOrNull()?.uppercase() ?: "?"
                    }
                }
            }
        )
    }

    // ── Аватар группы (демо) — системный пикер + UCrop, как у обычной аватарки ──

    private val pickDemoAvatar = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) startDemoAvatarCrop(uri) }

    private val cropDemoAvatar = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = UCrop.getOutput(result.data!!)
            if (uri != null) applyDemoAvatarUri(uri)
        } else if (result.resultCode == UCrop.RESULT_ERROR && result.data != null) {
            val err = UCrop.getError(result.data!!)
            android.widget.Toast.makeText(this, getString(R.string.error_avatar_load) + ": ${err?.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDemoAvatarPicker() {
        pickDemoAvatar.launch("image/*")
    }

    private fun startDemoAvatarCrop(sourceUri: Uri) {
        val destUri = Uri.fromFile(File(cacheDir, "demo_group_avatar_crop_${System.currentTimeMillis()}.jpg"))
        val options = UCrop.Options().apply {
            setCircleDimmedLayer(true)
            setShowCropFrame(false)
            setShowCropGrid(false)
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setToolbarTitle(getString(R.string.crop_avatar_title))
            setHideBottomControls(true)
            setFreeStyleCropEnabled(false)
        }
        cropDemoAvatar.launch(
            UCrop.of(sourceUri, destUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(1024, 1024)
                .withOptions(options)
                .getIntent(this)
        )
    }

    private fun applyDemoAvatarUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = AvatarUtils.loadAndResize(this@PartnerProfileActivity, uri)
            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    demoGroupAvatarBitmap = bmp
                    findViewById<ShapeableImageView>(R.id.iv_profile_avatar).apply {
                        setImageBitmap(bmp)
                        visibility = View.VISIBLE
                    }
                    findViewById<TextView>(R.id.tv_avatar_initial).visibility = View.GONE
                } else {
                    android.widget.Toast.makeText(this@PartnerProfileActivity, R.string.error_avatar_load, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Групповой чат, РЕАЛЬНЫЕ данные (ADR-001) ────────────────────────────────
    // См. ADR_GROUP_CHATS.md. Источник истины — members.txt (подписан админом),
    // применяется в ChatActivity.processChannelData(), локальный кэш — ChatParticipantDao.
    // Имя/аватар/фото/голос/ссылки выше в onCreate() УЖЕ реальные (общий код с 1:1) —
    // здесь только специфичное для групп: список участников, бан, ре-публикация
    // имени/аватара группы через тот же подписанный канал.

    // freshMembersVersion() удалена: версию members.txt теперь читает воркер
    // PublishScheduler из Room в момент публикации — стало невозможно опубликовать
    // устаревший номер в принципе (сериализация очередью).

    /**
     * Единая публикация members.txt от лица админа — все do*Real этого экрана обязаны
     * ходить только сюда. Чинит сразу два бага из репорта пользователя:
     *
     * 1. «Плашка мута появляется поздно / не появляется». Раньше каждый вызов строил
     *    NostrTransport(preferTor = true) НАПРЯМУЮ, игнорируя реальный transportToken
     *    чата. Для direct-чатов (nostrdirect) публикация насильно уходила в Tor, причём
     *    TorManager.start() здесь никто не вызывал — если демон Tor не был запущен,
     *    publish висел до таймаутов (десятки секунд) или падал, и заглушённый узнавал
     *    о муте с большим опозданием. TransportFactory.forChat повторяет ровно тот путь,
     *    которым ходит сам чат (и стартует Tor, когда он действительно нужен).
     *
     * 2. Гонка версий с maybeAdminEnrollNewMembers (ChatActivity). Раньше новая версия
     *    НЕ сохранялась локально до обратного чтения собственного members.txt с реле
     *    (eventual consistency — несколько тиков). Всё это время enroll-путь считал
     *    newVersion от СТАРОГО значения и мог опубликовать другой members.txt с ТЕМ ЖЕ
     *    номером — replaceable-событие на реле перекрывало мут/бан, а анти-откат у
     *    клиентов молча отбрасывал второй вариант: статус «терялся» без единой ошибки.
     *    Persist сразу после успешной публикации (тот же приём, что уже применён в
     *    maybeAdminEnrollNewMembers).
     */
    private suspend fun publishMembersAsAdmin(
        chat: com.atrum.chat.data.Chat,
        database: com.atrum.chat.data.AppDatabase,
        participants: List<MembersSync.Entry>,
        groupName: String? = chat.groupName,
        groupAvatarBase64: String? = chat.groupAvatarBase64,
        groupDescription: String? = chat.groupDescription
    ) {
        val adminUserId = chat.adminUserId ?: return
        // Мультиподпись (Этап 2): публиковать может главный ИЛИ делегат с MODERATE. Планировщик
        // (PublishScheduler.publishMembers) сам решит — главный двигает роль-версию и пишет
        // ростер, делегат публикует свой слот только с мут/бан (верховенство главного). EDIT-
        // действия (имя/ава/описание) вызывают это лишь у главного — они гейтятся в своих do*.
        if (!groupCanModerate) return
        // ⚠️ ВСЯ публикация — через планировщик (PublishScheduler, запрос пользователя:
        // «планировщик событий, чтобы ставил действия в очередь»). Room уже обновлён
        // вызывающим do*Real ДО этого вызова (§1.5) — воркер очереди публикует СНИМОК
        // из Room с версией из Room, строго последовательно. Это разом закрывает:
        //  • гонку версий «сменил имя и аву подряд — одно из двух потерялось у всех»
        //    (два параллельных launch публиковали одинаковый номер версии);
        //  • «мут лагает при быстрых мутах» — быстрые действия коалесцируются в одну
        //    публикацию со всеми изменениями;
        //  • потерю недоставленного при смерти процесса (флаги персистентны).
        // [participants]/[database] сохранены в сигнатуре для совместимости вызовов —
        // снимок воркер строит сам, свежее любого аргумента.
        PublishScheduler.markMembersDirty(applicationContext, chat.chatId)
        // Профиль беседы — только когда имя/ава/описание реально изменились этим
        // действием (мут/бан остаются ровно одним крошечным событием members.txt).
        val profileChanged = groupName != chat.groupName ||
            groupAvatarBase64 != chat.groupAvatarBase64 ||
            groupDescription != chat.groupDescription
        if (profileChanged && (groupName != null || groupAvatarBase64 != null || groupDescription != null)) {
            PublishScheduler.markProfileDirty(applicationContext, chat.chatId)
        }
    }

    private fun setupRealGroupExtras(chatRoomId: Long, networkChatId: String, password: String) {
        // ECDH-сверка (fingerprint/QR/identity badge) не применима — группа шифруется
        // общим паролем без forward-secrecy сессии (см. ChatActivity.applyGroupPresence).
        findViewById<View>(R.id.card_security).visibility = View.GONE
        // "Роль"-переключатель существовал только для демо-превью обоих видов экрана —
        // в реальном режиме роль фиксирована (я админ или нет), переключать нечего.
        findViewById<View>(R.id.demo_role_toggle)?.visibility = View.GONE

        groupChatRoomId = chatRoomId
        val database = com.atrum.chat.data.AppDatabase.get(this)

        lifecycleScope.launch {
            val chat = withContext(Dispatchers.IO) { database.chatDao().getById(chatRoomId) } ?: return@launch
            groupChatCached = chat
            groupIsAdmin = !chat.adminUserId.isNullOrBlank() && chat.adminUserId == prefs.myUserId
            // Мультиподпись (Этап 2): делегированные права из моей записи участника.
            val myPerms = withContext(Dispatchers.IO) {
                database.chatParticipantDao().getOne(chat.id, prefs.myUserId)?.permissions ?: 0
            }
            groupCanModerate = groupIsAdmin || AdminPermissions.has(myPerms, AdminPermissions.MODERATE)
            groupCanStats = groupIsAdmin || AdminPermissions.has(myPerms, AdminPermissions.STATS)

            val avatarEditBtn = findViewById<ImageButton>(R.id.btn_avatar_edit_demo)
            val nameEditBtn = findViewById<ImageButton>(R.id.btn_name_edit_demo)
            // Статистика активности участников — кнопка в шапке (справа от «назад»),
            // доступна ТОЛЬКО админу. Раньше жила отдельной карточкой под списком
            // участников — пользователь не смог её найти (репорт «кнопки нет»), перенесена
            // в шапку и переключается в ОДНОМ блоке с карандашами редактирования (тем же
            // проверенным условием groupIsAdmin), чтобы не повторить тот же баг.
            val statsBtn = findViewById<ImageButton>(R.id.btn_group_stats)
            // Карандаши правки имени/авы — право EDIT (пока только главный, см. Этап 2 «основа»).
            if (groupIsAdmin) {
                avatarEditBtn.visibility = View.VISIBLE
                nameEditBtn.visibility = View.VISIBLE
                avatarEditBtn.setOnClickListener { pickRealGroupAvatar.launch("image/*") }
                nameEditBtn.setOnClickListener { renameGroupReal() }
            } else {
                avatarEditBtn.visibility = View.GONE
                nameEditBtn.visibility = View.GONE
            }
            // Статистика — право STATS (главный или делегат с этим правом).
            if (groupCanStats) {
                statsBtn.visibility = View.VISIBLE
                statsBtn.setOnClickListener {
                    startActivity(android.content.Intent(this@PartnerProfileActivity, GroupStatsActivity::class.java).apply {
                        putExtra(GroupStatsActivity.EXTRA_CHAT_ID, chatRoomId)
                    })
                }
            } else {
                statsBtn.visibility = View.GONE
            }

            renderGroupDescription(chat)

            findViewById<View>(R.id.section_members).visibility = View.VISIBLE
            loadAndRenderGroupMembers(chat, networkChatId, password, database)
        }
    }

    /**
     * Карточка описания группы. У админа видна ВСЕГДА (плейсхолдер-приглашение,
     * если описание ещё не задано) — у остальных участников только если описание
     * заполнено. Карандаш редактирования — только у админа.
     */
    private fun renderGroupDescription(chat: com.atrum.chat.data.Chat) {
        val card = findViewById<View>(R.id.card_group_description)
        val tv = findViewById<TextView>(R.id.tv_group_description)
        val editBtn = findViewById<ImageButton>(R.id.btn_group_description_edit)
        val description = chat.groupDescription?.trim().orEmpty()

        if (description.isNotEmpty()) {
            card.visibility = View.VISIBLE
            tv.text = description
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else if (groupIsAdmin) {
            card.visibility = View.VISIBLE
            tv.text = getString(R.string.group_description_placeholder_admin)
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_quaternary))
        } else {
            card.visibility = View.GONE
        }

        if (groupIsAdmin) {
            editBtn.visibility = View.VISIBLE
            editBtn.setOnClickListener { editGroupDescriptionReal() }
            card.setOnClickListener { editGroupDescriptionReal() }
        } else {
            editBtn.visibility = View.GONE
            card.setOnClickListener(null)
        }
    }

    private fun editGroupDescriptionReal() {
        val chat = groupChatCached ?: return
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.group_description_dialog_title),
            initialText = chat.groupDescription.orEmpty(),
            positiveText = getString(R.string.btn_save),
            negativeText = getString(R.string.btn_cancel),
            onPositive = { newText -> doEditGroupDescriptionReal(chat, newText.trim().take(300)) }
        )
    }

    /**
     * Сохранение описания группы — тот же путь, что переименование/смена аватара
     * (ADR-001): локально сразу, публикация через members.txt в фоне. В отличие от
     * имени, описание разрешено сохранять пустым (админ может убрать текст — плейсхолдер
     * вернётся), но пустая строка НЕ попадёт в members.txt явно (см. MembersSync.buildContent —
     * "" считается "не менять"), поэтому обнуление описания видно только локально у
     * админа, пока не будет отдельного протокола очистки поля. Не блокирует текущую
     * задачу — тот же принцип уже действует для groupName/groupAvatarBase64.
     */
    private fun doEditGroupDescriptionReal(chat: com.atrum.chat.data.Chat, newDescription: String) {
        val adminUserId = chat.adminUserId ?: return
        if (adminUserId != prefs.myUserId) return
        lifecycleScope.launch {
            val database = com.atrum.chat.data.AppDatabase.get(this@PartnerProfileActivity)
            withContext(Dispatchers.IO) {
                database.chatDao().updateGroupProfile(chat.id, chat.groupName, chat.groupAvatarBase64, newDescription)
            }
            val updated = chat.copy(groupDescription = newDescription)
            groupChatCached = updated
            renderGroupDescription(updated)
            try {
                val participants = withContext(Dispatchers.IO) { database.chatParticipantDao().getForChat(chat.id) }
                    .map { MembersSync.Entry(it.userId, it.banned, it.mutedUntilMs, it.mutedReason, MembersSync.evidenceIdsFromStore(it.mutedEvidenceIds), it.permissions) }
                publishMembersAsAdmin(
                    chat, database,
                    participants = participants,
                    groupName = chat.groupName,
                    groupAvatarBase64 = chat.groupAvatarBase64,
                    groupDescription = newDescription
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    this@PartnerProfileActivity, R.string.invite_create_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Список участников: локальный кэш (ChatParticipantDao — членство/бан, источник
     * истины members.txt) + живые профили (имя/аватар/онлайн) из profiles.txt, тем же
     * способом, что уже читает ChatActivity. Показывается мгновенно из локального кэша,
     * не дожидаясь сети (§1.5 CLAUDE.md).
     */
    private suspend fun loadAndRenderGroupMembers(
        chat: com.atrum.chat.data.Chat,
        networkChatId: String,
        password: String,
        database: com.atrum.chat.data.AppDatabase
    ) {
        var latestParticipants = withContext(Dispatchers.IO) {
            database.chatParticipantDao().getForChat(chat.id)
        }
        renderGroupMembersRows(latestParticipants, emptyMap(), chat)

        // ⚠️ Фикс (репорт: "общий чат медленно грузит, держи их немного в фоне как и
        // обычные чаты"). Пока ChatActivity открыт, он и так постоянно опрашивает этот
        // же канал (SyncEngine, ~3с) и кладёт свежий снимок в ChatSnapshotCache — тем
        // же способом, каким 1:1-чаты мгновенно показываются из кэша при заходе
        // (см. ChatActivity.loadMessages(), "Попытка загрузить из кэша для мгновенного
        // отображения"). У этого экрана такого мгновенного старта не было вообще —
        // каждое открытие профиля группы поднимало ОТДЕЛЬНОЕ сетевое соединение с нуля
        // и ждало полный relay round-trip, прежде чем показать реальные имена/аватары/
        // онлайн — отсюда и ощущение "медленно грузит". Теперь сначала пробуем уже
        // тёплый снимок (почти всегда есть — сюда переходят ИЗ уже открытого чата).
        // ChatActivity.onPause() полностью останавливает SyncEngine (см. тот файл),
        // так что кэш сам по себе больше не обновляется, пока этот экран открыт —
        // поэтому ниже всё равно остаются и сетевой pullAndRenderProfiles(), и
        // периодический опрос: кэш только для мгновенного первого кадра, а не взамен
        // живого обновления.
        ChatSnapshotCache.get(networkChatId)?.let { cached ->
            val cachedProfiles = when {
                ChatActivity.SLOT_UNION_PROFILES && cached.profileSlots.isNotEmpty() ->
                    ProfileSync.unionProfileSlots(cached.profileSlots, password, networkChatId)
                cached.profilesContent.isNotBlank() ->
                    ProfileSync.parseProfiles(cached.profilesContent, password, networkChatId)
                else -> emptyMap()
            }
            if (cachedProfiles.isNotEmpty()) {
                lastGroupProfiles = cachedProfiles
                renderGroupMembersRows(latestParticipants, cachedProfiles, chat)
            }
        }

        // Живые профили — подтягиваем в фоне и перерисовываем, когда придут (без сети
        // список участников/банов уже виден выше — только имена/аватары/онлайн донагружаются).
        //
        // ⚠️ Фикс (репорт: "у админа ник участника неправильный, статус в сети не точен",
        // не проходит даже после периодического опроса). Причина — ProfileSync.pullProfiles()
        // читает ОДИН общий блоб profiles.txt (api.loadFileOrNull, последний записавший
        // "выигрывает"). Для ГРУППЫ несколько участников независимо публикуют СВОИ профили
        // в СВОИ отдельные слоты (Nostr-события) именно чтобы избежать lost-update — см.
        // ProfileSync.unionProfileSlots и ChatActivity.SLOT_UNION_PROFILES (тот же принцип,
        // уже применяется в ChatsListActivity для превью чатов). pullProfiles() эту
        // per-слотовую унию не делает вообще — если админ и второй участник почти одновременно
        // публикуют профили, чей-то профиль просто теряется в общем блобе НАВСЕГДА, сколько бы
        // раз ни опрашивали. Читаем теперь через transport.loadAll().profileSlots + union —
        // тем же способом, каким это уже надёжно работает в ChatActivity/ChatsListActivity.
        // ⚠️ Второй кусок того же фикса ("не грузить их постоянно заново"): раньше
        // pullAndRenderProfiles() поднимал НОВЫЙ NostrTransport на КАЖДЫЙ вызов —
        // при начальной загрузке, при появлении нового участника И на каждом тике
        // периодического опроса (см. ниже, раз в 3с). Один и тот же держащийся
        // "тёплым" транспорт (как у ChatActivity — там он один на весь сеанс экрана)
        // дешевле переиспользовать, чем поднимать заново каждые несколько секунд.
        // ⚠️ Тот же фикс, что и в publishMembersAsAdmin: транспорт через фабрику с реальным
        // transportToken чата (direct-чат читает напрямую, а не через насильный Tor).
        val profilesTransport = TransportFactory.forChat(
            this, networkChatId,
            mtransportToken.ifBlank { prefs.getChatToken(networkChatId) },
            password, prefs.myUserId, adminUserId = chat.adminUserId
        )
        suspend fun pullAndRenderProfiles(current: List<com.atrum.chat.data.ChatParticipant>) {
            try {
                val rawParsed = withContext(Dispatchers.IO) {
                    val all = profilesTransport.loadAll()
                    if (ChatActivity.SLOT_UNION_PROFILES && all.profileSlots.isNotEmpty()) {
                        ProfileSync.unionProfileSlots(all.profileSlots, password, networkChatId)
                    } else {
                        ProfileSync.parseProfiles(all.profilesContent, password, networkChatId)
                    }
                }
                // ⚠️ Фикс (тот же класс бага, что и "мигающие аватарки" в ChatActivity —
                // см. ChatActivity.refreshMessageAvatars): рендерим из "липкого" union
                // (ProfileSync.unionAndRemember), а НЕ из сырого снимка ЭТОГО конкретного
                // тика. Один флаки-Tor-тик, вернувший профиль без чьего-то аватара, иначе
                // на миг гасит уже показанную картинку в списке участников, и она тут же
                // возвращается на следующем 3с-тике — видимое мерцание.
                val profiles = ProfileSync.unionAndRemember(networkChatId, rawParsed)
                lastGroupProfiles = profiles
                renderGroupMembersRows(current, profiles, chat)
            } catch (_: Exception) {
                // Офлайн/сеть не ответила — остаёмся с уже отрисованным локальным кэшем.
            }
        }

        pullAndRenderProfiles(latestParticipants)

        // ⚠️ Фикс (аудит групповых чатов, см. отчёт пользователя: у админа список
        // участников/число не обновлялись без переоткрытия экрана). Раньше список
        // читался ОДИН РАЗ при открытии экрана — если ChatParticipantDao менялась уже
        // ПОСЛЕ этого (новый участник добавлен через MembersSync.applyIncoming, бан/анбан
        // с другого устройства, самолечение админ-энролла и т.п.), экран оставался со
        // старым списком до повторного захода. Прямое нарушение §1.5 CLAUDE.md
        // ("всё грузится на месте", без "перезайди, чтобы применилось"). Теперь список —
        // живой Flow, перерисовывается сразу на любое изменение локального кэша, откуда
        // бы оно ни пришло.
        //
        // ⚠️ Второй фикс (репорт: "ава пользователя подхватывается поздно после входа",
        // "статус в сети/ники отображаются неправильно"). Раньше pullAndRenderProfiles()
        // вызывался ТОЛЬКО при открытии экрана и один раз при появлении нового участника
        // в ChatParticipantDao — если в этот самый момент новый участник ещё не успел
        // опубликовать/протолкнуть свой profiles.txt на реле (обычная задержка сети/Tor),
        // повторной попытки уже не было ВООБЩЕ — аватар/имя/онлайн так и оставались
        // пустыми/устаревшими до переоткрытия экрана. Теперь профили ещё и опрашиваются
        // периодически (раз в 3с — тот же интервал, что и активный Nostr-поллинг
        // SyncEngine, см. CLAUDE.md §1), пока этот экран открыт — тот же принцип "всё
        // подхватывается само", что и в ChatActivity.
        //
        // coroutineScope { } — оба child-а (тикер и Flow.collect) структурно привязаны
        // к этому suspend-вызову: при отмене родительской корутины (см. lifecycleScope
        // в setupRealGroupExtras, отменяется при уничтожении экрана) оба гарантированно
        // останавливаются, включая тикер, даже если бы collect не отменился первым.
        coroutineScope {
            launch {
                while (isActive) {
                    delay(3000L)
                    pullAndRenderProfiles(latestParticipants)
                }
            }
            database.chatParticipantDao().observeForChat(chat.id).collect { fresh ->
                latestParticipants = fresh
                renderGroupMembersRows(fresh, lastGroupProfiles, chat)
                // Новый участник, чьего профиля (имя/аватар) ещё нет в кэше — подтянуть
                // профили немедленно, не дожидаясь следующего тика 3с-опроса выше.
                if (fresh.any { it.userId !in lastGroupProfiles }) pullAndRenderProfiles(fresh)
            }
        }
    }

    private fun renderGroupMembersRows(
        participants: List<com.atrum.chat.data.ChatParticipant>,
        profiles: Map<String, Profile>,
        chat: com.atrum.chat.data.Chat
    ) {
        val now = System.currentTimeMillis()
        val sorted = participants.sortedBy { it.joinedAtMs }

        // ⚠️ Ранний выход по сигнатуре (см. поле lastRenderedMembersSignature) — считаем
        // ТОЛЬКО из того, что реально видно на экране (не сырой profile.onlineTs,
        // который меняется почти на каждый тик даже когда бакет "онлайн/офлайн" тот
        // же самый), иначе периодический 3с-опрос профилей заново перестраивал бы
        // весь container и создавал видимое мигание без единого реального изменения.
        val signature = buildString {
            sorted.forEach { member ->
                val profile = profiles[member.userId]
                // Межчатовый fallback (см. ProfileSync.getGlobalKnown) — ТОЛЬКО для имени/
                // аватара, если в ЭТОМ чате профиль участника ещё не пришёл. Presence
                // (isOnline) из него намеренно не берём — см. комментарий у globalKnownProfiles.
                val globalFallback = if (profile == null) ProfileSync.getGlobalKnown(member.userId) else null
                val isMe = member.userId == prefs.myUserId
                val displayName = when {
                    isMe -> profile?.name?.takeIf { it.isNotBlank() } ?: prefs.myName
                    else -> profile?.name?.takeIf { it.isNotBlank() }
                        ?: globalFallback?.name?.takeIf { it.isNotBlank() }
                        ?: member.userId.take(8)
                }
                val isOnline = isMe || (profile != null && profile.onlineTs > 0L && now - profile.onlineTs < ONLINE_EXPIRY_MS_LOCAL)
                val avatarSrc = profile?.avatarBase64 ?: globalFallback?.avatarBase64 ?: (if (isMe) prefs.myAvatarBase64 else null)
                append(member.userId).append(':').append(member.banned).append(':')
                append(displayName).append(':').append(isOnline).append(':')
                append(avatarSrc?.length ?: -1).append(':')
                // ⚠️ Мут в сигнатуре ниже: без этого поля наложение/снятие мута
                // не перерисовывало бы строку, если больше ничего не изменилось
                // (ранний выход по сигнатуре считал бы состояние прежним).
                append(member.mutedUntilMs ?: -1).append(':').append(member.mutedReason ?: "")
                // ⚠️ Права в сигнатуре: без них назначение/снятие роли (0↔маска) не меняло
                // сигнатуру → ранний выход не перерисовывал строку → метка «админ» не
                // появлялась у нового делегата, хотя права в Room уже применены.
                append(':').append(member.permissions)
                append('|')
            }
            append("admin=").append(chat.adminUserId).append(",meAdmin=").append(groupIsAdmin)
        }
        if (signature == lastRenderedMembersSignature) return
        lastRenderedMembersSignature = signature

        val container = findViewById<LinearLayout>(R.id.ll_members_container)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        sorted.forEach { member ->
            val profile = profiles[member.userId]
            // Тот же межчатовый fallback, что и в сигнатуре выше — если участник ещё не
            // прислал профиль в ЭТОТ чат, но уже известен по другому (личному) чату,
            // используем его имя/аватар вместо голого userId, пока свежие данные не придут.
            val globalFallback = if (profile == null) ProfileSync.getGlobalKnown(member.userId) else null
            val isMe = member.userId == prefs.myUserId
            val displayName = when {
                isMe -> profile?.name?.takeIf { it.isNotBlank() } ?: prefs.myName
                else -> profile?.name?.takeIf { it.isNotBlank() }
                    ?: globalFallback?.name?.takeIf { it.isNotBlank() }
                    ?: member.userId.take(8)
            }
            val isAdminRow = member.userId == chat.adminUserId
            // ⚠️ Фикс (репорт: "у админа статус в сети отображается неправильно" —
            // сам админ видел себя "не в сети"). "Онлайн" для СЕБЯ считается по тому
            // же profiles.txt/сети, что и для остальных — но раз я прямо сейчас смотрю
            // этот экран, я точно онлайн, ждать собственный round-trip через сеть
            // бессмысленно и даёт заведомо неверную картину.
            val isOnline = isMe || (profile != null && profile.onlineTs > 0L && now - profile.onlineTs < ONLINE_EXPIRY_MS_LOCAL)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(7), dp(24), dp(7))
                alpha = if (member.banned) 0.4f else 1f
            }

            // ⚠️ Фикс (репорт: "у админа не отображается ава" — своя собственная).
            // Имя ниже уже давно падает назад на prefs.myName для "себя" — для аватара
            // такого фолбэка не было, поэтому свой аватар молча ждал round-trip через
            // pullProfiles(), хотя он и так уже есть локально (prefs.myAvatarBase64),
            // офлайн, без сети (§1.5 CLAUDE.md).
            val avatarBmp = AvatarUtils.fromBase64(
                profile?.avatarBase64 ?: globalFallback?.avatarBase64 ?: (if (isMe) prefs.myAvatarBase64 else null)
            )
            val avatarView: View = if (avatarBmp != null) {
                ShapeableImageView(this).apply {
                    setImageBitmap(avatarBmp)
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).also { it.marginEnd = dp(12) }
                    shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                        .setAllCornerSizes(dp(19).toFloat())
                        .build()
                }
            } else {
                TextView(this).apply {
                    text = displayName.trim().firstOrNull()?.uppercase() ?: "?"
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.white))
                    background = ContextCompat.getDrawable(this@PartnerProfileActivity, R.drawable.bg_avatar_placeholder)
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).also { it.marginEnd = dp(12) }
                }
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTv = TextView(this).apply {
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_primary))
                // Создатель беседы → «создатель»; делегированный админ (есть права) → «админ».
                val roleSuffix = when {
                    isAdminRow -> getString(R.string.role_creator_suffix)
                    AdminPermissions.isAdmin(member.permissions) -> getString(R.string.role_admin_suffix)
                    else -> null
                }
                text = if (roleSuffix != null) {
                    val suffix = "  · $roleSuffix"
                    android.text.SpannableStringBuilder(displayName + suffix).apply {
                        setSpan(
                            android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_tertiary)),
                            displayName.length, displayName.length + suffix.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                } else displayName
            }
            // Мут (ADR-001, аналог banned, но временный и мягкий — см. ChatParticipant.mutedUntilMs).
            val isMuted = !member.banned && member.mutedUntilMs != null && member.mutedUntilMs > now
            val statusTv = TextView(this).apply {
                textSize = 11.5f
                val (txt, colorRes) = when {
                    member.banned -> getString(R.string.demo_member_banned) to R.color.error
                    isMuted -> getString(R.string.demo_member_muted_fmt, formatMuteUntil(member.mutedUntilMs!!)) to R.color.warning
                    isOnline -> getString(R.string.demo_member_online) to R.color.accent
                    else -> getString(R.string.demo_member_offline) to R.color.text_tertiary
                }
                text = txt
                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, colorRes))
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(nameTv); col.addView(statusTv)
            row.addView(avatarView); row.addView(col)

            // Значок личной статистики — только у СВОЕЙ строки и только для НЕ-админа
            // (админ уже видит статистику всех, включая себя, через кнопку в шапке
            // btn_group_stats → GroupStatsActivity). По запросу пользователя: "у обычного
            // пользователя рядом с собой в списке — значок статистики, ведёт сразу в его
            // личную стату, минуя выбор участника; в чужую стату заходить нельзя" — второе
            // гарантируется тем, что кнопка есть только на isMe-строке, а на самом экране
            // статистики (UserStatsActivity.setupAndLoad) не-админ дополнительно допускается
            // ТОЛЬКО если targetUserId == его собственный (см. эту же сессию).
            if (isMe && !groupIsAdmin) {
                val rippleVal = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleVal, true)
                val statsBtn = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_chart_trend)
                    setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.accent_light))
                    setBackgroundResource(rippleVal.resourceId)
                    contentDescription = getString(R.string.my_stats_entry_cd)
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener { openMyStats(chat) }
                }
                row.addView(statsBtn)
            }

            if (groupCanModerate && !isAdminRow && !member.banned) {
                val rippleVal = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleVal, true)

                if (isMuted) {
                    val unmuteBtn = ImageButton(this).apply {
                        setImageResource(R.drawable.ic_bell)
                        setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.warning))
                        setBackgroundResource(rippleVal.resourceId)
                        contentDescription = getString(R.string.demo_unmute_cd, displayName)
                        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).also { it.marginEnd = dp(2) }
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        setOnClickListener { confirmUnmuteReal(member, displayName, chat) }
                    }
                    row.addView(unmuteBtn)
                } else {
                    val muteBtn = ImageButton(this).apply {
                        setImageResource(R.drawable.ic_bell_off)
                        setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_tertiary))
                        setBackgroundResource(rippleVal.resourceId)
                        contentDescription = getString(R.string.demo_mute_cd, displayName)
                        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).also { it.marginEnd = dp(2) }
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        setOnClickListener { showMuteDialog(member, displayName, chat) }
                    }
                    row.addView(muteBtn)
                }

                val banBtn = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_tertiary))
                    setBackgroundResource(rippleVal.resourceId)
                    contentDescription = getString(R.string.demo_ban_cd, displayName)
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener { confirmBanReal(member, displayName, chat) }
                }
                row.addView(banBtn)
            } else if (groupCanModerate && !isAdminRow && member.banned) {
                val rippleVal = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleVal, true)
                val unbanBtn = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_refresh)
                    setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.accent))
                    setBackgroundResource(rippleVal.resourceId)
                    contentDescription = getString(R.string.demo_unban_cd, displayName)
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener { confirmUnbanReal(member, displayName, chat) }
                }
                row.addView(unbanBtn)
            }

            container.addView(row)
        }
    }

    /** Личная статистика обычного (не-админа) участника — сразу его собственная страница,
     *  минуя список участников GroupStatsActivity (тот экран целиком admin-only). */
    private fun openMyStats(chat: com.atrum.chat.data.Chat) {
        startActivity(android.content.Intent(this, UserStatsActivity::class.java).apply {
            putExtra(UserStatsActivity.EXTRA_CHAT_ID, chat.id)
            putExtra(UserStatsActivity.EXTRA_USER_ID, prefs.myUserId)
            putExtra(UserStatsActivity.EXTRA_USER_NAME, prefs.myName)
            putExtra(UserStatsActivity.EXTRA_USER_AVATAR, prefs.myAvatarBase64)
        })
    }

    private fun confirmUnbanReal(member: com.atrum.chat.data.ChatParticipant, displayName: String, chat: com.atrum.chat.data.Chat) {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.demo_unban_title, displayName),
            message = getString(R.string.demo_unban_message),
            positiveText = getString(R.string.demo_unban_confirm),
            positiveIsDestructive = false,
            negativeText = getString(R.string.btn_cancel),
            onPositive = { doUnbanReal(member, chat) }
        )
    }

    /**
     * Разбан (ADR-001, §Меню забаненных). Снимает флаг banned в локальном кэше и
     * публикует новую версию members.txt. ВАЖНО (проговорено и подтверждено
     * пользователем): это НЕ отзывает и НЕ восстанавливает крипто-доступ выборочно —
     * пароль чата общий и не меняется, так что реального разделения «видел/не видел
     * старую историю» на уровне шифрования нет (см. ADR_GROUP_CHATS.md, §Технический
     * долг). Разбаненный при этом НЕ возвращается в чат автоматически — его локальный
     * чат/секреты уже удалены на его устройстве в момент бана (ChatActivity.checkSelfBanned),
     * так что администратору нужно заново поделиться с ним приглашением (см. ChatsListActivity).
     */
    private fun doUnbanReal(member: com.atrum.chat.data.ChatParticipant, chat: com.atrum.chat.data.Chat) {
        val adminUserId = chat.adminUserId ?: return
        if (!groupCanModerate) return // главный или делегат с MODERATE (Этап 2)
        lifecycleScope.launch {
            val database = com.atrum.chat.data.AppDatabase.get(this@PartnerProfileActivity)
            withContext(Dispatchers.IO) {
                database.chatParticipantDao().unban(chat.id, member.userId)
                // История модерации: разбан.
                database.muteHistoryDao().insert(
                    com.atrum.chat.data.MuteHistoryEntry(
                        ownerId = chat.id,
                        type = com.atrum.chat.data.MuteHistoryEntry.TYPE_UNBAN,
                        userId = member.userId,
                        issuedByUserId = prefs.myUserId,
                        mutedUntilMs = 0L
                    )
                )
            }
            // Перерисовываем сразу из локального кэша — оптимистично, не ждём сети (§1.5).
            val fresh = withContext(Dispatchers.IO) { database.chatParticipantDao().getForChat(chat.id) }
            renderGroupMembersRows(fresh, emptyMap(), chat)

            try {
                val entries = fresh.map { MembersSync.Entry(it.userId, it.banned, it.mutedUntilMs, it.mutedReason, MembersSync.evidenceIdsFromStore(it.mutedEvidenceIds), it.permissions) }
                publishMembersAsAdmin(
                    chat, database,
                    participants = entries,
                    groupName = chat.groupName,
                    groupAvatarBase64 = chat.groupAvatarBase64,
                    groupDescription = chat.groupDescription
                )
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@PartnerProfileActivity, R.string.demo_unban_share_hint, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    this@PartnerProfileActivity, R.string.invite_create_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun formatMuteUntil(ms: Long): String =
        java.text.SimpleDateFormat("dd.MM.yy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    private fun confirmUnmuteReal(member: com.atrum.chat.data.ChatParticipant, displayName: String, chat: com.atrum.chat.data.Chat) {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.demo_unmute_title, displayName),
            message = getString(R.string.demo_unmute_message),
            positiveText = getString(R.string.demo_unmute_confirm),
            positiveIsDestructive = false,
            negativeText = getString(R.string.btn_cancel),
            onPositive = { doUnmuteReal(member, chat) }
        )
    }

    /** Досрочное снятие мута — тот же принцип публикации, что и doUnbanReal/doBanReal. */
    private fun doUnmuteReal(member: com.atrum.chat.data.ChatParticipant, chat: com.atrum.chat.data.Chat) {
        val adminUserId = chat.adminUserId ?: return
        if (!groupCanModerate) return // главный или делегат с MODERATE (Этап 2)
        lifecycleScope.launch {
            val database = com.atrum.chat.data.AppDatabase.get(this@PartnerProfileActivity)
            withContext(Dispatchers.IO) {
                database.chatParticipantDao().unmute(chat.id, member.userId)
                database.muteHistoryDao().markLatestUnmutedEarly(chat.id, member.userId, System.currentTimeMillis())
            }
            val fresh = withContext(Dispatchers.IO) { database.chatParticipantDao().getForChat(chat.id) }
            renderGroupMembersRows(fresh, emptyMap(), chat)

            try {
                val entries = fresh.map { MembersSync.Entry(it.userId, it.banned, it.mutedUntilMs, it.mutedReason, MembersSync.evidenceIdsFromStore(it.mutedEvidenceIds), it.permissions) }
                publishMembersAsAdmin(
                    chat, database,
                    participants = entries,
                    groupName = chat.groupName,
                    groupAvatarBase64 = chat.groupAvatarBase64,
                    groupDescription = chat.groupDescription
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    this@PartnerProfileActivity, R.string.invite_create_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Кастомный диалог мута (DESIGN.md §10 — системные DatePicker/TimePicker запрещены):
     * свой календарь (месяц + отдельный степпер года — до нескольких лет вперёд без
     * пролистывания по месяцу) + часы:минуты + пресеты + причина.
     */
    private fun showMuteDialog(member: com.atrum.chat.data.ChatParticipant, displayName: String, chat: com.atrum.chat.data.Chat) {
        val view = layoutInflater.inflate(R.layout.dialog_mute_member, null)
        val tvTitle = view.findViewById<TextView>(R.id.tv_mute_title)
        val chipRow = view.findViewById<LinearLayout>(R.id.mute_chip_row)
        val tvMonth = view.findViewById<TextView>(R.id.tv_mute_month)
        val tvYear = view.findViewById<TextView>(R.id.tv_mute_year)
        val dowRow = view.findViewById<LinearLayout>(R.id.mute_dow_row)
        val grid = view.findViewById<LinearLayout>(R.id.mute_calendar_grid)
        val etHour = view.findViewById<EditText>(R.id.et_mute_hour)
        val etMinute = view.findViewById<EditText>(R.id.et_mute_minute)
        val tvSummary = view.findViewById<TextView>(R.id.tv_mute_summary)
        val etReason = view.findViewById<EditText>(R.id.et_mute_reason)
        val evidenceList = view.findViewById<LinearLayout>(R.id.mute_evidence_list)
        val evidenceEmpty = view.findViewById<TextView>(R.id.tv_evidence_empty)
        val selectedEvidenceIds = LinkedHashSet<String>()

        tvTitle.text = getString(R.string.mute_dialog_title_fmt, displayName)

        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.HOUR_OF_DAY, 1) // разумный дефолт — на час вперёд
        val selected = java.util.Calendar.getInstance().apply { timeInMillis = cal.timeInMillis }
        val view_ = java.util.Calendar.getInstance().apply {
            timeInMillis = selected.timeInMillis
            set(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        etHour.setText(selected.get(java.util.Calendar.HOUR_OF_DAY).toString())
        etMinute.setText(selected.get(java.util.Calendar.MINUTE).toString())

        val monthNames = resources.getStringArray(R.array.month_names_full)
        val dowNames = listOf("П", "В", "С", "Ч", "П", "С", "В")
        dowRow.removeAllViews()
        dowNames.forEach { d ->
            dowRow.addView(TextView(this).apply {
                text = d
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        fun updateSummary() {
            val h = etHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 0
            val m = etMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
            selected.set(java.util.Calendar.HOUR_OF_DAY, h)
            selected.set(java.util.Calendar.MINUTE, m)
            selected.set(java.util.Calendar.SECOND, 0)
            selected.set(java.util.Calendar.MILLISECOND, 0)
            tvSummary.text = getString(R.string.mute_dialog_summary_fmt, formatMuteUntil(selected.timeInMillis))
        }

        fun renderCalendar() {
            tvMonth.text = monthNames.getOrElse(view_.get(java.util.Calendar.MONTH)) { "" }
            tvYear.text = view_.get(java.util.Calendar.YEAR).toString()
            grid.removeAllViews()

            val firstOfMonth = view_.clone() as java.util.Calendar
            firstOfMonth.set(java.util.Calendar.DAY_OF_MONTH, 1)
            // Понедельник = 0 ... Воскресенье = 6
            val rawDow = firstOfMonth.get(java.util.Calendar.DAY_OF_WEEK) // Sunday=1..Saturday=7
            val startOffset = (rawDow + 5) % 7
            val daysInMonth = firstOfMonth.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

            var day = 1 - startOffset
            while (day <= daysInMonth) {
                val weekRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(2) }
                }
                for (col in 0 until 7) {
                    val thisDay = day
                    val cellTv = TextView(this).apply {
                        textSize = 11f
                        gravity = android.view.Gravity.CENTER
                        setPadding(0, dp(6), 0, dp(6))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = dp(1); it.marginEnd = dp(1) }
                        if (thisDay in 1..daysInMonth) {
                            text = thisDay.toString()
                            val isSelected = selected.get(java.util.Calendar.YEAR) == view_.get(java.util.Calendar.YEAR) &&
                                selected.get(java.util.Calendar.MONTH) == view_.get(java.util.Calendar.MONTH) &&
                                selected.get(java.util.Calendar.DAY_OF_MONTH) == thisDay
                            if (isSelected) {
                                setBackgroundResource(R.drawable.bg_chip_selected)
                                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.white))
                            } else {
                                background = null
                                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_primary))
                            }
                            setOnClickListener {
                                selected.set(java.util.Calendar.YEAR, view_.get(java.util.Calendar.YEAR))
                                selected.set(java.util.Calendar.MONTH, view_.get(java.util.Calendar.MONTH))
                                selected.set(java.util.Calendar.DAY_OF_MONTH, thisDay)
                                updateSummary()
                                renderCalendar()
                            }
                        } else {
                            text = ""
                        }
                    }
                    weekRow.addView(cellTv)
                    day++
                }
                grid.addView(weekRow)
            }
        }

        view.findViewById<ImageButton>(R.id.btn_mute_prev_month).setOnClickListener {
            view_.add(java.util.Calendar.MONTH, -1); renderCalendar()
        }
        view.findViewById<ImageButton>(R.id.btn_mute_next_month).setOnClickListener {
            view_.add(java.util.Calendar.MONTH, 1); renderCalendar()
        }
        view.findViewById<ImageButton>(R.id.btn_mute_prev_year).setOnClickListener {
            view_.add(java.util.Calendar.YEAR, -1); renderCalendar()
        }
        view.findViewById<ImageButton>(R.id.btn_mute_next_year).setOnClickListener {
            view_.add(java.util.Calendar.YEAR, 1); renderCalendar()
        }
        val timeWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateSummary() }
        }
        etHour.addTextChangedListener(timeWatcher)
        etMinute.addTextChangedListener(timeWatcher)

        val presets = listOf(
            getString(R.string.mute_preset_1h) to java.util.Calendar.HOUR_OF_DAY to 1,
            getString(R.string.mute_preset_1d) to java.util.Calendar.DAY_OF_MONTH to 1,
            getString(R.string.mute_preset_1w) to java.util.Calendar.DAY_OF_MONTH to 7,
            getString(R.string.mute_preset_1m) to java.util.Calendar.MONTH to 1,
            getString(R.string.mute_preset_1y) to java.util.Calendar.YEAR to 1
        )
        chipRow.removeAllViews()
        presets.forEach { (labelField, amount) ->
            val (label, field) = labelField
            chipRow.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setBackgroundResource(R.drawable.bg_pill)
                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(6) }
                setOnClickListener {
                    val target = java.util.Calendar.getInstance()
                    target.add(field, amount)
                    selected.timeInMillis = target.timeInMillis
                    view_.timeInMillis = target.timeInMillis
                    view_.set(java.util.Calendar.DAY_OF_MONTH, 1)
                    etHour.setText(selected.get(java.util.Calendar.HOUR_OF_DAY).toString())
                    etMinute.setText(selected.get(java.util.Calendar.MINUTE).toString())
                    updateSummary()
                    renderCalendar()
                }
            })
        }

        updateSummary()
        renderCalendar()

        // Сообщения-основание: вся история ЭТОГО участника, чекбокс на каждой строке
        // (необязательно — можно заглушить и без единого выбранного сообщения, см.
        // btn_mute_confirm ниже, он ничем не гейтится). Сами сообщения не дублируются —
        // сохраняется только msgId (см. ChatParticipant.mutedEvidenceIds), заглушённый
        // клиент найдёт их локально по этому же msgId в своей копии chat.txt.
        fun addEvidenceRow(msg: Message) {
            val row = layoutInflater.inflate(R.layout.item_mute_evidence_row, evidenceList, false)
            val ivKind = row.findViewById<ImageView>(R.id.iv_evidence_kind)
            val tvTime = row.findViewById<TextView>(R.id.tv_evidence_time)
            val tvPreview = row.findViewById<TextView>(R.id.tv_evidence_preview)
            val cb = row.findViewById<android.widget.CheckBox>(R.id.cb_evidence_selected)
            tvTime.text = StatsUtil.formatMessageTime(this, msg.timestampMs)
            when {
                msg.isVoice -> {
                    ivKind.setImageResource(R.drawable.ic_mic)
                    ivKind.visibility = View.VISIBLE
                    tvPreview.text = getString(R.string.msg_preview_voice)
                }
                msg.isImage -> {
                    ivKind.setImageResource(R.drawable.ic_image_outline)
                    ivKind.visibility = View.VISIBLE
                    tvPreview.text = msg.text.takeIf { it.isNotBlank() } ?: getString(R.string.msg_preview_photo)
                }
                else -> {
                    ivKind.visibility = View.GONE
                    tvPreview.text = msg.text
                }
            }
            row.setOnClickListener {
                if (selectedEvidenceIds.contains(msg.msgId)) {
                    selectedEvidenceIds.remove(msg.msgId)
                    cb.isChecked = false
                } else {
                    selectedEvidenceIds.add(msg.msgId)
                    cb.isChecked = true
                }
            }
            evidenceList.addView(row)
        }

        /**
         * Ветка-переписка целиком как одно основание (по запросу пользователя: "указать
         * переписку в ветке ответов пользователя с другим человеком") — [replyMsg] это
         * ответ участника, [originalMsg] — сообщение, на которое он отвечал (может быть от
         * ЛЮБОГО собеседника, не обязательно от самого участника). Один чекбокс выбирает/
         * снимает ОБА msgId сразу. originalMsg.msgId ссылается на строку в chat.txt, которая
         * и так есть у заглушённого клиента (chat.txt общий для всей группы) — рендерится
         * там как нейтральный (не жёлтый) пузырёк с подписью отправителя, см.
         * ChatActivity.addMuteEvidenceBubble.
         */
        fun addEvidenceThreadRow(replyMsg: Message, originalMsg: Message) {
            val row = layoutInflater.inflate(R.layout.item_mute_evidence_thread, evidenceList, false)
            val cb = row.findViewById<android.widget.CheckBox>(R.id.cb_evidence_thread_selected)
            val tvLabel = row.findViewById<TextView>(R.id.tv_evidence_thread_label)
            val tvOrigSender = row.findViewById<TextView>(R.id.tv_evidence_thread_orig_sender)
            val tvOrigText = row.findViewById<TextView>(R.id.tv_evidence_thread_orig_text)
            val tvReplyText = row.findViewById<TextView>(R.id.tv_evidence_thread_reply_text)

            fun preview(m: Message): String = when {
                m.isVoice -> getString(R.string.msg_preview_voice)
                m.isImage -> m.text.takeIf { it.isNotBlank() } ?: getString(R.string.msg_preview_photo)
                else -> m.text
            }

            tvLabel.text = getString(R.string.mute_evidence_thread_with_fmt, originalMsg.sender)
            tvOrigSender.text = originalMsg.sender
            tvOrigText.text = preview(originalMsg)
            tvReplyText.text = preview(replyMsg)

            fun syncChecked() {
                cb.isChecked = selectedEvidenceIds.contains(replyMsg.msgId) && selectedEvidenceIds.contains(originalMsg.msgId)
            }
            syncChecked()
            row.setOnClickListener {
                if (cb.isChecked) {
                    selectedEvidenceIds.remove(replyMsg.msgId)
                    selectedEvidenceIds.remove(originalMsg.msgId)
                } else {
                    selectedEvidenceIds.add(replyMsg.msgId)
                    selectedEvidenceIds.add(originalMsg.msgId)
                }
                syncChecked()
            }
            evidenceList.addView(row)
        }

        lifecycleScope.launch {
            try {
                val transport = TransportFactory.forChat(this@PartnerProfileActivity, chat.chatId, chat.transportToken, chat.chatPassword, prefs.myUserId)
                val all = withContext(Dispatchers.IO) { transport.loadAll() }
                val allMessages = withContext(Dispatchers.Default) {
                    StatsUtil.decodeAll(all.chatContent, chat.chatPassword, chat.chatId, prefs.myUserId, prefs.myName)
                }
                val messages = allMessages
                    .filter { it.senderUserId == member.userId }
                    .sortedByDescending { it.timestampMs }
                if (messages.isEmpty()) {
                    evidenceEmpty.text = getString(R.string.mute_dialog_evidence_empty)
                    evidenceEmpty.visibility = View.VISIBLE
                } else {
                    evidenceEmpty.visibility = View.GONE
                    // Ветка-переписка (см. addEvidenceThreadRow) — только для реплик участника,
                    // у которых нашёлся оригинал (см. StatsUtil.findQuotedOriginal); остальные
                    // сообщения — как раньше, отдельной строкой (addEvidenceRow).
                    messages.forEach { msg ->
                        val original = if (msg.isReply) StatsUtil.findQuotedOriginal(allMessages, msg) else null
                        if (original != null) addEvidenceThreadRow(msg, original) else addEvidenceRow(msg)
                    }
                }
            } catch (_: Exception) {
                evidenceEmpty.text = getString(R.string.mute_dialog_evidence_empty)
                evidenceEmpty.visibility = View.VISIBLE
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.btn_mute_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btn_mute_confirm).setOnClickListener {
            updateSummary()
            val untilMs = selected.timeInMillis
            if (untilMs <= System.currentTimeMillis()) {
                android.widget.Toast.makeText(this, R.string.mute_dialog_time_in_past, android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val reason = etReason.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
            dialog.dismiss()
            doMuteReal(member, chat, untilMs, reason, selectedEvidenceIds.toList())
        }
        dialog.show()
    }

    /** Мут — тот же принцип публикации, что и doBanReal/doUnbanReal. */
    private fun doMuteReal(member: com.atrum.chat.data.ChatParticipant, chat: com.atrum.chat.data.Chat, untilMs: Long, reason: String?, evidenceMsgIds: List<String> = emptyList()) {
        val adminUserId = chat.adminUserId ?: return
        if (!groupCanModerate) return // главный или делегат с MODERATE (Этап 2)
        lifecycleScope.launch {
            val database = com.atrum.chat.data.AppDatabase.get(this@PartnerProfileActivity)
            withContext(Dispatchers.IO) {
                database.chatParticipantDao().mute(chat.id, member.userId, untilMs, reason, MembersSync.evidenceIdsToStore(evidenceMsgIds))
                database.muteHistoryDao().insert(
                    com.atrum.chat.data.MuteHistoryEntry(
                        ownerId = chat.id,
                        userId = member.userId,
                        issuedByUserId = prefs.myUserId,
                        mutedUntilMs = untilMs,
                        reason = reason,
                        evidenceMsgIds = MembersSync.evidenceIdsToStore(evidenceMsgIds)
                    )
                )
            }
            val fresh = withContext(Dispatchers.IO) { database.chatParticipantDao().getForChat(chat.id) }
            renderGroupMembersRows(fresh, emptyMap(), chat)

            try {
                val entries = fresh.map { MembersSync.Entry(it.userId, it.banned, it.mutedUntilMs, it.mutedReason, MembersSync.evidenceIdsFromStore(it.mutedEvidenceIds), it.permissions) }
                publishMembersAsAdmin(
                    chat, database,
                    participants = entries,
                    groupName = chat.groupName,
                    groupAvatarBase64 = chat.groupAvatarBase64,
                    groupDescription = chat.groupDescription
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    this@PartnerProfileActivity, R.string.invite_create_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Presence-таймаут для online-статуса в списке участников (тот же порядок, что и в ChatActivity). */
    private val ONLINE_EXPIRY_MS_LOCAL = 20_000L

    private fun confirmBanReal(member: com.atrum.chat.data.ChatParticipant, displayName: String, chat: com.atrum.chat.data.Chat) {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.demo_ban_title, displayName),
            message = getString(R.string.demo_ban_message),
            positiveText = getString(R.string.demo_ban_confirm),
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel),
            onPositive = { doBanReal(member, chat) }
        )
    }

    private fun doBanReal(member: com.atrum.chat.data.ChatParticipant, chat: com.atrum.chat.data.Chat) {
        val adminUserId = chat.adminUserId ?: return
        if (!groupCanModerate) return // главный или делегат с MODERATE (Этап 2)
        lifecycleScope.launch {
            val database = com.atrum.chat.data.AppDatabase.get(this@PartnerProfileActivity)
            withContext(Dispatchers.IO) {
                database.chatParticipantDao().ban(chat.id, member.userId)
                // История модерации (объединена с мутами, по запросу пользователя): бан.
                database.muteHistoryDao().insert(
                    com.atrum.chat.data.MuteHistoryEntry(
                        ownerId = chat.id,
                        type = com.atrum.chat.data.MuteHistoryEntry.TYPE_BAN,
                        userId = member.userId,
                        issuedByUserId = prefs.myUserId,
                        mutedUntilMs = 0L
                    )
                )
            }
            // Перерисовываем сразу из локального кэша — оптимистично, не ждём сети (§1.5).
            val fresh = withContext(Dispatchers.IO) { database.chatParticipantDao().getForChat(chat.id) }
            renderGroupMembersRows(fresh, emptyMap(), chat)

            try {
                val entries = fresh.map { MembersSync.Entry(it.userId, it.banned, it.mutedUntilMs, it.mutedReason, MembersSync.evidenceIdsFromStore(it.mutedEvidenceIds), it.permissions) }
                publishMembersAsAdmin(
                    chat, database,
                    participants = entries,
                    groupName = chat.groupName,
                    groupAvatarBase64 = chat.groupAvatarBase64,
                    groupDescription = chat.groupDescription
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    this@PartnerProfileActivity, R.string.invite_create_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun renameGroupReal() {
        val chat = groupChatCached ?: return
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.cc_group_name_label),
            initialText = chat.groupName ?: chat.partnerName,
            positiveText = getString(R.string.btn_save),
            negativeText = getString(R.string.btn_cancel),
            onPositive = { newName ->
                val trimmed = newName.trim()
                if (trimmed.isNotEmpty()) doRenameGroupReal(chat, trimmed)
            }
        )
    }

    private fun doRenameGroupReal(chat: com.atrum.chat.data.Chat, newName: String) {
        val adminUserId = chat.adminUserId ?: return
        if (adminUserId != prefs.myUserId) return
        // Оптимистично сразу в UI — не ждём сети (§1.5 CLAUDE.md).
        findViewById<TextView>(R.id.tv_profile_name).text = newName
        lifecycleScope.launch {
            val database = com.atrum.chat.data.AppDatabase.get(this@PartnerProfileActivity)
            withContext(Dispatchers.IO) {
                database.chatDao().updateGroupProfile(chat.id, newName, chat.groupAvatarBase64, chat.groupDescription)
            }
            groupChatCached = chat.copy(groupName = newName)
            try {
                val participants = withContext(Dispatchers.IO) { database.chatParticipantDao().getForChat(chat.id) }
                    .map { MembersSync.Entry(it.userId, it.banned, it.mutedUntilMs, it.mutedReason, MembersSync.evidenceIdsFromStore(it.mutedEvidenceIds), it.permissions) }
                publishMembersAsAdmin(
                    chat, database,
                    participants = participants,
                    groupName = newName,
                    groupAvatarBase64 = chat.groupAvatarBase64,
                    groupDescription = chat.groupDescription
                )
            } catch (_: Exception) {
                android.widget.Toast.makeText(
                    this@PartnerProfileActivity, R.string.invite_create_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ── Аватар группы (реальный) — системный пикер + UCrop, публикует через members.txt ──

    private val pickRealGroupAvatar = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) startRealGroupAvatarCrop(uri) }

    private val cropRealGroupAvatar = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = UCrop.getOutput(result.data!!)
            if (uri != null) applyRealGroupAvatarUri(uri)
        } else if (result.resultCode == UCrop.RESULT_ERROR && result.data != null) {
            val err = UCrop.getError(result.data!!)
            android.widget.Toast.makeText(this, getString(R.string.error_avatar_load) + ": ${err?.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRealGroupAvatarCrop(sourceUri: Uri) {
        val destUri = Uri.fromFile(File(cacheDir, "real_group_avatar_crop_${System.currentTimeMillis()}.jpg"))
        val options = UCrop.Options().apply {
            setCircleDimmedLayer(true)
            setShowCropFrame(false)
            setShowCropGrid(false)
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setToolbarTitle(getString(R.string.crop_avatar_title))
            setHideBottomControls(true)
            setFreeStyleCropEnabled(false)
        }
        cropRealGroupAvatar.launch(
            UCrop.of(sourceUri, destUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(1024, 1024)
                .withOptions(options)
                .getIntent(this)
        )
    }

    private fun applyRealGroupAvatarUri(uri: Uri) {
        val chat = groupChatCached ?: return
        val adminUserId = chat.adminUserId ?: return
        if (adminUserId != prefs.myUserId) return
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = AvatarUtils.loadAndResize(this@PartnerProfileActivity, uri)
            if (bmp == null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@PartnerProfileActivity, R.string.error_avatar_load, android.widget.Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            // Бюджет авы группы (см. AvatarUtils.boundedGroupAvatarBase64): тяжёлая ава
            // раздувала members.txt за порог чанкования и убивала весь синк членства.
            val newAvatarB64 = AvatarUtils.boundedGroupAvatarBase64(AvatarUtils.toBase64(bmp)) ?: AvatarUtils.toBase64(bmp)
            withContext(Dispatchers.Main) {
                groupAvatarPendingBitmap = bmp
                findViewById<ShapeableImageView>(R.id.iv_profile_avatar).apply {
                    setImageBitmap(bmp)
                    visibility = View.VISIBLE
                }
                findViewById<TextView>(R.id.tv_avatar_initial).visibility = View.GONE
            }
            val database = com.atrum.chat.data.AppDatabase.get(this@PartnerProfileActivity)
            database.chatDao().updateGroupProfile(chat.id, chat.groupName, newAvatarB64, chat.groupDescription)
            groupChatCached = chat.copy(groupAvatarBase64 = newAvatarB64)
            try {
                val participants = database.chatParticipantDao().getForChat(chat.id)
                    .map { MembersSync.Entry(it.userId, it.banned, it.mutedUntilMs, it.mutedReason, MembersSync.evidenceIdsFromStore(it.mutedEvidenceIds), it.permissions) }
                publishMembersAsAdmin(
                    chat, database,
                    participants = participants,
                    groupName = chat.groupName,
                    groupAvatarBase64 = newAvatarB64,
                    groupDescription = chat.groupDescription
                )
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@PartnerProfileActivity, R.string.invite_create_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Демо-медиа (фото/голосовые/ссылки) — статичные заглушки, БЕЗ сети ──────

    private fun buildDemoPhotos() {
        findViewById<View>(R.id.section_photos).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_photos_count).text = "24"
        findViewById<View>(R.id.btn_photos_all).visibility = View.GONE

        val row = findViewById<LinearLayout>(R.id.ll_photo_grid_row1)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        val colors = intArrayOf(0xFF3A5A78.toInt(), 0xFF5A4A3D.toInt(), 0xFF6B4A5C.toInt())
        colors.forEachIndexed { i, color ->
            val cell = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8f * density
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(0, (100 * density).toInt(), 1f).also {
                    if (i > 0) it.marginStart = (4 * density).toInt()
                }
            }
            row.addView(cell)
        }
    }

    private fun buildDemoVoice() {
        findViewById<View>(R.id.section_voice).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_voice_count).text = "6"
        findViewById<View>(R.id.btn_voice_all).visibility = View.GONE

        val container = findViewById<LinearLayout>(R.id.ll_voice_container)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        listOf("0:14" to 35, "0:41" to 0).forEach { (dur, pct) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val playIcon = ImageView(this).apply {
                setImageResource(R.drawable.ic_play)
                setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.accent_light))
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(10) }
            }
            val trackWrap = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(2), 1f)
            }
            val track = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.border))
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT, dp(2)
                )
            }
            val progress = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.accent))
                }
                layoutParams = android.widget.FrameLayout.LayoutParams(0, dp(2))
            }
            trackWrap.addView(track)
            trackWrap.addView(progress)
            trackWrap.post {
                val w = trackWrap.width * pct / 100
                progress.layoutParams = progress.layoutParams.apply { width = w }
                progress.requestLayout()
            }
            val durTv = TextView(this).apply {
                text = dur
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_tertiary))
                setPadding(dp(10), 0, 0, 0)
            }
            row.addView(playIcon); row.addView(trackWrap); row.addView(durTv)
            container.addView(row)
        }
    }

    private fun buildDemoLinks() {
        findViewById<View>(R.id.section_links).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_links_count).text = "1"
        findViewById<View>(R.id.btn_links_all).visibility = View.GONE

        val container = findViewById<LinearLayout>(R.id.ll_links_container)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        listOf("github.com/atrum-chat/atrum").forEach { url ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_link)
                setColorFilter(ContextCompat.getColor(this@PartnerProfileActivity, R.color.text_tertiary))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also { it.marginEnd = dp(10) }
            }
            val tv = TextView(this).apply {
                text = url
                textSize = 12.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@PartnerProfileActivity, R.color.accent_light))
            }
            row.addView(icon); row.addView(tv)
            container.addView(row)
        }
    }
}
