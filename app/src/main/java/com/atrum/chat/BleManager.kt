package com.atrum.chat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * BLE-движок для локального офлайн-пути сообщений Atrum.
 *
 * Модель:
 *  • Каждый телефон в BT-режиме РЕКЛАМИРУЕТ сервис [SERVICE_UUID] и держит GATT-сервер
 *    (роль «ожидающего»), и одновременно СКАНИРУЕТ этот же сервис (роль «ищущего»).
 *  • В списке у пользователя видны только устройства с открытым Atrum рядом.
 *  • Тап по устройству / скан QR → подключаемся как central (GATT-клиент) к его серверу.
 *
 * Передача данных: central пишет в [RX_UUID], peripheral отвечает notify по [TX_UUID].
 * Кадры: "<type><payload>" + '\n'. Только текст (BLE медленный) — голос/медиа отключены.
 *
 * ВНИМАНИЕ: BLE на Android капризен и зависит от вендора/версии. Этот код написан по
 * стандартным API, но проверяется ТОЛЬКО на двух реальных телефонах.
 */
object BleManager {

    val SERVICE_UUID: UUID = UUID.fromString("8f1d9b10-7c4a-4e2b-9a3f-1c2d3e4f5a60")
    val RX_UUID: UUID = UUID.fromString("8f1d9b11-7c4a-4e2b-9a3f-1c2d3e4f5a60") // central → peripheral (write)
    val TX_UUID: UUID = UUID.fromString("8f1d9b12-7c4a-4e2b-9a3f-1c2d3e4f5a60") // peripheral → central (notify)
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Идентификатор производителя в BLE-рекламе (для нашего короткого токена сессии). */
    private const val MFG_ID = 0xFFFF

    /** Найденное рядом устройство Atrum. [token] — короткий токен из рекламы (для QR-матчинга). */
    data class Found(val device: BluetoothDevice, val name: String, val rssi: Int, val token: String)

    /**
     * Короткий токен текущей рекламной сессии (hex). Кладём в QR; собеседник по нему коннектится.
     *
     * ВНИМАНИЕ (безопасность): токен короткий (2 байта) — это вынужденно: legacy BLE-реклама
     * с 128-битным service UUID почти не оставляет места под manufacturer data. Токен — лишь
     * рандеву-идентификатор, НЕ секрет. Защита переписки держится на app-слое (CryptoHelper).
     * Усиление против MITM (шифрование invite ключом из QR) — отдельная задача (см. BT_AUDIT.md).
     */
    @Volatile var sessionToken: String = ""; private set
    @Volatile private var tokenBytes: ByteArray = ByteArray(0)

    interface Listener {
        fun onConnected(deviceName: String) {}
        fun onDisconnected() {}
        fun onMessage(text: String) {}      // готовая строка чата (расшифровывает ChatActivity)
        /** Джойнер прислал свой профиль (получает автор перед созданием чата). */
        fun onHello(name: String, tag: String, avatar: String) {}
        /** Автор прислал приглашение: секреты чата + свой профиль (получает джойнер). */
        fun onInvite(channelId: String, password: String, name: String, tag: String, avatar: String) {}
        fun onError(reason: String) {}
    }

    /** Разделитель полей внутри кадра (не встречается в base64/строках чата). */
    private const val SEP = '\u001e'
    /** Разделитель кадров в байтовом потоке. */
    private const val EOL = '\n'

    // ── Состояние ───────────────────────────────────────────────────────────────
    @Volatile private var adapter: BluetoothAdapter? = null
    @Volatile private var scanner: BluetoothLeScanner? = null
    @Volatile private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var clientGatt: BluetoothGatt? = null
    @Volatile private var serverDevice: BluetoothDevice? = null   // подключённый central (на peripheral)
    @Volatile private var listener: Listener? = null
    @Volatile private var txChar: BluetoothGattCharacteristic? = null      // peripheral → central (notify)
    @Volatile private var rxCharClient: BluetoothGattCharacteristic? = null // central → peripheral (write)
    @Volatile private var mtu: Int = 20                                     // полезная нагрузка на запись
    private val rxBuffer = StringBuilder()
    private val seen = ConcurrentHashMap<String, Long>()

