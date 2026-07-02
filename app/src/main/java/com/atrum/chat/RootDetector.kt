package com.atrum.chat

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Надёжный (по нескольким признакам) детект наличия root-доступа на устройстве.
 *
 * Проверки НЕ запускают su и не вызывают запрос суперпользователя — только чтение
 * файловой системы, список пакетов, теги сборки и `which su`. Любое одно срабатывание
 * считается признаком root. Возможны ложные срабатывания на кастомных прошивках —
 * поэтому окно только предупреждает и ни на что не влияет.
 */
object RootDetector {

    fun isRooted(ctx: Context): Boolean =
        hasSuBinary() || hasRootPackages(ctx) || hasTestKeys() || whichSu() || hasMagiskPaths()

    private val suPaths = arrayOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/xbin/su",
        "/data/local/bin/su", "/data/local/su", "/system/app/Superuser.apk",
        "/vendor/bin/su", "/system/bin/.ext/.su", "/system/usr/we-need-root/su",
        "/cache/su", "/dev/su", "/system/xbin/mu"
    )

    private fun hasSuBinary(): Boolean =
        suPaths.any { runCatching { File(it).exists() }.getOrDefault(false) }

    /** Пути, характерные для Magisk. */
    private val magiskPaths = arrayOf(
        "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules",
        "/cache/.disable_magisk", "/dev/.magisk.unblock"
    )

    private fun hasMagiskPaths(): Boolean =
        magiskPaths.any { runCatching { File(it).exists() }.getOrDefault(false) }

    private val rootPkgs = arrayOf(
        "com.topjohnwu.magisk", "io.github.huskydg.magisk", "com.topjohnwu.magisk.debug",
        "eu.chainfire.supersu", "com.noshufou.android.su", "com.noshufou.android.su.elite",
        "com.koushikdutta.superuser", "com.thirdparty.superuser", "com.yellowes.su",
        "com.kingroot.kinguser", "com.kingo.root", "com.zachspong.temprootremovejb",
        "com.ramdroid.appquarantine", "com.topjohnwu.magisk.hidden"
    )

    private fun hasRootPackages(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return rootPkgs.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }
    }

    private fun hasTestKeys(): Boolean =
        Build.TAGS?.contains("test-keys") == true

    /** `which su` — read-only, НЕ запускает su и не триггерит запрос root. */
    private fun whichSu(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
        val line = p.inputStream.bufferedReader().use { it.readLine() }
        p.destroy()
        !line.isNullOrBlank()
    }.getOrDefault(false)
}
