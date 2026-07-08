package com.atrum.chat

import com.atrum.chat.transport.BluetoothTransport
import com.atrum.chat.transport.NostrTransport

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.databinding.ActivityCreateChatBinding
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * Создание чата. Два экрана с переключением:
 *
 *   1) CHOICE — стартовый: две карточки (Создать P2P / Присоединиться)
 *   2) CREATE — форма создания P2P-чата: profile preview + features + duration chips
 *               → создание чата поверх DHT. Пароль генерируется автоматически.
 *
 * Срок жизни чата: enum [Duration] → expiresAtMs у [Chat].
 */
class CreateChatActivity : SecureActivity() {

    private lateinit var binding: ActivityCreateChatBinding
    private lateinit var db: AppDatabase
    private lateinit var prefs: Prefs

    private enum class Screen { CHOICE, CREATE, WAITING }
    private var screen: Screen = Screen.CHOICE

    private enum class Duration(val days: Int) {
        DAY_1(1), DAY_7(7), DAY_30(30), UNLIMITED(-1)
    }
    private var selectedDuration: Duration = Duration.UNLIMITED

    /** Путь сообщений. По умолчанию — Nostr (молния). */
    private enum class MsgPath { NOSTR, TOR, BT }
    private var selectedPath: MsgPath = MsgPath.NOSTR
    /** Совместимость со старым кодом: true только для Tor-пути. */
    private val selectedTor: Boolean get() = selectedPath == MsgPath.TOR

    /**
     * Тип чата: обычный (P2P, двое) или групповой.
     * Групповой режим создаётся реально (см. createGroupChat(), ADR_GROUP_CHATS.md):
     * тот же Nostr-транспорт и V5-шифрование общим паролем, БЕЗ ECDH forward-secrecy
     * рукопожатия (оно рассчитано только на двух участников). BLE-путь для групп
     * не поддерживается — только Nostr.
     */
    private enum class ChatKind { PAIR, GROUP }
    private var chatKind: ChatKind = ChatKind.PAIR

    /** Лимит участников для группового чата. Проверяется при джойне (JoinChatActivity). */
    private enum class ParticipantsLimit(val max: Int?) {
        FIVE(5), TEN(10), FIFTEEN(15), UNLIMITED(null)
    }
    private var selectedParticipants: ParticipantsLimit = ParticipantsLimit.TEN

    /** Аватар своего профиля — чтобы восстановить при возврате из группового режима. */
    private var profileAvatarBitmap: android.graphics.Bitmap? = null
    /** Аватар, выбранный для группового чата через галерею (пока только превью). */
    private var groupAvatarBitmap: android.graphics.Bitmap? = null

    /** Пароль чата — генерируется автоматически при создании. */
    private val generatedPassword: String = generateSecurePassword()

    // ── Bluetooth (BLE) ──────────────────────────────────────────────────────────
    /** Найденные BLE-устройства: адрес → строка списка (дедуп). */
    private val btRows = HashMap<String, View>()
    /** Подключённое BLE-устройство. */
    private var btConnectedDevice: BluetoothDevice? = null
    /** Этот телефон — джойнер (central, сканировал/тапнул): шлёт HELLO и ждёт invite. */
    @Volatile private var btJoiner: Boolean = false
    /** У автора: джойнер подключился и прислал профиль → можно создавать чат. */
    @Volatile private var btPeerReady: Boolean = false
    /** Профиль собеседника, полученный по BLE (имя/тег/аватар). */
    @Volatile private var btPeerName: String = ""
    @Volatile private var btPeerTag: String = ""
    @Volatile private var btPeerAvatar: String = ""
    /** Чтобы не создать/не открыть BT-чат дважды. */
    @Volatile private var btChatOpened: Boolean = false
    @Volatile private var btDiscovering: Boolean = false
    @Volatile private var pendingBtToken: String = ""
    private val btSignals = HashMap<String, android.widget.TextView>()

