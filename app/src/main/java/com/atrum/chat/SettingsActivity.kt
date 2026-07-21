package com.atrum.chat

import com.atrum.chat.transport.TransportFactory
import com.atrum.chat.stickers.StickerSettingsActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import androidx.core.content.ContextCompat

class SettingsActivity : SecureActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    // Разрешение на доступ к фото для НАШЕЙ галереи (GalleryPicker). Системный
    // ACTION_GET_CONTENT больше не используется — см. pickAvatar()/MediaPick.
    private val avatarPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) MediaPick.pickOne(this, lifecycleScope) { startCrop(it) }
        else Toast.makeText(this, R.string.gallery_perm_needed, Toast.LENGTH_SHORT).show()
    }

    // Результат НАШЕГО кадратора (AvatarCropActivity) — Uri вырезанного квадрата.
    private val cropImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = result.data!!.getStringExtra(AvatarCropActivity.EXTRA_OUTPUT_URI)?.let { Uri.parse(it) }
            if (uri != null) loadAvatarFromUri(uri)
        }
    }

    /** Наша галерея (с проверкой доступа к фото) для выбора аватара → кадратор. */
    private fun pickAvatar() {
        if (MediaPick.hasAccess(this)) MediaPick.pickOne(this, lifecycleScope) { startCrop(it) }
        else avatarPermLauncher.launch(MediaPick.perms())
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

        binding.itemNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }

        /* CLAUDE_HINT: Mods section entry point. To restore, uncomment this block.
        binding.itemStickers.setOnClickListener {
            startActivity(Intent(this, StickerSettingsActivity::class.java))
        }
        binding.itemMods.setOnClickListener {
            startActivity(Intent(this, ModsActivity::class.java))
        }
        */
        binding.itemStickers.setOnClickListener {
            startActivity(Intent(this, StickerSettingsActivity::class.java))
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
        binding.itemTester.setOnClickListener { showTesterPasswordDialog() }
        binding.btnLogout.setOnClickListener { confirmLogout() }

        // Реле: скрытый вход в издателя — 7 нажатий подряд по строке, затем запрос пароля.
        binding.itemRelays.setOnClickListener { onRelayTap() }
        binding.itemConnection.setOnClickListener {
            startActivity(Intent(this, ConnectionActivity::class.java))
        }

        setupVersionRow()
        setupParallax()
    }

    override fun onResume() {
        super.onResume()
        // Обновляем баннер и профиль при возвращении (например, из HeaderSettingsActivity)
        setupBanner()
        setupProfile()
        setupBiometric()
        refreshRelaysSection()
    }

    private fun refreshRelaysSection() {
        val hidden = prefs.relaySectionHidden
        binding.sectionNetworkTitle.visibility = if (hidden) View.GONE else View.VISIBLE
        binding.sectionNetworkCard.visibility = if (hidden) View.GONE else View.VISIBLE
        if (hidden) return
        val builtin = com.atrum.chat.transport.NostrTransport.RELAYS.size
        val extra = RelayListStore.extraRelays(this).size
        val ver = RelayListStore.currentVersion(this)
        binding.tvRelaysSub.text = if (ver < 0 || extra == 0)
            getString(R.string.relays_sub_builtin, builtin)
        else getString(R.string.relays_sub_full, builtin, extra, ver)
        binding.itemPublisher.visibility = View.GONE
    }

    // ─── Скрытый вход в издателя: 7 нажатий → пароль ───────────────────────────
    private var relayTaps = 0
    private var lastRelayTapMs = 0L

    private fun onRelayTap() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRelayTapMs > 1500) relayTaps = 0
        lastRelayTapMs = now
        relayTaps++
        if (relayTaps >= 7) { relayTaps = 0; startActivity(Intent(this, PublisherGateActivity::class.java)) }
    }



    /**
     * Настройка строки «Вход по отпечатку».
     * Строка показывается ТОЛЬКО если в телефоне есть физический биометрический
     * сканер. Сама биометрия берётся из системы (на Samsung — Knox/TEE),
     * приложение её не хранит и не регистрирует.
     */
    private fun setupBiometric() {
        // Раздел скрыт, если: удалён навсегда, временно скрыт (возврат жестом в «О приложении»),
        // или в телефоне нет биометрического сканера.
        if (prefs.biometricRemoved || prefs.biometricHidden || !BiometricHelper.hasHardware(this)) {
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
        // Первое включение — спрашиваем выбор автора (использовать или убрать совсем).
        if (!prefs.biometricChoiceMade) {
            showBiometricFirstChoice()
            return
        }
        enableBiometricFlow()
    }

    /** Обычное включение отпечатка после того, как выбор уже сделан. */
    private fun enableBiometricFlow() {
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

    /**
     * Диалог при ПЕРВОМ включении входа по отпечатку.
     * «Буду пользоваться» → включаем как обычно (больше не спрашиваем).
     * «Убрать функцию» → удаляем функционал НАВСЕГДА (раздел исчезает, разблокировка по
     * отпечатку отключается, вернуть нельзя). Отмена диалога — оставляем выключенным, спросим позже.
     */
    private fun showBiometricFirstChoice() {
        NeonDialog.showThreeChoice(
            ctx = this,
            title = getString(R.string.biometric_first_title),
            message = getString(R.string.biometric_first_body),
            iconRes = R.drawable.ic_fingerprint,
            primaryText = getString(R.string.biometric_first_keep),
            onPrimary = {
                prefs.biometricChoiceMade = true
                enableBiometricFlow()
            },
            neutralText = getString(R.string.biometric_first_hide),
            onNeutral = {
                // Мягко скрываем — вернуть можно жестом в «О приложении».
                prefs.biometricChoiceMade = true
                prefs.biometricHidden = true
                prefs.biometricEnabled = false
                setupBiometric()   // строка исчезает
            },
            destructiveText = getString(R.string.biometric_first_remove),
            onDestructive = {
                // Удаляем навсегда.
                prefs.biometricChoiceMade = true
                prefs.biometricRemoved = true
                prefs.biometricEnabled = false
                setupBiometric()
            },
            footnote = getString(R.string.biometric_hide_hint)
        )
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

        binding.flAvatarContainer.setOnClickListener {
            AppLock.beginShareGrace()
            pickAvatar()
        }
        binding.tvProfileName.setOnClickListener { showEditNameDialog() }
        binding.tvProfileTag.setOnClickListener { showEditTagDialog() }
        binding.tvProfileStatus.setOnClickListener { showEditStatusDialog() }

        // Галочка верификации рядом с ником (main-visible; неподделываемо — см. VerifiedBadge).
        // В личной сборке видна сразу (проверить фичу); в релизе — если мой ключ в списке.
        binding.verifiedBadge.setVerified(
            VerifiedBadge.isVerifiedSelf(prefs.myIdentityPubKey), animate = true
        )

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

    private fun updateAllAvatarViews(bitmap: Bitmap) {
        binding.ivAvatar.setImageBitmap(bitmap)
        binding.ivAvatar.visibility = View.VISIBLE
        binding.flAvatarInitial.visibility = View.GONE

        binding.ivTopBarAvatar.setImageBitmap(bitmap)
        binding.ivTopBarAvatar.visibility = View.VISIBLE
        binding.flTopBarAvatarInitial.visibility = View.GONE
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
        
        val avatar = AvatarUtils.fromBase64(prefs.myAvatarBase64)
        updateTopBar(prefs.myName, avatar)

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
                    val token = settingsPrefs.getChatToken(chat.chatId)
                        .takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.transportToken
                    val password = settingsPrefs.getChatPassword(chat.chatId)
                        .takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.chatPassword
                    val api = TransportFactory.forChat(applicationContext, chat.chatId, token, password, settingsPrefs.myUserId)
                    // Личность обязательно кладём в КАЖДЫЙ пуш профиля. pushMyProfile делает
                    // ПОЛНУЮ замену моего слота, поэтому без idk/isig смена имени/аватара из
                    // настроек ронял бы мою верификацию (щит мигал бы у собеседников до
                    // следующего пуша из ChatActivity), а теперь ещё и подпись содержимого
                    // (contentSig, п.4 ADR). Домен identitySig = chat.chatId (тот же, что у
                    // ChatActivity.computeIdentitySig / VerifiedBadge). Приватник получаем один
                    // раз, используем для isig, затем передаём в pushMyProfile — там он затрётся.
                    val priv = settingsPrefs.getOrCreateIdentity().first
                    val isig = try {
                        CryptoHelper.signWithIdentity(priv, VerifiedBadge.identitySigData(chat.chatId))
                    } catch (_: Exception) { null }
                    val withIdentity = profile.copy(
                        identityPubKey = settingsPrefs.myIdentityPubKey,
                        identitySig = isig
                    )
                    ProfileSync.pushMyProfile(api, password, withIdentity, priv)
                } catch (_: Exception) {}
            }
        }
    }

    private fun startCrop(sourceUri: Uri) {
        // Наш нативный кадратор (1:1, круг) вместо системного uCrop.
        cropImage.launch(
            Intent(this, AvatarCropActivity::class.java)
                .putExtra(AvatarCropActivity.EXTRA_SOURCE_URI, sourceUri.toString())
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
            updateAllAvatarViews(bitmap)
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
        AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
            .setPositiveButton(R.string.logout_confirm) { _, _ -> performLogout() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showTesterPasswordDialog() {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.tester_pwd_dialog_title),
            subtitle = getString(R.string.tester_pwd_dialog_desc),
            initialText = "",
            positiveText = getString(R.string.btn_next),
            negativeText = getString(R.string.btn_cancel),
            isPassword = true
        ) { pwd ->
            if (prefs.checkTesterPassword(pwd)) {
                startActivity(Intent(this, TesterSettingsActivity::class.java))
            } else {
                Toast.makeText(this, R.string.tester_pwd_wrong, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performLogout() {
        val myUserId = prefs.myUserId
        val appContext = applicationContext

        val logoutPrefs = prefs
        AppScope.launch {
            val db = AppDatabase.get(appContext)
            for (chat in db.chatDao().getAll()) {
                try {
                    val token = logoutPrefs.getChatToken(chat.chatId)
                        .takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.transportToken
                    val password = logoutPrefs.getChatPassword(chat.chatId)
                        .takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.chatPassword
                    val api = TransportFactory.forChat(applicationContext, chat.chatId, token, password, myUserId)
                    ProfileSync.pushDeletedMarker(api, password, myUserId)
                } catch (_: Exception) {}
                logoutPrefs.deleteChatSecrets(chat.chatId)
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
    /**
     * Плавное появление баннера (фото профиля в шапке) — fade-in за 300мс.
     * Утрачен при рефакторинге v2.6.5 (вызов в setupBanner остался), восстановлен из истории.
     */
    private fun animateBannerIn() {
        binding.ivBanner.alpha = 0f
        binding.vBannerDimOverlay.alpha = 0f
        binding.vBannerGradient.alpha = 0f

        binding.ivBanner.animate().alpha(1f).setDuration(300).start()
        binding.vBannerDimOverlay.animate().alpha(1f).setDuration(300).start()
        binding.vBannerGradient.animate().alpha(1f).setDuration(300).start()
    }

    private fun applyHeroGradient() {
        val bgColor = ContextCompat.getColor(this, R.color.bg)
        val rgb = bgColor and 0x00FFFFFF
        
        val a0   = Color.TRANSPARENT
        val a15  = (15  shl 24) or rgb
        val a60  = (60  shl 24) or rgb
        val a130 = (130 shl 24) or rgb
        val a210 = (210 shl 24) or rgb

        // 9 stops for maximum smoothness: прозрачный → сплошной фон страницы
        val colors = intArrayOf(a0, a0, a15, a60, a130, a210, bgColor, bgColor, bgColor)
        val gradient = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            colors
        )
        binding.vBannerGradient.background = gradient
    }
}
