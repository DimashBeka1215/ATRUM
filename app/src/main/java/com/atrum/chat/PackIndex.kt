package com.atrum.chat

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

object PackIndex {

    private val EXPECTED: IntArray = run {
        val enc = intArrayOf(
            144, 191, 93, 169, 74, 221, 164, 55, 145, 109, 131, 170, 3, 80, 142, 204,
            83, 228, 58, 254, 233, 174, 100, 25, 105, 36, 16, 55, 46, 248, 99, 47
        )
        IntArray(enc.size) { enc[it] xor 0x9C }
    }

    fun mismatched(context: Context): Boolean {
        return try {
            val hashes = digestsOf(context.applicationContext)
            if (hashes.isEmpty()) false else hashes.none { it }
        } catch (_: Throwable) {
            false
        }
    }

    fun enforce(activity: Activity) {
        if (mismatched(activity)) {
            UpdateRequiredActivity.launch(activity)
            activity.finishAffinity()
        }
    }

    private fun digestsOf(context: Context): List<Boolean> {
        val pm = context.packageManager
        val pkg = context.packageName
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: return emptyList()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures ?: return emptyList()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig -> equalsExpected(md.digest(sig.toByteArray())) }
    }

    private fun equalsExpected(digest: ByteArray): Boolean {
        if (digest.size != EXPECTED.size) return false
        var diff = 0
        for (i in EXPECTED.indices) {
            diff = diff or ((digest[i].toInt() and 0xFF) xor EXPECTED[i])
        }
        return diff == 0
    }
}