    /** Единый приёмник BLE-событий (и для автора-peripheral, и для джойнера-central). */
    private val btListener = object : BleManager.Listener {
        override fun onConnected(deviceName: String) = runOnUiThread {
            if (btJoiner) {
                // Джойнер: подключились → шлём свой профиль и уходим в ОЖИДАНИЕ.
                // Чат НЕ открываем — только после invite (автор нажал «Создать»).
                resetWaitUi()
                sendMyHello()
                showScreen(Screen.WAITING)
                binding.btWaitTitle.text = getString(R.string.bt_connected, deviceName)
                binding.btWaitHint.setText(R.string.bt_wait_exchanging)
            } else {
                // Автор: сразу шлём свой профиль джойнеру (чтобы он видел, кого ждёт),
                // и ждём профиль джойнера (onHello) — тогда активируем «Создать чат».
                sendMyHello()
                binding.btStatus.text = getString(R.string.bt_peer_connecting, deviceName)
            }
        }
        override fun onHello(name: String, tag: String, avatar: String) = runOnUiThread {
            if (btJoiner) {
                // Джойнер получил профиль АВТОРА → показываем, кого ждём. Чат НЕ открываем.
                showBtWaitAuthor(name, avatar)
            } else {
                // Автор получил профиль джойнера → активируем «Создать чат».
                btPeerName = name; btPeerTag = tag; btPeerAvatar = avatar
                btPeerReady = true
                clearBtRows()
                binding.btStatus.text = getString(R.string.bt_peer_ready, name.ifBlank { getString(R.string.join_default_partner_name) })
                if (selectedPath == MsgPath.BT) binding.btnCreateP2p.isEnabled = true
            }
        }
        override fun onInvite(channelId: String, password: String, name: String, tag: String, avatar: String) = runOnUiThread {
            // Получает джойнер: секреты + профиль автора → создаём тот же чат и открываем.
            openBtChatFromInvite(channelId, password, name, tag, avatar)
        }
        override fun onDisconnected() = runOnUiThread {
            btPeerReady = false
            if (selectedPath == MsgPath.BT) binding.btnCreateP2p.isEnabled = false
            if (!btChatOpened && screen == Screen.WAITING) { showScreen(Screen.CHOICE); stopBt() }
            if (!btChatOpened) binding.btStatus.setText(R.string.bt_failed)
        }
        override fun onError(reason: String) = runOnUiThread {
            if (!btChatOpened) binding.btStatus.setText(R.string.bt_failed)
        }
    }

    /** Аватар по BLE — только если небольшой (большие notify-передачи ненадёжны). */
    private fun btSafeAvatar(): String =
        (prefs.myAvatarBase64 ?: "").let { if (it.length in 1..6000) it else "" }

    private fun sendMyHello() {
        val n = prefs.myName.ifBlank { android.os.Build.MODEL }
        val t = prefs.myTag ?: ""
        val a = btSafeAvatar()
        lifecycleScope.launch(Dispatchers.IO) {
            var ok = false; var tries = 0
            while (!ok && tries < 4) { ok = BleManager.sendHello(n, t, a); if (!ok) Thread.sleep(180); tries++ }
        }
    }

