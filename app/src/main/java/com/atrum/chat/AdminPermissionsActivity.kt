package com.atrum.chat

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран выбора прав делегированного администратора (Этап 1 фичи «Админы»). Открывается
 * ТОЛЬКО у главного админа (Chat.adminUserId) — из раздела «Админы» в [GroupStatsActivity]:
 * либо при назначении нового человека (пикер участника), либо при тапе на уже назначенного
 * админа для правки/снятия прав.
 *
 * Логика назначения (см. CLAUDE.md §1, §14):
 *  1. Тумблеры дают битовую маску прав (см. [AdminPermissions]).
 *  2. Сохранение мгновенно пишет маску в Room (ChatParticipant.permissions) — §1.5.
 *  3. Публикация members.txt с новой маской — через [PublishScheduler.markMembersDirty]
 *     (сериализованная очередь, монотонные версии, коалесценция).
 *  4. На устройстве назначенного новая маска применится MembersSync.applyIncoming и
 *     родит уведомление о роли (notifyRoleGranted / notifyRoleRevoked, см. 1d).
 *
 * Фактическое ПРИМЕНЕНИЕ прав делегированными админами (мультиподпись транспорта) — Этап 2.
 */
class AdminPermissionsActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"           // Room id чата (Long)
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_USER_NAME = "user_name"
        const val EXTRA_PERMISSIONS = "permissions"    // текущая маска (0 — новый)
    }

    private var chatRoomId: Long = -1L
    private lateinit var userId: String
    private lateinit var networkChatId: String

    private lateinit var swEdit: SwitchCompat
    private lateinit var swModerate: SwitchCompat
    private lateinit var swDelete: SwitchCompat
    private lateinit var swStats: SwitchCompat
    private lateinit var swPin: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_permissions)

        chatRoomId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        userId = intent.getStringExtra(EXTRA_USER_ID) ?: run { finish(); return }
        val userName = intent.getStringExtra(EXTRA_USER_NAME) ?: userId.take(8)
        val currentMask = intent.getIntExtra(EXTRA_PERMISSIONS, 0)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_subtitle).text = userName

        swEdit = findViewById(R.id.sw_edit)
        swModerate = findViewById(R.id.sw_moderate)
        swDelete = findViewById(R.id.sw_delete)
        swStats = findViewById(R.id.sw_stats)
        swPin = findViewById(R.id.sw_pin)

        swEdit.isChecked = AdminPermissions.has(currentMask, AdminPermissions.EDIT)
        swModerate.isChecked = AdminPermissions.has(currentMask, AdminPermissions.MODERATE)
        swDelete.isChecked = AdminPermissions.has(currentMask, AdminPermissions.DELETE_RESTORE)
        swStats.isChecked = AdminPermissions.has(currentMask, AdminPermissions.STATS)
        swPin.isChecked = AdminPermissions.has(currentMask, AdminPermissions.PIN)

        // Строки-ряды тоже переключают тумблер (удобнее тапать по всей строке).
        mapOf(
            R.id.row_edit to swEdit, R.id.row_moderate to swModerate,
            R.id.row_delete to swDelete, R.id.row_stats to swStats, R.id.row_pin to swPin
        ).forEach { (rowId, sw) ->
            findViewById<android.view.View>(rowId).setOnClickListener { sw.toggle() }
        }

        val isExisting = AdminPermissions.isAdmin(currentMask)
        findViewById<TextView>(R.id.btn_assign).apply {
            text = getString(if (isExisting) R.string.admin_perms_save else R.string.admin_perms_assign)
            setOnClickListener { save() }
        }
        findViewById<TextView>(R.id.btn_revoke).apply {
            visibility = if (isExisting) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener { confirmRevoke(userName) }
        }

        // Передать право владения (ADR_MESSAGE_AUTHENTICITY.md §10). Экран открыт только у
        // владельца, передаём именно этому участнику (userId).
        findViewById<android.view.View>(R.id.row_transfer_owner).setOnClickListener {
            showTransferDialog(userName)
        }
    }

    /** Окно подтверждения с минутным таймером и галочкой «остаться админом». */
    private fun showTransferDialog(recipientName: String) {
        val view = layoutInflater.inflate(R.layout.dialog_transfer_ownership, null)
        val dialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        view.findViewById<TextView>(R.id.tv_transfer_msg).text =
            getString(R.string.transfer_owner_msg, recipientName)
        val cb = view.findViewById<android.widget.CheckBox>(R.id.cb_keep_admin)
        view.findViewById<android.view.View>(R.id.row_keep_admin).setOnClickListener { cb.isChecked = !cb.isChecked }
        view.findViewById<TextView>(R.id.btn_transfer_cancel).setOnClickListener { dialog.dismiss() }

        val confirm = view.findViewById<TextView>(R.id.btn_transfer_confirm)
        confirm.isEnabled = false
        confirm.text = getString(R.string.transfer_owner_yes_timer, 60)
        val timer = object : android.os.CountDownTimer(60_000L, 1000L) {
            override fun onTick(msLeft: Long) {
                confirm.text = getString(R.string.transfer_owner_yes_timer, ((msLeft + 999L) / 1000L).toInt())
            }
            override fun onFinish() {
                confirm.text = getString(R.string.transfer_owner_yes)
                confirm.isEnabled = true
                confirm.alpha = 1f
            }
        }
        timer.start()
        confirm.setOnClickListener {
            if (!confirm.isEnabled) return@setOnClickListener
            confirm.isEnabled = false
            performTransfer(cb.isChecked, dialog)
        }
        dialog.setOnDismissListener { timer.cancel() }
        dialog.show()
    }

    /**
     * Генерирует и публикует сертификат передачи владения (OwnerSync), применяет локально.
     * Коды: 0 — успех, 2 — у получателя ещё нет identity-ключа, иначе ошибка.
     */
    private fun performTransfer(keepAdmin: Boolean, dialog: android.app.Dialog) {
        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.get(this@AdminPermissionsActivity)
                    val chat = db.chatDao().getById(chatRoomId) ?: return@runCatching 1
                    if (chat.adminUserId.isNullOrBlank() || chat.adminUserId != prefs.myUserId) return@runCatching 1
                    val password = prefs.getChatPassword(chat.chatId).ifEmpty { @Suppress("DEPRECATION") chat.chatPassword }
                    val token = prefs.getChatToken(chat.chatId).ifEmpty { @Suppress("DEPRECATION") chat.transportToken }
                    val transport = TransportFactory.forChat(
                        applicationContext, chat.chatId, token, password, prefs.myUserId, adminUserId = chat.adminUserId
                    )
                    // Ключ получателя: сперва закреплённый (TOFU). Если ещё не закреплён — берём из
                    // его ЖИВОГО профиля и закрепляем на месте (то же, что делает GroupRosterSync,
                    // но по требованию: не заставляем «ждать, пока закрепится»). Профиль с ключом
                    // появляется, как только получатель хоть раз открыл чат. Передача всё равно
                    // безопасна: принять оффер может только владелец этого ключа (подпись согласия).
                    var recipientIdk = db.chatParticipantDao().getOne(chat.id, userId)?.pinnedIdentityPubKey
                    if (recipientIdk.isNullOrBlank()) {
                        val liveIdk = runCatching {
                            ProfileSync.pullProfiles(transport, password)[userId]?.identityPubKey
                        }.getOrNull()
                        if (!liveIdk.isNullOrBlank()) {
                            db.chatParticipantDao().pinIdentityIfEmpty(chat.id, userId, liveIdk)
                            recipientIdk = db.chatParticipantDao().getOne(chat.id, userId)?.pinnedIdentityPubKey ?: liveIdk
                        }
                    }
                    if (recipientIdk.isNullOrBlank()) return@runCatching 2
                    val (priv, _) = prefs.getOrCreateIdentity()
                    val cert = try {
                        MembersSync.signOwnerTransfer(
                            priv,
                            MembersSync.OwnerTransfer(
                                chatId = chat.chatId, fromUserId = prefs.myUserId, fromIdk = prefs.myIdentityPubKey,
                                toUserId = userId, toIdk = recipientIdk, keepOldAsAdmin = keepAdmin,
                                ts = System.currentTimeMillis()
                            )
                        )
                    } finally { priv.fill(0) }
                    if (cert.sig.isBlank()) return@runCatching 1
                    // Публикуем ОФФЕР (только подпись владельца). Владение сменится ТОЛЬКО после
                    // того, как получатель примет (подпишет согласие) — двусторонняя передача,
                    // никому нельзя навязать права. Локально сейчас НИЧЕГО не меняем.
                    val existingRaw = transport.loadOwnerCerts().trim()
                    val existingPlain = if (existingRaw.isEmpty()) "" else (CryptoHelper.decrypt(existingRaw, password, chat.chatId) ?: "")
                    val newPlain = OwnerSync.appendCert(existingPlain, cert)
                    transport.saveOwnerCerts(CryptoHelper.encryptGroupMessage(newPlain, password, chat.chatId))
                    0
                }.getOrDefault(1)
            }
            when (code) {
                0 -> {
                    android.widget.Toast.makeText(this@AdminPermissionsActivity, getString(R.string.transfer_owner_sent), android.widget.Toast.LENGTH_SHORT).show()
                    runCatching { dialog.dismiss() }
                    finish()
                }
                2 -> {
                    android.widget.Toast.makeText(this@AdminPermissionsActivity, getString(R.string.transfer_owner_no_key), android.widget.Toast.LENGTH_LONG).show()
                    runCatching { dialog.dismiss() }
                }
                else -> {
                    android.widget.Toast.makeText(this@AdminPermissionsActivity, getString(R.string.transfer_owner_error), android.widget.Toast.LENGTH_SHORT).show()
                    runCatching { dialog.dismiss() }
                }
            }
        }
    }

    private fun currentMaskFromSwitches(): Int {
        var mask = 0
        if (swEdit.isChecked) mask = mask or AdminPermissions.EDIT
        if (swModerate.isChecked) mask = mask or AdminPermissions.MODERATE
        if (swDelete.isChecked) mask = mask or AdminPermissions.DELETE_RESTORE
        if (swStats.isChecked) mask = mask or AdminPermissions.STATS
        if (swPin.isChecked) mask = mask or AdminPermissions.PIN
        return mask
    }

    private fun save() {
        val mask = currentMaskFromSwitches()
        if (mask == 0) {
            // Нечего выдавать — трактуем как снятие роли (или отмену для нового).
            android.widget.Toast.makeText(this, getString(R.string.admin_perms_none_selected), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        applyMask(mask)
    }

    private fun confirmRevoke(userName: String) {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.admin_perms_revoke_title),
            message = getString(R.string.admin_perms_revoke_msg, userName),
            positiveText = getString(R.string.admin_perms_revoke),
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel),
            onPositive = { applyMask(0) }
        )
    }

    /** Пишет маску в Room и ставит публикацию members.txt в очередь. */
    private fun applyMask(mask: Int) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.get(this@AdminPermissionsActivity)
                    val chat = db.chatDao().getById(chatRoomId) ?: return@runCatching false
                    // Защита: назначать может только главный админ (экран и так открыт лишь у него).
                    if (chat.adminUserId.isNullOrBlank() || chat.adminUserId != prefs.myUserId) return@runCatching false
                    networkChatId = chat.chatId
                    db.chatParticipantDao().setPermissions(chat.id, userId, mask)
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                PublishScheduler.markMembersDirty(applicationContext, networkChatId)
                android.widget.Toast.makeText(
                    this@AdminPermissionsActivity,
                    getString(if (mask == 0) R.string.admin_perms_revoked_toast else R.string.admin_perms_saved_toast),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
            } else {
                android.widget.Toast.makeText(this@AdminPermissionsActivity, getString(R.string.admin_perms_error), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
