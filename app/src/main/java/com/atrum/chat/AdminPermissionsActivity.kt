package com.atrum.chat

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
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