    /** Защёлка ожидания подтверждения отправки чанка (write/notify). */
    @Volatile private var sendLatch: CountDownLatch? = null
    private val sendLock = Any()

    @Volatile var connected: Boolean = false; private set

    /** Имя подключённого собеседника (для названия BT-чата). */
    @Volatile var peerName: String = ""; private set

    /** Не рвать GATT-соединение при stopAll — когда чат передаётся из CreateChatActivity в ChatActivity. */
    @Volatile var keepAlive: Boolean = false

    /** Сменить получателя событий (CreateChatActivity → BluetoothTransport). */
    fun setListener(l: Listener?) { listener = l }

    /** Есть ли живое BLE-соединение. */
    fun isLinked(): Boolean = connected

    // ── Разрешения ──────────────────────────────────────────────────────────────

    /** Какие разрешения ещё не выданы для BLE (для запроса из Activity). */
    fun missingPermissions(ctx: Context): List<String> {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(ctx, need, "android.permission.BLUETOOTH_SCAN")
            add(ctx, need, "android.permission.BLUETOOTH_CONNECT")
            add(ctx, need, "android.permission.BLUETOOTH_ADVERTISE")
        } else {
            add(ctx, need, "android.permission.ACCESS_FINE_LOCATION")
        }
        return need
    }

    private fun add(ctx: Context, list: MutableList<String>, perm: String) {
        if (ctx.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) list.add(perm)
    }

    private fun adapter(ctx: Context): BluetoothAdapter? {
        adapter?.let { return it }
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = mgr?.adapter
        return adapter
    }

    fun isEnabled(ctx: Context): Boolean = adapter(ctx)?.isEnabled == true

    // ── Поиск + реклама (запускаем оба в BT-режиме) ──────────────────────────────

    @SuppressLint("MissingPermission")
    fun startDiscovery(ctx: Context, displayName: String, onFound: (Found) -> Unit): Boolean {
        val a = adapter(ctx) ?: return false
        if (!a.isEnabled) return false
        if (missingPermissions(ctx).isNotEmpty()) return false
        seen.clear()
        tokenBytes = ByteArray(2).also { java.security.SecureRandom().nextBytes(it) }
        sessionToken = tokenBytes.joinToString("") { "%02x".format(it) }
        startAdvertise(ctx, a, displayName)
        startGattServer(ctx)
        return startScan(ctx, a, onFound)
    }

    @SuppressLint("MissingPermission")
    private fun startScan(ctx: Context, a: BluetoothAdapter, onFound: (Found) -> Unit): Boolean {
        val s = a.bluetoothLeScanner ?: return false
        scanner = s
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return
                val now = System.currentTimeMillis()
                pruneSeen(now)
                val last = seen[addr]
                seen[addr] = now
                if (last != null && now - last < 4000) return // не спамим повторами
                val name = runCatching { result.scanRecord?.deviceName }.getOrNull()
                    ?: runCatching { dev.name }.getOrNull()
                    ?: "Atrum-устройство"
                val tk = runCatching { result.scanRecord?.getManufacturerSpecificData(MFG_ID) }
                    .getOrNull()?.joinToString("") { "%02x".format(it) } ?: ""
                onFound(Found(dev, name, result.rssi, tk))
            }
            override fun onScanFailed(errorCode: Int) {
                listener?.onError("scan failed: $errorCode")
            }
        }
        scanCb = cb
        return try { s.startScan(listOf(filter), settings, cb); true } catch (_: Throwable) { false }
    }

    /** Чистим старые записи seen, чтобы карта не росла бесконечно при долгом скане. */
    private fun pruneSeen(now: Long) {
        if (seen.size < 64) return
        val it = seen.entries.iterator()
        while (it.hasNext()) { if (now - it.next().value > 30_000) it.remove() }
    }

    @Volatile private var scanCb: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun stopScan(ctx: Context) {
        val s = scanner ?: return
        val cb = scanCb ?: return
        runCatching { s.stopScan(cb) }
        scanCb = null
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertise(ctx: Context, a: BluetoothAdapter, displayName: String) {
        val adv = a.bluetoothLeAdvertiser ?: return
        advertiser = adv
        runCatching { a.name = displayName } // имя в scanRecord (может быть усечено вендором)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .apply { if (tokenBytes.isNotEmpty()) addManufacturerData(MFG_ID, tokenBytes) }
            .build()
        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        runCatching { adv.startAdvertising(settings, data, scanResp, advCb) }
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { listener?.onError("advertise failed: $errorCode") }
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer(ctx: Context) {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val server = mgr.openGattServer(ctx, gattServerCb) ?: return
        gattServer = server
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        tx.addDescriptor(BluetoothGattDescriptor(CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        txChar = tx
        runCatching { server.addService(service) }
    }

    private val gattServerCb = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverDevice = device
                connected = true
                peerName = runCatching { device.name }.getOrNull() ?: peerName
                listener?.onConnected(peerName.ifBlank { "устройство" })
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (serverDevice?.address == device.address) {
                    serverDevice = null
                    if (clientGatt == null) { connected = false; listener?.onDisconnected() }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            this@BleManager.mtu = (mtu - 3).coerceIn(20, 512)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (characteristic.uuid == RX_UUID && value != null) {
                handleIncoming(value)
            }
            if (responseNeeded) {
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            // Central включает/выключает notify (CCCD). Подтверждаем — notify шлём всегда.
            if (responseNeeded) {
                runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
            }
        }

        /** Notify доставлен — отпускаем защёлку, шлём следующий чанк. */
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            sendLatch?.countDown()
        }
    }

    // ── Подключение как central (тап по устройству / скан QR) ─────────────────────

    @SuppressLint("MissingPermission")
    fun connect(ctx: Context, device: BluetoothDevice, l: Listener) {
        listener = l
        stopScan(ctx)
        clientGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(ctx, false, clientCb, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(ctx, false, clientCb)
        }
    }

    private val clientCb = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clientGatt = gatt
                    if (!runCatching { gatt.requestMtu(512) }.getOrDefault(false)) {
                        runCatching { gatt.discoverServices() }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    rxCharClient = null
                    runCatching { gatt.close() }
                    if (clientGatt === gatt) clientGatt = null
                    if (serverDevice == null) { connected = false; listener?.onDisconnected() }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtuValue: Int, status: Int) {
            this@BleManager.mtu = (mtuValue - 3).coerceIn(20, 512)
            runCatching { gatt.discoverServices() }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(SERVICE_UUID)
            if (svc == null) { listener?.onError("service not found"); return }
            rxCharClient = svc.getCharacteristic(RX_UUID)
            val tx = svc.getCharacteristic(TX_UUID)
            if (tx != null) {
                runCatching {
                    gatt.setCharacteristicNotification(tx, true)
                    val cccd = tx.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            run {
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(cccd)
                            }
                        }
                    }
                }
            }
            connected = true
            peerName = runCatching { gatt.device.name }.getOrNull() ?: peerName
            listener?.onConnected(peerName.ifBlank { "устройство" })
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == TX_UUID) handleIncoming(ch.value ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (ch.uuid == TX_UUID) handleIncoming(value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            sendLatch?.countDown()
        }
    }

    // ── Приём кадров ──────────────────────────────────────────────────────────────

    private fun handleIncoming(bytes: ByteArray) {
        synchronized(rxBuffer) {
            rxBuffer.append(String(bytes, Charsets.UTF_8))
            var idx = rxBuffer.indexOf(EOL.toString())
            while (idx >= 0) {
                val frame = rxBuffer.substring(0, idx)
                rxBuffer.delete(0, idx + 1)
                if (frame.isNotEmpty()) dispatchFrame(frame)
                idx = rxBuffer.indexOf(EOL.toString())
            }
        }
    }

    private fun dispatchFrame(frame: String) {
        val type = frame[0]
        val rest = if (frame.length > 2 && frame[1] == SEP) frame.substring(2) else ""
        when (type) {
            'M' -> listener?.onMessage(rest)
            'H' -> {
                val p = rest.split(SEP)
                listener?.onHello(p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            }
            'I' -> {
                val p = rest.split(SEP)
                listener?.onInvite(
                    p.getOrElse(0) { "" }, p.getOrElse(1) { "" }, p.getOrElse(2) { "" },
                    p.getOrElse(3) { "" }, p.getOrElse(4) { "" }
                )
            }
        }
    }

    // ── Отправка кадров ───────────────────────────────────────────────────────────

    fun sendText(line: String): Boolean = sendFrame("M" + SEP + line)

    fun sendHello(name: String, tag: String, avatar: String): Boolean =
        sendFrame("H" + SEP + name + SEP + tag + SEP + avatar)

    fun sendInvite(channelId: String, password: String, name: String, tag: String, avatar: String): Boolean =
        sendFrame("I" + SEP + channelId + SEP + password + SEP + name + SEP + tag + SEP + avatar)

    @SuppressLint("MissingPermission")
    private fun sendFrame(frame: String): Boolean {
        if (!connected) return false
        val bytes = (frame + EOL).toByteArray(Charsets.UTF_8)
        val chunkSize = mtu.coerceIn(20, 512)
        synchronized(sendLock) {
            val gatt = clientGatt
            val rx = rxCharClient
            val server = gattServer
            val dev = serverDevice
            val tx = txChar
            var off = 0
            while (off < bytes.size) {
                val end = minOf(off + chunkSize, bytes.size)
                val chunk = bytes.copyOfRange(off, end)
                val latch = CountDownLatch(1)
                sendLatch = latch
                val ok = when {
                    gatt != null && rx != null -> writeChunkCentral(gatt, rx, chunk)
                    server != null && dev != null && tx != null -> notifyChunk(server, dev, tx, chunk)
                    else -> false
                }
                if (!ok) { sendLatch = null; return false }
                runCatching { latch.await(3, TimeUnit.SECONDS) }
                sendLatch = null
                off = end
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun writeChunkCentral(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, chunk: ByteArray): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.value = chunk
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(ch)
                }
            }
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun notifyChunk(
        server: BluetoothGattServer, dev: BluetoothDevice, ch: BluetoothGattCharacteristic, chunk: ByteArray
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(dev, ch, false, chunk) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.value = chunk
                server.notifyCharacteristicChanged(dev, ch, false)
            }
        }
    }.getOrDefault(false)

    // ── Остановка / очистка ───────────────────────────────────────────────────────

    /** Мягкая остановка: уважает [keepAlive] (для перехода CreateChatActivity → ChatActivity). */
    @SuppressLint("MissingPermission")
    fun stopAll(ctx: Context) {
        if (keepAlive) return
        teardown()
    }

    /** Полное завершение BLE (вызывается при закрытии BT-чата). */
    @SuppressLint("MissingPermission")
    fun shutdown(ctx: Context) {
        keepAlive = false
        teardown()
    }

    @SuppressLint("MissingPermission")
    private fun teardown() {
        runCatching { scanner?.stopScan(scanCb) }
        scanCb = null
        runCatching { advertiser?.stopAdvertising(advCb) }
        runCatching { clientGatt?.close() }
        clientGatt = null
        runCatching { gattServer?.close() }
        gattServer = null
        rxCharClient = null
        txChar = null
        serverDevice = null
        connected = false
        synchronized(rxBuffer) { rxBuffer.setLength(0) }
        sendLatch?.countDown()
        sendLatch = null
        listener = null
    }
}
