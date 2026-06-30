package com.atrum.chat

import com.atrum.chat.transport.BluetoothTransport
import com.atrum.chat.transport.NostrTransport

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothDevice
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            AvatarUtils.fromBase64(base64)?.let { bmp -> binding.ivAvatar.setImageBitmap(bmp) }
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

    // ═══ Создание чата ═══

    private fun createP2pChat() {
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
            if (selectedTor) TorManager.start(applicationContext)
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
                    ProfileSync.pushMyProfile(transport, password, myProfile)
                } catch (_: Exception) {
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