    private val btPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startBtDiscovery()
        else binding.btStatus.setText(R.string.bt_perm_needed)
    }

    /** Сканер QR (вход с экрана выбора) → подключение по токену через BLE. */
    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val token = res.data?.getStringExtra(QrScanActivity.EXTRA_TOKEN)
            if (!token.isNullOrBlank()) connectByScannedToken(token)
        }
    }

    /** Перед сканером убеждаемся, что есть BT-разрешения; камеру спросит сам сканер. */
    private val qrBtPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) launchQrScanner()
        else Toast.makeText(this, R.string.bt_perm_needed, Toast.LENGTH_LONG).show()
    }

    // ── Аватарка группового чата (системный пикер + UCrop — как обычная аватарка) ──

    private val pickGroupAvatar = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) startGroupAvatarCrop(uri) }

    private val cropGroupAvatar = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = UCrop.getOutput(result.data!!)
            if (uri != null) applyGroupAvatarUri(uri)
        } else if (result.resultCode == UCrop.RESULT_ERROR && result.data != null) {
            val err = UCrop.getError(result.data!!)
            Toast.makeText(this, getString(R.string.error_avatar_load) + ": ${err?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.get(this)
        prefs = Prefs(this)

        // Choice screen
        binding.cardP2p.setOnClickListener { showScreen(Screen.CREATE) }
        binding.cardJoin.setOnClickListener {
            startActivity(Intent(this, JoinChatActivity::class.java))
        }
        binding.cardBt.setOnClickListener { onScanBtCardClicked() }
        binding.btnCancelChoice.setOnClickListener { finish() }
        binding.btnBackToChoice.setOnClickListener { leaveToChoice() }

        // Create form (P2P / DHT)
        setupDurationChips()
        setupPathSelector()
        binding.btnCreateP2p.setOnClickListener { createP2pChat() }

        prefs.myAvatarBase64?.let { base64 ->
            AvatarUtils.fromBase64(base64)?.let { bmp ->
                binding.ivAvatar.setImageBitmap(bmp)
                profileAvatarBitmap = bmp
            }
        }

        setupChatModeToggle()
        setupParticipantsChips()
        binding.flAvatarContainer.setOnClickListener {
            if (chatKind == ChatKind.GROUP) onGroupAvatarTap()
        }

        startAvatarAnimations()

        showScreen(Screen.CHOICE)
    }

    override fun onBackPressed() {
        if (screen != Screen.CHOICE) {
            leaveToChoice()
        } else {
            super.onBackPressed()
        }
    }

    /** Возврат на стартовый экран: если были в BT — рвём соединение/поиск. */
    private fun leaveToChoice() {
        if (screen == Screen.WAITING || selectedPath == MsgPath.BT) {
            btJoiner = false; btPeerReady = false; btChatOpened = false
            stopBt()
        }
        showScreen(Screen.CHOICE)
    }

    private fun showScreen(target: Screen) {
        screen = target
        binding.choiceScreen.visibility = if (target == Screen.CHOICE) View.VISIBLE else View.GONE
        binding.p2pForm.visibility = if (target == Screen.CREATE) View.VISIBLE else View.GONE
        binding.btWait.visibility = if (target == Screen.WAITING) View.VISIBLE else View.GONE
        binding.btnBackToChoice.visibility = if (target == Screen.CHOICE) View.GONE else View.VISIBLE
        binding.tvSubtitle.visibility = if (target == Screen.CHOICE) View.VISIBLE else View.GONE
    }

    // ═══ Avatar animations ═══

    private fun startAvatarAnimations() {
        // Желе-эффект: scaleX и scaleY анимируются в противофазе —
        // когда рамка растягивается по X, она сжимается по Y, и наоборот.
        val jellyX = ObjectAnimator.ofFloat(
            binding.flAvatarGlow, View.SCALE_X,
            1f, 1.07f, 0.95f, 1.04f, 0.98f, 1f
        ).apply {
            duration = 2_200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val jellyY = ObjectAnimator.ofFloat(
            binding.flAvatarGlow, View.SCALE_Y,
            1f, 0.95f, 1.07f, 0.98f, 1.04f, 1f
        ).apply {
            duration = 2_200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        AnimatorSet().apply { playTogether(jellyX, jellyY); start() }

        // Fade-in при входе на экран
        binding.flAvatarContainer.alpha = 0f
        binding.flAvatarContainer.animate()
            .alpha(1f)
            .setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    // ═══ Duration chips ═══

    private fun setupDurationChips() {
        val chips = listOf(
            binding.chip1d   to Duration.DAY_1,
            binding.chip7d   to Duration.DAY_7,
            binding.chip30d  to Duration.DAY_30,
            binding.chipUnlim to Duration.UNLIMITED
        )
        applyDurationSelection(chips)
        chips.forEach { (view, duration) ->
            view.setOnClickListener {
                selectedDuration = duration
                applyDurationSelection(chips)
            }
        }
    }

    private fun applyDurationSelection(chips: List<Pair<android.widget.TextView, Duration>>) {
        chips.forEach { (view, duration) ->
            val selected = duration == selectedDuration
            view.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_default
            )
            view.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this,
                    if (selected) R.color.accent_light else R.color.text_secondary
                )
            )
        }
    }

    // ═══ Путь сообщений (Nostr / Tor / Bluetooth) ═══

    private fun setupPathSelector() {
        applyPathSelection()
        binding.pathNostr.setOnClickListener {
            selectedPath = MsgPath.NOSTR; applyPathSelection(); animateBolt(binding.pathNostrIcon)
        }
        binding.pathTor.setOnClickListener {
            selectedPath = MsgPath.TOR; applyPathSelection(); animateShield(binding.pathTorIcon)
        }
        binding.pathBt.setOnClickListener {
            selectedPath = MsgPath.BT; applyPathSelection()
        }
        binding.btnBtShowQr.setOnClickListener { toggleMyQr() }
    }

    private fun applyPathSelection() {
        val nostr = selectedPath == MsgPath.NOSTR
        val tor = selectedPath == MsgPath.TOR
        val bt = selectedPath == MsgPath.BT
        binding.pathNostr.setBackgroundResource(if (nostr) R.drawable.bg_chip_selected else R.drawable.bg_chip_default)
        binding.pathTor.setBackgroundResource(if (tor) R.drawable.bg_chip_selected else R.drawable.bg_chip_default)
        binding.pathBt.setBackgroundResource(if (bt) R.drawable.bg_chip_selected else R.drawable.bg_chip_default)
        tintPathIcon(binding.pathNostrIcon, nostr)
        tintPathIcon(binding.pathTorIcon, tor)
        tintPathIcon(binding.pathBtIcon, bt)
        binding.tvPathDesc.setText(
            when (selectedPath) {
                MsgPath.NOSTR -> R.string.cc_path_desc_nostr
                MsgPath.TOR -> R.string.cc_path_desc_tor
                MsgPath.BT -> R.string.cc_path_desc_bt
            }
        )
        binding.btPanel.visibility = if (bt) View.VISIBLE else View.GONE
        binding.torWarningPanel.visibility = if (tor) View.VISIBLE else View.GONE
        binding.btnCreateP2p.isEnabled = !bt || btPeerReady
        if (bt) startBtDiscovery() else stopBt()
    }

    private fun tintPathIcon(v: ImageView, on: Boolean) {
        v.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                this, if (on) R.color.accent_light else R.color.text_secondary
            )
        )
    }

    /** Молния (Nostr): чёткий «рывок» вверх с подскоком масштаба. */
    private fun animateBolt(v: View) {
        v.animate().cancel()
        val up = -6f * resources.displayMetrics.density
        val sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.32f, 0.92f, 1f)
        val sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.32f, 0.92f, 1f)
        val ty = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, up, 2f, 0f)
        AnimatorSet().apply {
            playTogether(sx, sy, ty)
            duration = 640
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** Щит (Tor): медленный защитный подскок с мягкой пружиной. */
    private fun animateShield(v: View) {
        v.animate().cancel()
        val sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.26f, 0.97f, 1.06f, 1f)
        val sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.26f, 0.97f, 1.06f, 1f)
        AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 720
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    // ═══ Групповой чат (UI; создание чата — TODO, см. комментарий у ChatKind) ═══

    private fun setupChatModeToggle() {
        applyChatKind()
        binding.togglePair.setOnClickListener { chatKind = ChatKind.PAIR; applyChatKind() }
        binding.toggleGroup.setOnClickListener { chatKind = ChatKind.GROUP; applyChatKind() }
    }

    private fun applyChatKind() {
        val group = chatKind == ChatKind.GROUP

        binding.togglePair.setBackgroundResource(if (group) R.drawable.bg_chip_default else R.drawable.bg_chip_selected)
        binding.toggleGroup.setBackgroundResource(if (group) R.drawable.bg_chip_selected else R.drawable.bg_chip_default)
        binding.togglePair.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, if (group) R.color.text_secondary else R.color.accent_light)
        )
        binding.toggleGroup.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, if (group) R.color.accent_light else R.color.text_secondary)
        )

        binding.groupNameSection.visibility = if (group) View.VISIBLE else View.GONE
        binding.groupParticipantsSection.visibility = if (group) View.VISIBLE else View.GONE
        binding.tvAvatarHint.visibility = if (group) View.VISIBLE else View.GONE

        binding.tvProfileSubtitle.setText(if (group) R.string.cc_group_profile_subtitle else R.string.cc_profile_subtitle)
        binding.tvFeature1Title.setText(if (group) R.string.cc_group_feature_exchange_title else R.string.cc_feature_exchange_title)
        binding.tvFeature1Desc.setText(if (group) R.string.cc_group_feature_exchange_desc else R.string.cc_feature_exchange_desc)
        binding.tvInviteOnlyText.setText(if (group) R.string.cc_group_invite_only_text else R.string.cc_invite_only_text)
        binding.btnCreateP2p.setText(if (group) R.string.cc_group_create_button else R.string.cc_create_button)

        val avatarBmp = if (group) (groupAvatarBitmap ?: profileAvatarBitmap) else profileAvatarBitmap
        avatarBmp?.let { binding.ivAvatar.setImageBitmap(it) }
    }

    private fun setupParticipantsChips() {
        val chips = listOf(
            binding.chipPart5 to ParticipantsLimit.FIVE,
            binding.chipPart10 to ParticipantsLimit.TEN,
            binding.chipPart15 to ParticipantsLimit.FIFTEEN,
            binding.chipPartUnlim to ParticipantsLimit.UNLIMITED
        )
        applyParticipantsSelection(chips)
        chips.forEach { (view, limit) ->
            view.setOnClickListener {
                selectedParticipants = limit
                applyParticipantsSelection(chips)
            }
        }
    }

    private fun applyParticipantsSelection(chips: List<Pair<android.widget.TextView, ParticipantsLimit>>) {
        chips.forEach { (view, limit) ->
            val selected = limit == selectedParticipants
            view.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_default)
            view.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this, if (selected) R.color.accent_light else R.color.text_secondary
                )
            )
        }
        binding.tvParticipantsHelper.text = selectedParticipants.max?.let {
            getString(R.string.cc_group_participants_helper_fmt, it)
        } ?: getString(R.string.cc_group_participants_unlimited)
    }

    /** Тап по аватару в групповом режиме — системный пикер фото, затем кроп (как обычная аватарка). */
    private fun onGroupAvatarTap() {
        pickGroupAvatar.launch("image/*")
    }

    private fun startGroupAvatarCrop(sourceUri: Uri) {
        val destUri = Uri.fromFile(File(cacheDir, "group_avatar_crop_${System.currentTimeMillis()}.jpg"))
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
        cropGroupAvatar.launch(
            UCrop.of(sourceUri, destUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(1024, 1024)
                .withOptions(options)
                .getIntent(this)
        )
    }

    private fun applyGroupAvatarUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = AvatarUtils.loadAndResize(this@CreateChatActivity, uri)
            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    groupAvatarBitmap = bmp
                    binding.ivAvatar.setImageBitmap(bmp)
                } else {
                    Toast.makeText(this@CreateChatActivity, R.string.error_avatar_load, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ═══ Создание чата ═══

    private fun createP2pChat() {
        if (chatKind == ChatKind.GROUP) {
            // Групповые чаты пока только поверх Nostr (см. ADR_GROUP_CHATS.md) —
            // BLE-путь для групп не проектировался, не создаём с ложным ощущением поддержки.
            if (selectedPath == MsgPath.BT) {
                Toast.makeText(this, R.string.cc_group_coming_soon, Toast.LENGTH_LONG).show()
                return
            }
            createGroupChat()
            return
        }
        if (selectedPath == MsgPath.BT) createBtChat() else createNostrChat()
    }

    /**
     * Создаёт P2P-чат поверх публичных Nostr-реле (напрямую или через Tor).
     */
    private fun createNostrChat() {
        val roomName = prefs.myName.takeIf { it.isNotBlank() }
            ?: getString(R.string.join_default_partner_name)
        val channelId = generateChannelId()
        val password = generatedPassword
        val expiresAt = if (selectedDuration.days < 0) null
            else System.currentTimeMillis() + selectedDuration.days * 24L * 60 * 60 * 1000

        setLoading(true)
        lifecycleScope.launch {
            @Suppress("DEPRECATION")
            val chat = Chat(
                chatId = channelId,
                transportToken = "",
                chatPassword = "",
                partnerName = roomName,
                lastMessage = "",
                lastTimeMs = System.currentTimeMillis(),
                expiresAtMs = expiresAt
            )
            val pathToken = if (selectedTor) NostrTransport.NOSTR_TOKEN
                            else NostrTransport.NOSTR_DIRECT_TOKEN
            prefs.saveChatSecrets(channelId, pathToken, password)
            if (selectedTor) {
                TorManager.start(applicationContext)
                // Автор Tor-чата — тоже нажатие «создать/подключиться» по смыслу, взводим
                // тот же сторож, что и JoinChatActivity/ChatActivity (см. TorSyncWatchdog.kt).
                TorSyncWatchdog.arm(applicationContext, channelId)
            }
            val newId = withContext(Dispatchers.IO) { db.chatDao().insert(chat) }

            val myProfile = Profile(
                userId = prefs.myUserId,
                name = prefs.myName,
                tag = prefs.myTag,
                avatarBase64 = prefs.myAvatarBase64
            )
            AppScope.launch {
                try {
                    val transport = NostrTransport(channelId, password, prefs.myUserId, preferTor = selectedTor)
                    val ok = ProfileSync.pushMyProfile(transport, password, myProfile)
                    if (!ok && selectedTor) {
                        val cause = ProfileSync.lastError
                            ?: IllegalStateException("pushMyProfile вернул false, но lastError пуст")
                        TorSyncWatchdog.reportDeviation(applicationContext, channelId, "CreateChatActivity.pushMyProfile", cause)
                    }
                } catch (e: Exception) {
                    if (selectedTor) {
                        TorSyncWatchdog.reportDeviation(applicationContext, channelId, "CreateChatActivity.pushMyProfile", e)
                    }
                }
            }

            startActivity(Intent(this@CreateChatActivity, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CHAT_ID, newId)
            })
            finish()
        }
    }

    /**
     * Создаёт групповой чат (ADR-001, см. ADR_GROUP_CHATS.md). Транспортно и криптографически
     * это ТОТ ЖЕ путь, что и обычный Nostr P2P-чат (общий channelId/пароль, V5-шифрование,
     * без ECDH-рукопожатия — см. ChatActivity.onCreate) — единственное отличие: Chat.isGroup=true,
     * плюс публикуется подписанный members.txt с самим создателем как единственным участником.
     * Создатель автоматически становится администратором (adminUserId = мой userId).
     */
    private fun createGroupChat() {
        val groupName = binding.etGroupName.text?.toString()?.trim().orEmpty()
            .ifBlank { getString(R.string.cc_group_default_name) }
        val channelId = generateChannelId()
        val password = generatedPassword
        val expiresAt = if (selectedDuration.days < 0) null
            else System.currentTimeMillis() + selectedDuration.days * 24L * 60 * 60 * 1000
        val adminUserId = prefs.myUserId
        val groupAvatarB64 = groupAvatarBitmap?.let { AvatarUtils.toBase64(it) }

        setLoading(true)
        lifecycleScope.launch {
            @Suppress("DEPRECATION")
            val chat = Chat(
                chatId = channelId,
                transportToken = "",
                chatPassword = "",
                // partnerName — легаси-поле для экранов, ещё не переведённых на groupName
                // (полная переделка UI под группы — отдельный шаг, см. ADR_GROUP_CHATS.md).
                partnerName = groupName,
                lastMessage = "",
                lastTimeMs = System.currentTimeMillis(),
                expiresAtMs = expiresAt,
                isGroup = true,
                participantLimit = selectedParticipants.max,
                adminUserId = adminUserId,
                groupName = groupName,
                groupAvatarBase64 = groupAvatarB64
                // membersVersion остаётся дефолтным 0 — см. баг ниже.
            )
            val pathToken = if (selectedTor) NostrTransport.NOSTR_TOKEN
                            else NostrTransport.NOSTR_DIRECT_TOKEN
            prefs.saveChatSecrets(channelId, pathToken, password)
            if (selectedTor) {
                TorManager.start(applicationContext)
                TorSyncWatchdog.arm(applicationContext, channelId)
            }
            val newId = withContext(Dispatchers.IO) { db.chatDao().insert(chat) }

            // ⚠️ БАГ (найден и исправлен при аудите синхронизации групп): раньше здесь
            // стояло membersVersion = 1 в Chat — с намерением "чтобы наше же первичное
            // members.txt не отбросилось анти-откатом". На деле ровно наоборот: анти-откат
            // в MembersSync.applyIncoming — это "parsed.version <= chat.membersVersion",
            // т.е. НЕСТРОГОЕ сравнение. Если локальная версия УЖЕ равна 1 ДО того, как
            // applyIncoming вообще увидел версию 1 с реле — 1 <= 1 истинно, и наш
            // СОБСТВЕННЫЙ первый members.txt отбрасывался НАВСЕГДА (следующие версии
            // от админа — 2, 3... — тоже стартуют от уже "виденной" единицы штатно, баг
            // не в них). Итог: ChatParticipantDao участника-админа никогда не заполнялась
            // → maybeAdminEnrollNewMembers() в ChatActivity видела current.isEmpty() и
            // вечно ждала ("наша собственная версия 1 ещё не долетела"), из-за чего НИ
            // ОДИН новый участник не подтверждался — застревал на баннере "Ожидаем
            // подтверждения" навсегда, а счётчик участников у админа не рос никогда.
            // Фикс: membersVersion остаётся 0 (как у всех чатов по умолчанию) — обычный
            // анти-откат (1 <= 0 ложно) пропускает наш же v1 при первом же чтении с реле.
            // Плюс — сразу локально (без сети, см. §1.5 CLAUDE.md) заводим себя как
            // первого участника, чтобы счётчик показывал "1" мгновенно при создании
            // группы, не дожидаясь даже первого опроса.
            withContext(Dispatchers.IO) {
                db.chatParticipantDao().upsert(
                    com.atrum.chat.data.ChatParticipant(
                        ownerId = newId,
                        userId = adminUserId,
                        banned = false,
                        joinedAtMs = System.currentTimeMillis()
                    )
                )
            }

            val myProfile = Profile(
                userId = prefs.myUserId,
                name = prefs.myName,
                tag = prefs.myTag,
                avatarBase64 = prefs.myAvatarBase64
            )
            AppScope.launch {
                try {
                    val transport = NostrTransport(
                        sourceId = channelId,
                        chatPassword = password,
                        myUserId = adminUserId,
                        preferTor = selectedTor,
                        adminUserId = adminUserId
                    )
                    ProfileSync.pushMyProfile(transport, password, myProfile)
                    MembersSync.publish(
                        transport = transport,
                        password = password,
                        chatId = channelId,
                        adminUserId = adminUserId,
                        newVersion = 1,
                        participants = listOf(MembersSync.Entry(adminUserId, banned = false))
                    )
                } catch (e: Exception) {
                    if (selectedTor) {
                        TorSyncWatchdog.reportDeviation(applicationContext, channelId, "CreateChatActivity.createGroupChat", e)
                    }
                }
            }

            startActivity(Intent(this@CreateChatActivity, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CHAT_ID, newId)
            })
            finish()
        }
    }

    /**
     * Автор BT-чата: собеседник уже подключился и прислал профиль (btPeerReady).
     * Генерируем секреты, шлём invite по BLE и открываем чат, не разрывая соединение.
     */
    private fun createBtChat() {
        if (!btPeerReady || btChatOpened) return
        btChatOpened = true
        val channelId = generateChannelId()
        val password = generatedPassword
        val roomName = btPeerName.ifBlank { getString(R.string.join_default_partner_name) }
        val myName = prefs.myName.ifBlank { android.os.Build.MODEL }
        val myTag = prefs.myTag
        val myAvatar = btSafeAvatar()
        setLoading(true)
        lifecycleScope.launch {
            @Suppress("DEPRECATION")
            val chat = Chat(
                chatId = channelId,
                transportToken = BluetoothTransport.BT_TOKEN,
                chatPassword = "",
                partnerName = roomName,
                partnerTag = btPeerTag.ifBlank { null },
                partnerAvatarBase64 = btPeerAvatar.ifBlank { null },
                lastMessage = "",
                lastTimeMs = System.currentTimeMillis(),
                partnerJoined = true
            )
            prefs.saveChatSecrets(channelId, BluetoothTransport.BT_TOKEN, password)
            val newId = withContext(Dispatchers.IO) { db.chatDao().insert(chat) }
            withContext(Dispatchers.IO) {
                var ok = false; var tries = 0
                while (!ok && tries < 5) {
                    ok = BleManager.sendInvite(channelId, password, myName, myTag, myAvatar)
                    if (!ok) Thread.sleep(200)
                    tries++
                }
            }
            BleManager.keepAlive = true
            startActivity(Intent(this@CreateChatActivity, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CHAT_ID, newId)
            })
            finish()
        }
    }

    /** Джойнер: получил invite по BLE → создаёт тот же чат и открывает его. */
    private fun openBtChatFromInvite(channelId: String, password: String, name: String, tag: String, avatar: String) {
        if (btChatOpened) return
        btChatOpened = true
        val roomName = name.ifBlank { getString(R.string.join_default_partner_name) }
        lifecycleScope.launch {
            @Suppress("DEPRECATION")
            val chat = Chat(
                chatId = channelId,
                transportToken = BluetoothTransport.BT_TOKEN,
                chatPassword = "",
                partnerName = roomName,
                partnerTag = tag.ifBlank { null },
                partnerAvatarBase64 = avatar.ifBlank { null },
                lastMessage = "",
                lastTimeMs = System.currentTimeMillis(),
                partnerJoined = true
            )
            prefs.saveChatSecrets(channelId, BluetoothTransport.BT_TOKEN, password)
            val newId = withContext(Dispatchers.IO) { db.chatDao().insert(chat) }
            BleManager.keepAlive = true
            startActivity(Intent(this@CreateChatActivity, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CHAT_ID, newId)
            })
            finish()
        }
    }

    // ═══ Bluetooth discovery / подключение ═══

    /** Карточка «Подключиться по Bluetooth» (со стартового экрана): сканировать QR. */
    private fun onScanBtCardClicked() {
        val missing = BleManager.missingPermissions(this)
        if (missing.isNotEmpty()) qrBtPermLauncher.launch(missing.toTypedArray())
        else launchQrScanner()
    }

    private fun launchQrScanner() {
        qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
    }

    /** Поиск устройства с отсканированным токеном и подключение к нему как central. */
    private fun connectByScannedToken(token: String) {
        BleManager.setListener(btListener)
        btJoiner = true
        pendingBtToken = token
        btConnectedDevice = null
        showScreen(Screen.WAITING)
        resetWaitUi()
        val name = prefs.myName.ifBlank { android.os.Build.MODEL }
        val started = BleManager.startDiscovery(this, name) { found ->
            runOnUiThread {
                if (!btChatOpened && btConnectedDevice == null &&
                    found.token.isNotEmpty() && found.token.equals(pendingBtToken, ignoreCase = true)
                ) {
                    btConnectedDevice = found.device
                    BleManager.stopScan(this)
                    BleManager.connect(this, found.device, btListener)
                }
            }
        }
        if (!started) {
            showScreen(Screen.CHOICE)
            Toast.makeText(this, R.string.bt_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Автор: реклама + GATT-сервер + сканирование (показываем устройства рядом). */
    private fun startBtDiscovery() {
        if (btDiscovering) return
        val missing = BleManager.missingPermissions(this)
        if (missing.isNotEmpty()) { btPermLauncher.launch(missing.toTypedArray()); return }
        if (!BleManager.isEnabled(this)) { binding.btStatus.setText(R.string.bt_failed); return }
        BleManager.setListener(btListener)
        btJoiner = false
        clearBtRows()
        val name = prefs.myName.ifBlank { android.os.Build.MODEL }
        val started = BleManager.startDiscovery(this, name) { found ->
            runOnUiThread { onBtDeviceFound(found) }
        }
        btDiscovering = started
        binding.btStatus.setText(if (started) R.string.bt_searching else R.string.bt_failed)
    }

    private fun onBtDeviceFound(found: BleManager.Found) {
        val addr = found.device.address ?: return
        val existing = btSignals[addr]
        if (existing != null) { existing.text = signalLabel(found.rssi); return }
        val row = buildBtDeviceRow(found)
        btRows[addr] = row
        binding.btDeviceList.addView(row)
    }

    private fun buildBtDeviceRow(found: BleManager.Found): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundResource(R.drawable.bg_chip_default)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
            isClickable = true; isFocusable = true
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bluetooth)
            setColorFilter(androidx.core.content.ContextCompat.getColor(this@CreateChatActivity, R.color.accent_light))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).also { it.marginEnd = dp(12) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameTv = TextView(this).apply {
            text = found.name
            setTextColor(androidx.core.content.ContextCompat.getColor(this@CreateChatActivity, R.color.text_primary))
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val sigTv = TextView(this).apply {
            text = signalLabel(found.rssi)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@CreateChatActivity, R.color.text_tertiary))
            textSize = 11f
        }
        col.addView(nameTv); col.addView(sigTv)
        row.addView(icon); row.addView(col)
        btSignals[found.device.address ?: ""] = sigTv
        row.setOnClickListener { connectToDevice(found.device) }
        return row
    }

    private fun connectToDevice(device: BluetoothDevice) {
        btJoiner = true
        btConnectedDevice = device
        BleManager.stopScan(this)
        binding.btStatus.setText(R.string.bt_searching)
        BleManager.connect(this, device, btListener)
    }

    private fun signalLabel(rssi: Int): String = getString(
        when {
            rssi >= -60 -> R.string.bt_signal_strong
            rssi >= -80 -> R.string.bt_signal_medium
            else -> R.string.bt_signal_weak
        }
    )

    private fun toggleMyQr() {
        val iv = binding.ivBtQr
        if (iv.visibility == View.VISIBLE) { iv.visibility = View.GONE; return }
        val token = BleManager.sessionToken
        if (token.isBlank()) return
        val bmp = QrGen.make(QrGen.btPayload(token), (240 * resources.displayMetrics.density).toInt())
        if (bmp != null) { iv.setImageBitmap(bmp); iv.visibility = View.VISIBLE }
    }

    private fun clearBtRows() {
        btRows.clear(); btSignals.clear()
        binding.btDeviceList.removeAllViews()
        binding.ivBtQr.visibility = View.GONE
    }

    private fun resetWaitUi() {
        binding.btWaitTitle.setText(R.string.bt_wait_title)
        binding.btWaitHint.setText(R.string.bt_wait_connecting)
        binding.ivBtWaitAvatar.visibility = View.GONE
    }

    /**
     * Джойнер получил профиль автора (HELLO) — показываем, кого ждём (аватар + имя)
     * и подсказку. Чат НЕ открываем: вход только по invite (автор нажал «Создать»).
     */
    private fun showBtWaitAuthor(name: String, avatar: String) {
        val display = name.ifBlank { getString(R.string.join_default_partner_name) }
        binding.btWaitTitle.text = getString(R.string.bt_wait_for_create_fmt, display)
        binding.btWaitHint.setText(R.string.bt_wait_hint_exchange)
        val bmp = avatar.takeIf { it.isNotBlank() }?.let { AvatarUtils.fromBase64(it) }
        if (bmp != null) {
            binding.ivBtWaitAvatar.setImageBitmap(AvatarUtils.toCircle(bmp))
            binding.ivBtWaitAvatar.visibility = View.VISIBLE
        } else {
            binding.ivBtWaitAvatar.visibility = View.GONE
        }
    }

    private fun stopBt() {
        btDiscovering = false
        clearBtRows()
        if (!BleManager.keepAlive) BleManager.stopAll(applicationContext)
    }

    private fun generateChannelId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCreateP2p.isEnabled = !loading
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!BleManager.keepAlive) BleManager.stopAll(applicationContext)
    }

    companion object {
        private const val GENERATED_LEN = 14
        private const val GENERATED_ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun generateSecurePassword(): String {
            val rng = SecureRandom()
            val sb = StringBuilder(GENERATED_LEN)
            repeat(GENERATED_LEN) {
                sb.append(GENERATED_ALPHABET[rng.nextInt(GENERATED_ALPHABET.length)])
            }
            return sb.toString()
        }
    }
}
