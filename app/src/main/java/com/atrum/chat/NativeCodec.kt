package com.atrum.chat

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build

object NativeCodec {

    @Volatile private var available: Boolean = false

    init {
        available = try {
            System.loadLibrary("atrumcodec")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private external fun nativeVerify(der: ByteArray): Int

    fun mismatched(context: Context): Boolean {
        if (!available) return false
        return try {
            val sigs = signaturesOf(context.applicationContext) ?: return false
            if (sigs.isEmpty()) return false
            val results = sigs.map { nativeVerify(it.toByteArray()) }
            when {
                results.any { it == 1 } -> false
                results.all { it == 0 } -> true
                else -> false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun signaturesOf(context: Context): Array<Signature>? {
        val pm = context.packageManager
        val pkg = context.packageName
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
    }
}
