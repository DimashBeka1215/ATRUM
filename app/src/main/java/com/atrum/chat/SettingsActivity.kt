package com.atrum.chat

import com.atrum.chat.transport.GistTransport
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.databinding.ActivitySettingsBinding
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import androidx.core.content.ContextCompat

class SettingsActivity : SecureActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) startCrop(uri) }

    private val cropImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = UCrop.getOutput(result.data!!)
            if (uri != null) loadAvatarFromUri(uri)
        } else if (result.resultCode == UCrop.RESULT_ERROR && result.data != null) {
            val err = UCrop.getError(result.data!!)
            Toast.makeText(this,
                getString(R.string.error_avatar_load) + ": ${err?.message}",
                Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        setupProfile()
        setupBanner()

        binding.flBannerSection.setOnClickListener {
            startActivity(Intent(this, HeaderSettingsActivity::class.java))
        }

        binding.itemPersonalization.setOnClickListener {
            startActivity(Intent(this, PersonalizationActivity::class.java))
        }

        binding.itemChangePin.setOnClickListener {
            startActivity(Intent(this, ChangePinActivity::class.java))
        }

        setupBiometric()
        binding.itemBiometric.setOnClickListener { toggleBiometric() }
        binding.btnBiometricEnroll.setOnClickListener {
            try {
                startActivity(BiometricHelper.enrollIntent())
            } catch (_: Exception) {
                Toast.makeText(this, R.string.biometric_open_settings, Toast.LENGTH_SHORT).show()
            }
        }

        binding.itemVersion.setOnClickListener {
            UpdateActivity.startForCheck(this, forceRefresh = true)
        }
        binding.itemAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.itemCredits.setOnClickListener {
            startActivity(Intent(this, CreditsActivity::class.java))
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }

        setupVersionRow()
        setupParallax()
    }

    override fun onResume() {
        super.onResume()
        // Обновляем баннер и профиль при возвращении (например, из HeaderSettingsActivity)
        setupBanner()
        setupProfile()
        setupBiometric()
    }

    /**
     * Настройка строки «Вход по отпечатку».
     * Строка показывается ТОЛЬКО если в телефоне есть физический биометрический
     * сканер. Сама биометрия берётся из системы (на Samsung — Knox/TEE),
     * приложение её не хранит и не регистрирует.
     */
    private fun setupBiometric() {
        if (!BiometricHelper.hasHardware(this)) {
            binding.itemBiometric.visibility = View.GONE
            binding.warnBiometric.visibility = View.GONE
            return
        }
        binding.itemBiometric.visibility = View.VISIBLE
        // Если пользователь удалил отпечаток из телефона — сбрасываем флаг.
        if (prefs.biometricEnabled && !BiometricHelper.canUse(this)) {
            prefs.biometricEnabled = false
        }
        binding.switchBiometric.isChecked = prefs.biometricEnabled
        // Отпечаток снова доступен — прячем жёлтое предупреждение.
        if (BiometricHelper.canUse(this)) {
            binding.warnBiometric.visibility = View.GONE
        }
    }

    private fun toggleBiometric() {
        if (binding.switchBiometric.isChecked) {
            // Выключаем вход по отпечатку.
            prefs.biometricEnabled = false
            binding.switchBiometric.isChecked = false
            binding.warnBiometric.visibility = View.GONE
            return
        }
        // Включаем. Отпечаток — альтернатива PIN, поэтому PIN обязателен.
        if (!prefs.hasLocalPassword()) {
            Toast.makeText(this, R.string.biometric_need_pin, Toast.LENGTH_SHORT).show()
            return
        }
        when {
            BiometricHelper.canUse(this) -> {
                prefs.biometricEnabled = true
                binding.switchBiometric.isChecked = true
                binding.warnBiometric.visibility = View.GONE
            }
            BiometricHelper.isNoneEnrolled(this) -> {
                // Сканер есть, но отпечаток не добавлен — жёлтое предупреждение.
                binding.warnBiometric.visibility = View.VISIBLE
            }
            else -> {
                Toast.makeText(this, R.string.biometric_warn_title, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupParallax() {
        val density = resources.displayMetrics.density
        val heroPx = (170 * density).toInt()
        val collapseStart = (heroPx * 0.28f).toInt()
        val collapseEnd   = heroPx + (50 * density).toInt()

        // Pivot для scale: верхний центр — аватар уезжает вверх, не вниз
        binding.llProfile.post {
            binding.llProfile.pivotX = binding.llProfile.width / 2f
            binding.llProfile.pivotY = 0f
        }

        binding.nestedScrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // Параллакс баннера (фото двигается медленнее скролла)
            val parallax = scrollY * 0.25f
            binding.ivBanner.translationY       = parallax
            binding.vBannerDimOverlay.translationY = parallax
            binding.vBannerGradient.translationY   = parallax

            // Прогресс коллапса [0..1] с ease in-out
            val raw = ((scrollY - collapseStart).toFloat() / (collapseEnd - collapseStart)).coerceIn(0f, 1f)
            val te  = if (raw < 0.5f) 2f * raw * raw else -1f + (4f - 2f * raw) * raw

            // Профиль уезжает вверх и уменьшается
            binding.llProfile.translationY = -te * heroPx * 0.82f
            val scale = 1f - te * 0.63f
            binding.llProfile.scaleX  = scale
            binding.llProfile.scaleY  = scale
            binding.llProfile.alpha   = 1f - te * 0.5f

            // Топбар появляется
            binding.llTopBar.alpha = ((te - 0.45f) / 0.40f).coerceIn(0f, 1f)
        }
    }

    // ── Строка версии + проверка обновлений ──────────────────────────────────

    /** Последний известный релиз — сохраняем чтобы показать диалог по клику. */
    private var latestRelease: ForceUpdateChecker.ReleaseInfo? = null

    private fun setupVersionRow() {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

        binding.tvVersionSub.text = currentVersion

        if (!ForceUpdateChecker.RELEASES_ENABLED) {
            // Фича выключена — просто показываем версию, без бейджа
            return
        }

        // Показываем «Проверяется…» пока идёт запрос
        binding.tvVersionBadge.text = getString(R.string.settings_version_checking)
        binding.tvVersionBadge.setBackgroundResource(R.drawable.bg_version_checking)
        binding.tvVersionBadge.setTextColor(
            ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary)
        )
        binding.tvVersionBadge.visibility = View.VISIBLE

        lifecycleScope.launch {
            val release = ForceUpdateChecker.checkLatestRelease(this@SettingsActivity)
            latestRelease = release
            if (release != null) {
                binding.tvVersionSub.text = "$currentVersion → ${release.tagName}"
                binding.tvVersionBadge.text = getString(R.string.settings_version_update_badge)
                // Стиль «Обновить»: фиолетовый фон + белый текст — хорошо в обеих темах
                binding.tvVersionBadge.setBackgroundResource(R.drawable.bg_chip_selected)
                binding.tvVersionBadge.setTextColor(
                    ContextCompat.getColor(this@SettingsActivity, R.color.accent)
                )
            } else {
                binding.tvVersionBadge.text = getString(R.string.settings_version_up_to_date)
                // Стиль «Актуальная»: зелёный — используем online-цвет
                binding.tvVersionBadge.setBackgroundResource(R.drawable.bg_version_up_to_date)
                binding.tvVersionBadge.setTextColor(
                    ContextCompat.getColor(this@SettingsActivity, R.color.online)
                )
            }
        }
    }

    private fun setupProfile() {
        val name = prefs.myName
        val tag = prefs.myTag
        binding.tvProfileName.text = name.ifBlank { getString(R.string.no_name) }
        binding.tvProfileTag.text = tag.ifBlank { getString(R.string.settings_tag_label) }
        binding.tvAvatarInitial.text = name.trim().firstOrNull()?.uppercase() ?: "?"

        val avatar = AvatarUtils.fromBase64(prefs.myAvatarBase64)
        if (avatar != null) {
            binding.ivAvatar.setImageBitmap(avatar)
            binding.ivAvatar.visibility = View.VISIBLE
            binding.flAvatarInitial.visibility = View.GONE
        } else {
            binding.ivAvatar.visibility = View.GONE
            binding.flAvatarInitial.visibility = View.VISIBLE
        }

        val status = prefs.myStatus
        binding.tvProfileStatus.text = status.ifBlank { null }

        binding.flAvatarContainer.setOnClickListener { pickImage.launch("image/*") }
        binding.tvProfileName.setOnClickListener { showEditNameDialog() }
        binding.tvProfileTag.setOnClickListener { showEditTagDialog() }
        binding.tvProfileStatus.setOnClickListener { showEditStatusDialog() }

        updateTopBar(name, avatar)
    }

    private fun updateTopBar(name: String, avatar: android.graphics.Bitmap?) {
        binding.tvTopBarName.text = name.ifBlank { getString(R.string.no_name) }
        binding.tvTopBarAvatarInitial.text = name.trim().firstOrNull()?.uppercase() ?: "?"
        if (avatar != null) {
            binding.ivTopBarAvatar.setImageBitmap(avatar)
            binding.ivTopBarAvatar.visibility = View.VISIBLE
            binding.flTopBarAvatarInitial.visibility = View.GONE
        } else {
            binding.ivTopBarAvatar.visibility = View.GONE
            binding.flTopBarAvatarInitial.visibility = View.VISIBLE
        }
    }

    private fun showEditNameDialog() {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.settings_edit_name_title),
            initialText = prefs.myName,
            positiveText = getString(R.string.btn_save),
            negativeText = getString(R.string.btn_cancel)
        ) { newName ->
            if (newName.isNotBlank()) {
                saveNameNow(newName)
            }
        }
    }

    private fun showEditTagDialog() {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.settings_edit_tag_title),
            initialText = prefs.myTag,
            positiveText = getString(R.string.btn_save),
            negativeText = getString(R.string.btn_cancel)
        ) { newTag ->
            if (newTag.isNotBlank()) {
                saveTagNow(newTag)
            }
        }
    }

    private fun showEditStatusDialog() {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.settings_edit_status_title),
            initialText = prefs.myStatus,
            positiveText = getString(R.string.btn_save),
            negativeText = getString(R.string.btn_cancel)
        ) { newStatus ->
            saveStatusNow(newStatus)
        }
    }

    private fun saveStatusNow(newStatus: String) {
        val trimmed = newStatus.trim()
        if (prefs.myStatus == trimmed) return

        prefs.myStatus = trimmed
        binding.tvProfileStatus.text = trimmed.ifBlank { null }

        val myProfile = Profile(
            userId = prefs.myUserId,
            name = prefs.myName,
            tag = prefs.myTag,
            avatarBase64 = prefs.myAvatarBase64,
            status = trimmed
        )

        broadcastProfileUpdate(myProfile)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun saveNameNow(newName: String) {
        val oldName = prefs.myName
        if (oldName == newName) return

        prefs.rememberPreviousName(oldName)
        prefs.myName = newName
        
        setupProfile()
        
        val myProfile = Profile(
            userId = prefs.myUserId,
            name = newName,
            tag = prefs.myTag,
            avatarBase64 = prefs.myAvatarBase64,
            status = prefs.myStatus
        )
        
        broadcastProfileUpdate(myProfile)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun saveTagNow(newTag: String) {
        // Пробелы → подчёркивания, лишние пробелы по краям убираем
        val cleaned = newTag.trim().replace(" ", "_")

        // @ обязателен в начале — без него тег не принимается
        if (!cleaned.startsWith("@")) {
            Toast.makeText(this, getString(R.string.settings_tag_no_at), Toast.LENGTH_SHORT).show()
            return
        }

        val formattedTag = cleaned
        if (prefs.myTag == formattedTag) return

        prefs.myTag = formattedTag
        
        setupProfile()
        
        val myProfile = Profile(
            userId = prefs.myUserId,
            name = prefs.myName,
            tag = formattedTag,
            avatarBase64 = prefs.myAvatarBase64,
            status = prefs.myStatus
        )
        
        broadcastProfileUpdate(myProfile)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun broadcastProfileUpdate(profile: Profile) {
        val appContext = applicationContext
        val settingsPrefs = prefs
        AppScope.launch {
            val db = AppDatabase.get(appContext)
            for (chat in db.chatDao().getAll()) {
                try {
                    val token = settingsPrefs.getChatToken(chat.gistId)
                        .takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.gistToken
                    val password = settingsPrefs.getChatPassword(chat.gistId)
                        .takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.chatPassword
                    val api = GistTransport(GistApi(token = token, gistId = chat.gistId))
                    ProfileSync.pushMyProfile(api, password, profile)
                } catch (_: Exception) {}
            }
        }
    }

    private fun startCrop(sourceUri: Uri) {
        val destUri = Uri.fromFile(File(cacheDir, "avatar_crop_${System.currentTimeMillis()}.jpg"))
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
        cropImage.launch(
            UCrop.of(sourceUri, destUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(1024, 1024)
                .withOptions(options)
                .getIntent(this)
        )
    }

    private fun loadAvatarFromUri(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                AvatarUtils.loadAndResize(this@SettingsActivity, uri)
            } ?: run {
                Toast.makeText(this@SettingsActivity, R.string.error_avatar_load, Toast.LENGTH_SHORT).show()
                return@launch
            }
            binding.ivAvatar.setImageBitmap(bitmap)
            binding.ivAvatar.visibility = View.VISIBLE
            binding.flAvatarInitial.visibility = View.GONE
            saveAvatarNow(bitmap)
        }
    }

    private fun saveAvatarNow(bitmap: Bitmap) {
        lifecycleScope.launch {
            val base64 = withContext(Dispatchers.IO) { AvatarUtils.toBase64(bitmap) }
            prefs.myAvatarBase64 = base64
            val myProfile = Profile(
                userId = prefs.myUserId,
                name = prefs.myName,
                tag = prefs.myTag,
                avatarBase64 = base64,
                status = prefs.myStatus
            )
            broadcastProfileUpdate(myProfile)
            Toast.makeText(this@SettingsActivity, R.string.avatar_updated, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this, R.style.Theme_GithubChat_Dialog)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setPositiveButton(R.string.logout_confirm) { _, _ -> performLogout() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun performLogout() {
        val myUserId = prefs.myUserId
        val appContext = applicationContext

        val logoutPrefs = prefs
        AppScope.launch {
            val db = AppDatabase.get(appContext)
            for (chat in db.chatDao().getAll()) {
                try {
                    val token = logoutPrefs.getChatToken(chat.gistId)
                        .takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.gistToken
                    val password = logoutPrefs.getChatPassword(chat.gistId)
                        .takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.chatPassword
                    val api = GistTransport(GistApi(token = token, gistId = chat.gistId))
                    ProfileSync.pushDeletedMarker(api, password, myUserId)
                } catch (_: Exception) {}
                logoutPrefs.deleteChatSecrets(chat.gistId)
            }
            db.clearAllTables()
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.get(appContext).clearAllTables()
            }
            prefs.clear()
            startActivity(
                Intent(this@SettingsActivity, WelcomeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            )
            finish()
        }
    }

    // ── Banner ─────────────────────────────────────────────────────────────────

    private fun setupBanner() {
        val base64 = prefs.myBannerBase64
        if (base64 != null) {
            val bmp = try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }

            if (bmp != null) {
                binding.ivBanner.setImageBitmap(bmp)
                binding.ivBanner.visibility = View.VISIBLE
                binding.vBannerGradient.visibility = View.VISIBLE
                binding.vBannerDimOverlay.visibility = View.VISIBLE
                applyHeroGradient()
                animateBannerIn()
                return
            }
        }
        // No banner photo — but keep gradient to dissolve hero bg into page
        binding.ivBanner.visibility = View.GONE
        binding.vBannerDimOverlay.visibility = View.GONE
        applyHeroGradient()
        binding.vBannerGradient.visibility = View.VISIBLE
    }

    /**
     * Premium multi-stop gradient overlay to smoothly dissolve the banner into the page background.
     * Starts from the middle of the banner to ensure a natural transition without harsh edges.
     * Automatically adapts to Light/Dark themes using @color/bg.
     */
    private fun applyHeroGradient() {
        val bgColor = ContextCompat.getColor(this, R.color.bg)
        val rgb = bgColor and 0x00FFFFFF
        
        val a0   = Color.TRANSPARENT
        val a15  = (15  shl 24) or rgb
        val a60  = (60  shl 24) or rgb
        val a130 = (130 shl 24) or rgb
        val a210 = (210 shl 24) or rgb

        // 9 stops for maximum smoothness: 0% to ~45% is transparent, then aggressive but smooth fade
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(a0, a0, a0, a0, a15, a60, a130, a210, bgColor)
        )
        binding.vBannerGradient.background = gradient
    }

    /** Плавное появление баннера (и оверлея затемнения). */
    private fun animateBannerIn() {
        binding.ivBanner.alpha = 0f
        binding.vBannerDimOverlay.alpha = 0f
        binding.vBannerGradient.alpha = 0f
        
        binding.ivBanner.animate().alpha(1f).setDuration(300).start()
        binding.vBannerDimOverlay.animate().alpha(1f).setDuration(300).start()
        binding.vBannerGradient.animate().alpha(1f).setDuration(300).start()
    }

}
