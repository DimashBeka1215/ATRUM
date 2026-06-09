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
import com.atrum.chat.transport.GistTransport
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PartnerProfileActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    companion object {
        const val EXTRA_NAME          = "name"
        const val EXTRA_TAG           = "tag"
        const val EXTRA_STATUS        = "status"
        const val EXTRA_AVATAR_BASE64 = "avatar_base64"
        const val EXTRA_GIST_ID       = "gist_id"
        const val EXTRA_GIST_TOKEN    = "gist_token"
        const val EXTRA_CHAT_PASSWORD = "chat_password"
        const val EXTRA_IMAGE_REFS    = "image_refs"
        const val EXTRA_IDENTITY_PUB          = "identity_pub"
        const val EXTRA_EPH_PUB               = "eph_pub"
        const val EXTRA_EPH_SIG               = "eph_sig"
        const val EXTRA_VERIFIED_PARTNER_IDK  = "verified_partner_idk"
    }

    private var shieldPulse: android.animation.Animator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_partner_profile)

        val name         = intent.getStringExtra(EXTRA_NAME) ?: ""
        val tag          = intent.getStringExtra(EXTRA_TAG)
        val status       = intent.getStringExtra(EXTRA_STATUS)
        val avatarBase64 = intent.getStringExtra(EXTRA_AVATAR_BASE64)
        val gistId       = intent.getStringExtra(EXTRA_GIST_ID) ?: ""
        val gistToken    = intent.getStringExtra(EXTRA_GIST_TOKEN) ?: ""
        val chatPassword = intent.getStringExtra(EXTRA_CHAT_PASSWORD) ?: ""
        val imageRefs    = intent.getStringArrayListExtra(EXTRA_IMAGE_REFS) ?: arrayListOf()

        // Security fingerprint
        val tvFingerprint = findViewById<TextView>(R.id.tv_security_fingerprint)
        val fingerprint = CryptoHelper.getSessionKeyFingerprint(gistId)
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
        applyIdentityBadge(gistId)

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
        val gridRow2 = findViewById<LinearLayout>(R.id.ll_photo_grid_row2)

        if (imageRefs.isEmpty()) {
            photosSection.visibility = View.GONE
        } else {
            photosSection.visibility = View.VISIBLE
            loadPhotoGrid(imageRefs, gistId, gistToken, chatPassword, gridContainer, gridRow2)
        }
    }

    private fun loadPhotoGrid(
        refs: List<String>,
        gistId: String,
        gistToken: String,
        chatPassword: String,
        row1: LinearLayout,
        row2: LinearLayout
    ) {
        val api = GistApi(token = gistToken, gistId = gistId)
        val transport = GistTransport(api)
        val loader = ImageLoader(transport, chatPassword)

        // Show up to 6 photos (3 per row)
        val display = refs.takeLast(6)

        row1.removeAllViews()
        row2.removeAllViews()

        display.forEachIndexed { index, ref ->
            val cell = layoutInflater.inflate(R.layout.item_photo_grid_cell, null) as View
            val iv = cell.findViewById<ImageView>(R.id.iv_photo_cell)
            val row = if (index < 3) row1 else row2
            row.addView(cell)

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

            cell.setOnClickListener {
                val startIndex = refs.size - display.size + index
                val intent = android.content.Intent(this, ImageViewActivity::class.java).apply {
                    putExtra(ImageViewActivity.EXTRA_REFS, ArrayList(refs))
                    putExtra(ImageViewActivity.EXTRA_START_INDEX, startIndex)
                }
                startActivity(intent)
            }
        }
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
    private fun applyIdentityBadge(gistId: String) {
        val shield = findViewById<android.widget.ImageButton>(R.id.btn_shield_status)
        val card = findViewById<View>(R.id.card_security)

        val idk = intent.getStringExtra(EXTRA_IDENTITY_PUB)
        val eph = intent.getStringExtra(EXTRA_EPH_PUB)
        val esig = intent.getStringExtra(EXTRA_EPH_SIG)
        val vpk = intent.getStringExtra(EXTRA_VERIFIED_PARTNER_IDK)

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
                gistId.toByteArray(Charsets.UTF_8)
            CryptoHelper.verifyIdentitySignature(idk, data, esig)
        } catch (_: Exception) { false }

        val myIdk = prefs.myIdentityPubKey
        val confirmed = prefs.getConfirmedPartnerIdentity(gistId) == idk
        val partnerConfirmedMe = vpk != null && vpk == myIdk
        val knownIdk = prefs.getKnownPartnerIdentity(gistId)
        val keyChanged = knownIdk != null && knownIdk != idk

        // TOFU: запоминаем identity-ключ партнёра при первом появлении.
        if (knownIdk == null) prefs.setKnownPartnerIdentity(gistId, idk)

        val badge     = findViewById<ImageView>(R.id.iv_security_badge)
        val tvBadge   = findViewById<TextView>(R.id.tv_security_badge)
        val tvHint    = findViewById<TextView>(R.id.tv_verify_hint)
        val btnVerify = findViewById<android.widget.Button>(R.id.btn_verify)

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

        // Щит раскрывает/прячет секцию безопасности.
        shield.setOnClickListener {
            card.visibility = if (card.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Кнопка ручного подтверждения подлинности (сверка SAS/QR лично).
        btnVerify.text = if (confirmed) getString(R.string.verify_cancel_btn)
                         else getString(R.string.verify_confirm_btn)
        btnVerify.setOnClickListener {
            if (prefs.getConfirmedPartnerIdentity(gistId) == idk) {
                prefs.clearConfirmedPartnerIdentity(gistId)
            } else {
                prefs.setConfirmedPartnerIdentity(gistId, idk)
            }
            applyIdentityBadge(gistId)
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
}
