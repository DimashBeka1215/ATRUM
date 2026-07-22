package com.atrum.chat

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.imageview.ShapeableImageView
import java.io.File
import java.io.FileOutputStream

/**
 * Экран QR-приглашения в фирменном стиле: узорный фон, аватар над белой карточкой,
 * стилизованный QR (скруглённые точки + контурное лого), тег и код-пароль.
 *
 * Вход (extras): EXTRA_INVITE (строка ATRM…), EXTRA_PIN, EXTRA_NAME, EXTRA_AVATAR (base64|null).
 * Действия: поделиться картинкой QR, отправить текстом, перейти к сканеру.
 *
 * QR кодирует deep-link atrum://join#<invite> — открывается и сканером Atrum,
 * и штатной камерой телефона. Код-пароль в QR НЕ входит (передаётся отдельно).
 */
class InviteQrActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INVITE = "invite"
        const val EXTRA_PIN = "pin"
        const val EXTRA_NAME = "name"
        const val EXTRA_AVATAR = "avatar"
        private const val QR_PX = 760
    }

    private var invite = ""
    private var qrBitmap: Bitmap? = null

    private val scanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val raw = res.data?.getStringExtra(QrScanActivity.EXTRA_RAW)
            val scanned = InviteCodec.extractInvite(raw)
            if (scanned != null) {
                startActivity(Intent(this, JoinChatActivity::class.java).apply {
                    putExtra(JoinChatActivity.EXTRA_PREFILL, scanned)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invite_qr)

        invite = intent.getStringExtra(EXTRA_INVITE).orEmpty()
        val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
            .ifBlank { getString(R.string.invite_qr_default_name) }
        val avatar = intent.getStringExtra(EXTRA_AVATAR)

        if (invite.isBlank()) { finish(); return }

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_tag).text = name
        findViewById<TextView>(R.id.tv_pin).text = pin

        // Аватар: фото поверх плейсхолдера, иначе — буква.
        val letter = findViewById<TextView>(R.id.tv_avatar_letter)
        val ivAvatar = findViewById<ShapeableImageView>(R.id.iv_avatar)
        val bmp = AvatarUtils.fromBase64(avatar)
        if (bmp != null) {
            // Обрезаем по кругу заранее — чтобы аватар был круглым и на экране,
            // и в сохранённой картинке (offscreen-рендер не применяет обрезку view).
            ivAvatar.setImageBitmap(AvatarUtils.toCircle(bmp))
            ivAvatar.visibility = View.VISIBLE
            letter.visibility = View.GONE
        } else {
            letter.text = name.trim().firstOrNull()?.uppercase() ?: "?"
            letter.visibility = View.VISIBLE
            ivAvatar.visibility = View.GONE
        }

        // Стилизованный QR из deep-link, в центре — аватар (фото или буква на фиолетовом).
        val deepLink = InviteCodec.toDeepLink(invite)
        val centerAvatar = bmp ?: makeLetterAvatar(name)
        qrBitmap = QrGen.makeStyled(this, deepLink, QR_PX, centerBitmap = centerAvatar)
        val ivQr = findViewById<ImageView>(R.id.iv_qr)
        if (qrBitmap != null) ivQr.setImageBitmap(qrBitmap)
        else ivQr.setImageBitmap(QrGen.make(deepLink, QR_PX)) // fallback на простой QR

        findViewById<View>(R.id.btn_save_gallery).setOnClickListener { saveToGallery() }
        findViewById<View>(R.id.btn_copy_qr).setOnClickListener { copyQrImage() }
        findViewById<View>(R.id.btn_copy_text).setOnClickListener { copyInviteText() }
        findViewById<View>(R.id.btn_scan).setOnClickListener {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java).apply {
                putExtra(QrScanActivity.EXTRA_MODE, QrScanActivity.MODE_INVITE)
            })
        }

        // Обучающие подсказки экрана приглашения (разово): QR, код-пароль и все 4 кнопки.
        CoachMark.show(this, "invite_qr", listOf(
            CoachMark.Step(R.id.iv_qr, getString(R.string.coach_iq_qr_t),
                getString(R.string.coach_iq_qr_b), iconRes = R.drawable.ic_qr),
            CoachMark.Step(R.id.tv_pin, getString(R.string.coach_iq_pin_t),
                getString(R.string.coach_iq_pin_b), iconRes = R.drawable.ic_lock),
            CoachMark.Step(R.id.btn_save_gallery, getString(R.string.coach_iq_save_t),
                getString(R.string.coach_iq_save_b), circle = true, iconRes = R.drawable.ic_download),
            CoachMark.Step(R.id.btn_copy_qr, getString(R.string.coach_iq_copyqr_t),
                getString(R.string.coach_iq_copyqr_b), circle = true, iconRes = R.drawable.ic_image_outline),
            CoachMark.Step(R.id.btn_copy_text, getString(R.string.coach_iq_share_t),
                getString(R.string.coach_iq_share_b), circle = true, iconRes = R.drawable.ic_copy),
            CoachMark.Step(R.id.btn_scan, getString(R.string.coach_iq_scan_t),
                getString(R.string.coach_iq_scan_b), circle = true, iconRes = R.drawable.ic_qr)
        ))
    }

    /** Имя альбома галереи для QR-кодов Atrum. */
    private val galleryAlbum = "Atrum QR"

    /** Сохраняет QR в отдельный альбом галереи «Atrum QR» (минуя системную шторку). */
    private fun saveToGallery() {
        val bmp = renderShareImage() ?: run {
            Toast.makeText(this, R.string.invite_qr_save_failed, Toast.LENGTH_SHORT).show(); return
        }
        try {
            val name = "atrum_qr_${System.currentTimeMillis()}.png"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DCIM + "/" + galleryAlbum
                    )
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw java.io.IOException("insert failed")
                resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    ?: throw java.io.IOException("stream null")
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DCIM
                    ),
                    galleryAlbum
                ).apply { mkdirs() }
                val file = File(dir, name)
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(file.absolutePath), arrayOf("image/png"), null
                )
            }
            Toast.makeText(this, R.string.invite_qr_saved, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.invite_qr_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Кладёт картинку QR в буфер обмена — вставить в чат вручную. */
    private fun copyQrImage() {
        val bmp = renderShareImage() ?: run { copyInviteText(); return }
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "atrum_invite_qr.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newUri(contentResolver, "Atrum QR", uri))
            Toast.makeText(this, R.string.invite_qr_image_copied, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // Не получилось с картинкой — хотя бы текст в буфер.
            copyInviteText()
        }
    }

    /** Кладёт текстовое приглашение в буфер обмена. */
    private fun copyInviteText() {
        copyToClipboard(getString(R.string.invite_share_text_fmt, invite))
        Toast.makeText(this, R.string.invite_qr_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Рендерит карточку приглашения вместе с узорным фоном — ровно то, что на экране
     * (фон + аватар + QR + имя + код). Для сохранения/копирования вместо «голого» QR.
     */
    private fun renderShareImage(): Bitmap? {
        val area = findViewById<View?>(R.id.share_card_area) ?: return qrBitmap
        val w = area.width
        val h = area.height
        if (w <= 0 || h <= 0) return qrBitmap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.drawColor(androidx.core.content.ContextCompat.getColor(this, R.color.bg))
        val pattern = findViewById<View?>(R.id.bg_pattern)
        if (pattern != null && pattern.width > 0 && pattern.height > 0) {
            // Выравниваем узор по позиции карточки на экране, чтобы фон совпал с видимым.
            val pl = IntArray(2); pattern.getLocationInWindow(pl)
            val al = IntArray(2); area.getLocationInWindow(al)
            cv.save()
            cv.translate((pl[0] - al[0]).toFloat(), (pl[1] - al[1]).toFloat())
            pattern.draw(cv)
            cv.restore()
        }
        area.draw(cv)
        return out
    }

    /** Рисует аватар-заглушку (буква на фиолетовом круге) для центра QR, когда фото нет. */
    private fun makeLetterAvatar(name: String): Bitmap {
        val size = 256
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = androidx.core.content.ContextCompat.getColor(this@InviteQrActivity, R.color.accent_dark)
        }
        cv.drawCircle(size / 2f, size / 2f, size / 2f, bg)
        val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = size * 0.5f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
        val fm = tp.fontMetrics
        cv.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, tp)
        return b
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Atrum invite", text))
        } catch (_: Throwable) {}
    }
}
