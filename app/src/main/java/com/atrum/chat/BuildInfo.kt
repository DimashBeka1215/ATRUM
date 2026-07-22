package com.atrum.chat

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

object BuildInfo {

    private val OFFICIAL_SHA256: Set<String> = setOf(decodeFingerprint())

    private fun decodeFingerprint(): String {
        val enc = intArrayOf(
            7, 84, 5, 4, 84, 6, 4, 2, 83, 1, 3, 6, 4, 15, 86, 85, 7, 83, 81, 6, 6, 81,
            4, 1, 14, 81, 84, 84, 6, 5, 2, 7, 84, 81, 0, 15, 86, 1, 1, 5, 0, 2, 4, 5,
            81, 15, 15, 2, 81, 2, 85, 15, 15, 84, 86, 85, 85, 5, 1, 3, 81, 81, 85, 4
        )
        val out = ByteArray(enc.size) { (enc[it] xor 0x37).toByte() }
        return String(out, Charsets.US_ASCII)
    }

    @Volatile private var cached: Boolean? = null

    fun isTampered(context: Context): Boolean {
        cached?.let { return it }
        val result = computeTampered(context.applicationContext)
        cached = result
        return result
    }

    private fun computeTampered(context: Context): Boolean {
        if (OFFICIAL_SHA256.isEmpty()) return false
        return try {
            val actual = currentSignatureHashes(context)
            when {
                actual.isEmpty() -> false
                actual.any { it in OFFICIAL_SHA256 } -> false
                else -> true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun currentSignatureHashes(context: Context): List<String> {
        val pm = context.packageManager
        val pkg = context.packageName
        val signatures: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return emptyList()
            signingInfo.apkContentsSigners ?: return emptyList()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures ?: return emptyList()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }
    }
}
