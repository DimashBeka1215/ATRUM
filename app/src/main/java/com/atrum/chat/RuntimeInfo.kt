package com.atrum.chat

import android.content.Context
import android.os.Debug
import java.io.File

object RuntimeInfo {

    fun compromised(context: Context): Boolean = try {
        fridaInMaps() || xposedPresent() || debuggerAttached()
    } catch (_: Throwable) {
        false
    }

    private fun fridaInMaps(): Boolean = try {
        File("/proc/self/maps").useLines { lines ->
            lines.any { line ->
                val s = line.lowercase()
                s.contains("frida") || s.contains("gum-js-loop") || s.contains("linjector")
            }
        }
    } catch (_: Throwable) {
        false
    }

    private fun xposedPresent(): Boolean {
        for (name in arrayOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XC_MethodHook",
            "de.robv.android.xposed.IXposedHookLoadPackage"
        )) {
            try {
                Class.forName(name)
                return true
            } catch (_: Throwable) {
            }
        }
        try {
            for (element in Throwable().stackTrace) {
                val n = element.className
                if (n.startsWith("de.robv.android.xposed") || n.contains("com.saurik.substrate")) return true
            }
        } catch (_: Throwable) {
        }
        return try {
            File("/proc/self/maps").useLines { lines ->
                lines.any { line ->
                    val s = line.lowercase()
                    s.contains("xposed") || s.contains("liblspatch") ||
                        s.contains("libriru") || s.contains("liblsp")
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun debuggerAttached(): Boolean = try {
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    } catch (_: Throwable) {
        false
    }
}
