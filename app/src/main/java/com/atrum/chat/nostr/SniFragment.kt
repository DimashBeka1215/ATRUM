package com.atrum.chat.nostr

import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * ⚠️ ОБХОД DPI-БЛОКИРОВКИ ПО SNI — своя реализация, без внешних библиотек.
 *
 * Идея та же, что у GoodbyeDPI / zapret / ByeDPI (--tlsrec / --split): наивная DPI матчит
 * доменное имя (SNI) в ОДНОМ TCP-пакете ClientHello и рвёт соединение, если имя в чёрном
 * списке. TCP — потоковый протокол: получатель (Nostr-реле) честно склеивает байты обратно
 * независимо от того, сколько было сегментов на проводе. Поэтому если разрезать самую первую
 * запись (ClientHello) на два TCP-сегмента ПРЯМО ПОСЕРЕДИНЕ поля SNI — реле ничего не
 * заметит, а блокировка, читающая один пакет за раз, потеряет цель.
 *
 * Работает ТОЛЬКО для прямого (не-Tor) пути — см. NostrRelayPool.directClient. Для Tor это
 * бессмысленно и недостижимо: сокет-слой kmp-tor приложению не отдан (см. обсуждение про
 * мосты), а DPI в этом пути видит только подключение к входному узлу Tor, а не наш трафик.
 *
 * Включается НЕ всегда — только когда прямое подключение уже похоже на заблокированное, через
 * NostrRelayPool.enableDirectFragmentation() (вызывается из NostrTransport при полном отказе
 * прямого пути, см. queryAllRelays/publishToAnyRelay). Пока флаг выключен — фабрика прозрачно
 * создаёт обычные сокеты, поведение не отличается от текущего.
 */
internal class SniFragmentingSocketFactory(
    private val enabled: () -> Boolean
) : SocketFactory() {

    override fun createSocket(): Socket = FragmentingSocket(enabled)

    override fun createSocket(host: String, port: Int): Socket =
        FragmentingSocket(enabled).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        FragmentingSocket(enabled).apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        FragmentingSocket(enabled).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        FragmentingSocket(enabled).apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/**
 * Обычный [Socket], только с перехватом самой первой записи в выходной поток — именно она
 * содержит TLS ClientHello при установке WSS-соединения (OkHttp сначала открывает сырой TCP
 * через SocketFactory, потом поверх него JSSE сам пишет байты хендшейка в этот же сокет).
 */
private class FragmentingSocket(private val enabled: () -> Boolean) : Socket() {

    private var firstWriteDone = false
    private var wrappedOut: OutputStream? = null

    override fun connect(endpoint: SocketAddress) {
        super.connect(endpoint)
        applyNoDelay()
    }

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        super.connect(endpoint, timeout)
        applyNoDelay()
    }

    // Без TCP_NODELAY (выключенный Nagle) ОС может склеить оба write() обратно в один
    // сегмент — тогда разбиение теряет смысл.
    private fun applyNoDelay() {
        runCatching { tcpNoDelay = true }
    }

    override fun getOutputStream(): OutputStream {
        wrappedOut?.let { return it }
        val real = super.getOutputStream()
        val wrapped = object : OutputStream() {
            override fun write(b: Int) { real.write(b) }
            override fun write(b: ByteArray) = write(b, 0, b.size)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (!firstWriteDone) {
                    firstWriteDone = true
                    if (enabled() && trySplitWrite(real, b, off, len)) return
                }
                real.write(b, off, len)
            }
            override fun flush() = real.flush()
            override fun close() = real.close()
        }
        wrappedOut = wrapped
        return wrapped
    }

    /**
     * Возвращает true, если запись уже выполнена (разбитой на 2 сегмента).
     *
     * ⚠️ ВАЖНО: если ПЕРВАЯ часть уже ушла в сокет, а вторая упала с ошибкой — нельзя
     * откатываться на повторную запись ВСЕГО буфера в вызывающем коде: первые [split] байт
     * тогда уйдут ДВАЖДЫ и испортят TLS ClientHello (реле получит мусор). Поэтому ошибка
     * второй записи не гасится и не даёт false — падает наружу как обычная ошибка сокета
     * (ровно то, что случилось бы и без фрагментации, если бы сокет отвалился на записи).
     * Безопасный fallback на «записать всё целиком» возможен ТОЛЬКО если не отправлено
     * вообще ничего — то есть если упала именно первая часть.
     */
    private fun trySplitWrite(real: OutputStream, b: ByteArray, off: Int, len: Int): Boolean {
        val split = SniFragment.findSplitOffset(b, off, len)
        if (split <= 0 || split >= len) return false
        try {
            real.write(b, off, split)
        } catch (e: Exception) {
            // Ничего ещё не отправлено — безопасно откатиться на обычную запись целиком.
            return false
        }
        real.flush()
        // Подстраховка сверх TCP_NODELAY — гарантирует, что ОС отправит два отдельных
        // сегмента, а не решит объединить их сама.
        Thread.sleep(2L)
        real.write(b, off + split, len - split) // исключение отсюда намеренно не ловим — см. выше
        return true
    }
}

/**
 * Чистая логика разбора минимального ClientHello — без сети, только байты, легко проверяется
 * отдельно (например юнит-тестом на заранее записанном ClientHello).
 */
internal object SniFragment {

    /**
     * Находит offset (относительно [off]), по которому стоит разрезать запись, чтобы разрубить
     * поле SNI (server_name extension) пополам. Возвращает -1, если это не TLS ClientHello,
     * структура не распознана или SNI не найден — в этом случае запись НЕ трогаем: лучше
     * не сработавший обход, чем сломанное соединение.
     */
    fun findSplitOffset(buf: ByteArray, off: Int, len: Int): Int {
        try {
            if (len < 5) return -1
            val end = off + len
            // TLS record: content_type(1)=0x16 Handshake, version(2), record_length(2)
            if (buf[off].toInt() != 0x16) return -1
            if (buf[off + 1].toInt() != 0x03) return -1
            var p = off + 5
            // Handshake header: msg_type(1)=0x01 ClientHello, length(3)
            if (p + 4 > end) return -1
            if (buf[p].toInt() != 0x01) return -1
            p += 4
            // client_version(2) + random(32)
            p += 34
            if (p + 1 > end) return -1
            // session_id
            val sidLen = buf[p].toInt() and 0xFF
            p += 1 + sidLen
            if (p + 2 > end) return -1
            // cipher_suites
            val csLen = u16(buf, p)
            p += 2 + csLen
            if (p + 1 > end) return -1
            // compression_methods
            val cmLen = buf[p].toInt() and 0xFF
            p += 1 + cmLen
            if (p + 2 > end) return -1
            // extensions block
            val extLen = u16(buf, p)
            p += 2
            val extEnd = minOf(p + extLen, end)
            while (p + 4 <= extEnd) {
                val extType = u16(buf, p)
                val extDataLen = u16(buf, p + 2)
                val dataStart = p + 4
                if (extType == 0x0000) {
                    // server_name extension: server_name_list_length(2), name_type(1)=0,
                    // name_length(2), name(name_length bytes)
                    if (dataStart + 5 > extEnd) return -1
                    val nameLen = u16(buf, dataStart + 3)
                    val nameStart = dataStart + 5
                    if (nameLen <= 0 || nameStart + nameLen > end) return -1
                    val mid = nameStart + nameLen / 2
                    return mid - off
                }
                p = dataStart + extDataLen
            }
            return -1
        } catch (e: Exception) {
            return -1
        }
    }

    private fun u16(buf: ByteArray, i: Int): Int =
        ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
}
