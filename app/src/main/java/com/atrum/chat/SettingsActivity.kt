package com.atrum.chat

import com.atrum.chat.transport.GistTransport
import android.app.Activity
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

class SettingsActivity : SecureActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) startCrop(uri) }

    private val cropImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
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
        binding.btnBack.setOnClickListener { finish() }

        binding.itemPersonalization.setOnClickListener {
            startActivity(Intent(this, PersonalizationActivity::class.java))
        }
        binding.itemAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.itemCredits.setOnClickListener {
            startActivity(Intent(this, CreditsActivity::class.java))
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }

        setupVersionRow()
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
                    androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.accent)
                )
                binding.itemVersion.setOnClickListener {
                    ForceUpdateChecker.showOptionalUpdateDialog(this@SettingsActivity, release)
                }
            } else {
                binding.tvVersionBadge.text = getString(R.string.settings_version_up_to_date)
                // Стиль «Актуальная»: зелёный — используем online-цвет
                binding.tvVersionBadge.setBackgroundResource(R.drawable.bg_version_up_to_date)
                binding.tvVersionBadge.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.online)
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
}
