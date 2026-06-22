package com.atrum.chat

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Локальный шифрованный архив расшифрованной истории FS-сообщений.
 *
 * Зачем: forward secrecy ротирует сессионный ключ и уничтожает старые. Но приложение
 * перечитывает шифртексты с реле и расшифровывает заново — без архива история старше
 * текущего ключа стала бы нечитаемой У САМОГО пользователя. Архив хранит расшифрованный
 * текст локально (AES-256-GCM под Keystore-ключом из Prefs), поэтому история читается
 * всегда, а ротация даёт FS против реле/сети: старый шифртекст на реле уже не расшифровать.
 *
 * Все операции fail-safe: любой сбой архива → no-op/null, живая переписка не страдает.
 */
class FsArchive(context: Context, private val archiveKey: ByteArray) : CryptoHelper.FsArchiveHook {

    private val dir: File = context.filesDir
    private val mem = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val loaded = HashSet<String>()
    private val lock = Any()
    private val rnd = SecureRandom()

    override fun recall(chatId: String, ciphertext: String): String? = try {
        mapFor(chatId)[ctHash(ciphertext)]
    } catch (_: Throwable) { null }

    override fun remember(chatId: String, ciphertext: String, plaintext: String) {
        try {
            val m = mapFor(chatId)
            val h = ctHash(ciphertext)
            if (m.putIfAbsent(h, plaintext) == null) appendRecord(chatId, h, plaintext)
        } catch (_: Throwable) {}
    }

    private fun mapFor(chatId: String): ConcurrentHashMap<String, String> {
        synchronized(lock) { if (loaded.add(chatId)) load(chatId) }
        return mem.getOrPut(chatId) { ConcurrentHashMap() }
    }

    private fun fileFor(chatId: String): File =
        File(dir, "fsarch_" + sha256hex(chatId).take(24) + ".dat")

    private fun ctHash(ct: String): String = sha256hex(ct.trim()).take(32)

    private fun load(chatId: String) {
        val f = fileFor(chatId)
        if (!f.exists()) return
        val m = mem.getOrPut(chatId) { ConcurrentHashMap() }
        try {
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val dec = decryptRecord(line) ?: return@forEachLine
                val idx = dec.indexOf('\u0001')
                if (idx > 0) m[dec.substring(0, idx)] = dec.substring(idx + 1)
            }
        } catch (_: Throwable) {}
    }

    private fun appendRecord(chatId: String, h: String, plaintext: String) {
        val rec = encryptRecord("$h\u0001$plaintext") ?: return
        synchronized(lock) {
            try {
                FileOutputStream(fileFor(chatId), true).use {
                    it.write((rec + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Throwable) {}
        }
    }

    private fun encryptRecord(s: String): String? = try {
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(archiveKey, "AES"), GCMParameterSpec(128, nonce))
        val ct = c.doFinal(s.toByteArray(Charsets.UTF_8))
        val body = ByteArray(12 + ct.size)
        System.arraycopy(nonce, 0, body, 0, 12)
        System.arraycopy(ct, 0, body, 12, ct.size)
        Base64.encodeToString(body, Base64.NO_WRAP)
    } catch (_: Throwable) { null }

    private fun decryptRecord(b64: String): String? {
        return try {
            val body = Base64.decode(b64, Base64.NO_WRAP)
            if (body.size < 12 + 16) return null
            val nonce = body.copyOfRange(0, 12)
            val ct = body.copyOfRange(12, body.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(archiveKey, "AES"), GCMParameterSpec(128, nonce))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (_: Throwable) { null }
    }

    private fun sha256hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
