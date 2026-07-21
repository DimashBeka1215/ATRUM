package com.atrum.chat

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Полноэкранное окно входящего предложения передачи владения (ADR_MESSAGE_AUTHENTICITY.md §10).
 * Появляется у ПОЛУЧАТЕЛЯ, когда пришёл подписанный оффер (двусторонняя передача). «Принять» —
 * подписываем согласие своим identity-ключом, публикуем завершённый сертификат, применяем локально,
 * показываем полосу загрузки и возвращаемся в перезагруженный чат. «Отклонить» — тихо, всё по-старому.
 */
class TransferOfferActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }
    private var chatRoomId: Long = -1L
    private var offerTs: Long = 0L

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_OFFER_TS = "offer_ts"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transfer_offer)
        chatRoomId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        offerTs = intent.getLongExtra(EXTRA_OFFER_TS, 0L)
        if (chatRoomId < 0L) { finish(); return }

        findViewById<TextView>(R.id.btn_offer_accept).setOnClickListener { accept() }
        findViewById<TextView>(R.id.btn_offer_decline).setOnClickListener { decline() }
    }

    /** Назад = отклонить (без выбора окно не закрываем «в пустоту»). */
    override fun onBackPressed() { decline() }

    private fun decline() {
        // Запоминаем ts как отклонённый (по сетевому chatId) — чтобы окно не всплывало снова
        // для того же оффера. Новый оффер (другой ts) покажется опять.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val chat = AppDatabase.get(this@TransferOfferActivity).chatDao().getById(chatRoomId)
                    if (chat != null && offerTs > 0L) prefs.setDeclinedOwnerOffer(chat.chatId, offerTs)
                }
            }
            finish()
        }
    }

    private fun accept() {
        findViewById<View>(R.id.offer_buttons).visibility = View.GONE
        findViewById<View>(R.id.offer_loading).visibility = View.VISIBLE
        val pb = findViewById<ProgressBar>(R.id.pb_accept)
        // Плавная полоса (как в «Стикерах»): анимируем 0→95, при успехе — 100.
        val anim = android.animation.ValueAnimator.ofInt(0, 95).apply {
            duration = 2400L
            addUpdateListener { pb.progress = it.animatedValue as Int }
            start()
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.get(this@TransferOfferActivity)
                    val chat = db.chatDao().getById(chatRoomId) ?: return@runCatching false
                    val password = prefs.getChatPassword(chat.chatId).ifEmpty { @Suppress("DEPRECATION") chat.chatPassword }
                    val token = prefs.getChatToken(chat.chatId).ifEmpty { @Suppress("DEPRECATION") chat.transportToken }
                    val transport = TransportFactory.forChat(
                        applicationContext, chat.chatId, token, password, prefs.myUserId, adminUserId = chat.adminUserId
                    )
                    val existingRaw = transport.loadOwnerCerts().trim()
                    val existingPlain = if (existingRaw.isEmpty()) "" else (CryptoHelper.decrypt(existingRaw, password, chat.chatId) ?: "")
                    val pinnedAdminIdk = db.chatParticipantDao().getOne(chat.id, chat.adminUserId ?: "")?.pinnedIdentityPubKey
                    val pending = OwnerSync.findPendingOfferForMe(chat, existingPlain, prefs.myUserId, prefs.myIdentityPubKey, pinnedAdminIdk)
                        ?: return@runCatching false
                    val (priv, _) = prefs.getOrCreateIdentity()
                    val completed = try { MembersSync.signOwnerAccept(priv, pending) } finally { priv.fill(0) }
                    if (completed.acceptSig.isBlank()) return@runCatching false
                    val newPlain = OwnerSync.appendCert(existingPlain, completed)
                    transport.saveOwnerCerts(CryptoHelper.encryptGroupMessage(newPlain, password, chat.chatId))
                    // Применяем локально — я становлюсь создателем сразу (§1.5).
                    OwnerSync.applyOwnerChain(chat, newPlain, db.chatDao(), db.chatParticipantDao())
                    true
                }.getOrDefault(false)
            }
            anim.cancel()
            if (ok) {
                pb.progress = 100
                // Просим открытый чат перезайти (пересоздать транспорт со СМЕНЁННЫМ adminUserId).
                ChatActivity.pendingOwnerReloadChatId = chatRoomId
                delay(280)
                finish()
            } else {
                Toast.makeText(this@TransferOfferActivity, getString(R.string.transfer_owner_error), Toast.LENGTH_SHORT).show()
                findViewById<View>(R.id.offer_loading).visibility = View.GONE
                findViewById<View>(R.id.offer_buttons).visibility = View.VISIBLE
            }
        }
    }
}
