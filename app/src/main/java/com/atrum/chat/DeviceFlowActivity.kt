package com.atrum.chat

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.atrum.chat.databinding.ActivityDeviceFlowBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI обёртка над OAuth Device Flow. Реактивно подписана на OAuthCompletionTracker:
 *
 *  - Service в фоне обновляет статус: "Создаём комнату" → "Сохраняем" → "Готово!"
 *  - Эта Activity отображает статус в реальном времени
 *  - При Success автоматически переходит в ChatActivity
 *  - При onResume (пользователь вернулся из браузера) повторно проверяет статус
 *  - При onStart подписывается на broadcast от Service (резервный канал)
 *
 * Это даёт пользователю мгновенный feedback как только он вернётся в приложение,
 * любым способом — тап notification, свайп из браузера, переключение task.
 */
class DeviceFlowActivity : SecureActivity() {

    private lateinit var binding: ActivityDeviceFlowBinding

    private var deviceCodeResp: OAuthManager.DeviceCodeResponse? = null
    private var roomName: String = ""
    private var roomPassword: String = ""
    private var durationDays: Int = -1
    private var handled = false  // защита от двойного перехода

    private val completionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleResult(
                success = intent?.getBooleanExtra(DeviceFlowService.EXTRA_SUCCESS, false) == true,
                chatId = intent?.getLongExtra(DeviceFlowService.EXTRA_CHAT_ID, -1L) ?: -1L,
                error = intent?.getStringExtra(DeviceFlowService.EXTRA_ERROR_MSG)
            )
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* результат не блокирующий */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceFlowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomName = intent.getStringExtra(EXTRA_ROOM_NAME).orEmpty()
        roomPassword = intent.getStringExtra(EXTRA_ROOM_PASSWORD).orEmpty()
        durationDays = intent.getIntExtra(CreateChatActivity.EXTRA_DURATION_DAYS, -1)
        if (roomName.isEmpty() || roomPassword.isEmpty()) {
            Toast.makeText(this, R.string.error_create_chat, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Сбрасываем tracker от старых попыток (если осталось от прошлого раза)
        OAuthCompletionTracker.consume()

        binding.btnCancel.setOnClickListener {
            stopService(Intent(this, DeviceFlowService::class.java))
            OAuthCompletionTracker.consume()
            finish()
        }
        binding.btnOpenGithub.setOnClickListener { openInBrowser() }
        binding.codeBlock.setOnClickListener { copyCode() }
        binding.btnOpenGithub.isEnabled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Подписываемся на Flow от tracker — основной канал получения статуса
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                OAuthCompletionTracker.status.collect { status ->
                    onTrackerStatus(status)
                }
            }
        }

        requestDeviceCode()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(DeviceFlowService.ACTION_DONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(completionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(completionReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(completionReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // Резервная проверка: вдруг пользователь вернулся из браузера, и Service
        // уже завершился, а broadcast не успел доставиться.
        val ready = OAuthCompletionTracker.lastSuccessChatId()
        if (ready != null && ready >= 0) {
            handleResult(success = true, chatId = ready, error = null)
        }
    }

    private fun onTrackerStatus(status: OAuthCompletionTracker.Status) {
        when (status) {
            is OAuthCompletionTracker.Status.Progress -> {
                binding.tvStatus.text = status.message
            }
            is OAuthCompletionTracker.Status.Success -> {
                binding.tvStatus.text = getString(R.string.df_done_title)
                handleResult(success = true, chatId = status.chatId, error = null)
            }
            is OAuthCompletionTracker.Status.Failure -> {
                handleResult(success = false, chatId = -1L, error = status.message)
            }
            OAuthCompletionTracker.Status.Idle -> { /* ничего */ }
        }
    }

    private fun handleResult(success: Boolean, chatId: Long, error: String?) {
        if (handled) return
        if (success && chatId < 0) return  // ждём корректного chatId

        handled = true
        if (success) {
            // Показываем "Готово!" на пол-секунды, потом открываем чат
            binding.tvStatus.text = getString(R.string.df_done_title)
            binding.tvUserCode.text = "✓"
            lifecycleScope.launch {
                delay(500)
                OAuthCompletionTracker.consume()
                val openIntent = Intent(this@DeviceFlowActivity, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(openIntent)
                finish()
            }
        } else {
            val msg = error?.takeIf { it.isNotBlank() }
                ?: getString(R.string.df_status_error, "unknown")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            OAuthCompletionTracker.consume()
            finish()
        }
    }

    private fun requestDeviceCode() {
        binding.tvUserCode.text = "…"
        binding.waitingBlock.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { OAuthManager.requestDeviceCode() }
                deviceCodeResp = resp
                binding.tvUserCode.text = resp.userCode
                binding.btnOpenGithub.isEnabled = true
                binding.waitingBlock.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.df_status_authorizing)

                DeviceFlowService.start(
                    ctx = applicationContext,
                    deviceCode = resp.deviceCode,
                    intervalSec = resp.pollIntervalSec,
                    expiresInSec = resp.expiresInSec,
                    roomName = roomName,
                    roomPassword = roomPassword,
                    durationDays = durationDays
                )
            } catch (e: Exception) {
                Toast.makeText(this@DeviceFlowActivity,
                    getString(R.string.df_status_error, e.message ?: ""),
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun openInBrowser() {
        val resp = deviceCodeResp ?: return
        copyCode()

        val accent = ContextCompat.getColor(this, R.color.accent)
        val customTabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder().setToolbarColor(accent).build()
            )
            .build()

        // НЕ запускаем в NEW_TASK — оставляем в нашей task. Тогда системный
        // swipe-back из браузера легче вернёт пользователя обратно к нам.
        val tabIntent = customTabs.intent.apply {
            data = Uri.parse(resp.verificationUriComplete)
        }
        try {
            startActivity(tabIntent, customTabs.startAnimationBundle)
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resp.verificationUriComplete)))
        }
    }

    private fun copyCode() {
        val code = deviceCodeResp?.userCode ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("device_code", code))
        Toast.makeText(this, R.string.device_flow_code_copied, Toast.LENGTH_SHORT).show()
        // Clear clipboard after 60 seconds to avoid leaking the device code
        binding.root.postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, 60_000L)
    }

    companion object {
        const val EXTRA_ROOM_NAME = "room_name"
        const val EXTRA_ROOM_PASSWORD = "room_password"
    }
}
