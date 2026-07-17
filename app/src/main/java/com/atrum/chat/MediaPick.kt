package com.atrum.chat

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope

/**
 * Разрешения и запуск НАШЕЙ галереи ([GalleryPicker]) для выбора ОДНОГО изображения —
 * единая замена системного ACTION_GET_CONTENT в аватарках/шапке/обоях (та же нативная
 * шторка, что в чате). Каждый экран регистрирует свой permission-launcher и зовёт [pickOne]
 * после проверки доступа.
 */
object MediaPick {

    fun perms(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAccess(ctx: Context): Boolean = perms().any {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Показать нашу галерею для выбора ОДНОГО изображения; [onPicked] — выбранный Uri. */
    fun pickOne(activity: Activity, scope: CoroutineScope, onPicked: (Uri) -> Unit) {
        GalleryPicker(
            activity = activity,
            scope = scope,
            maxSelection = 1,
            onDone = { uris -> uris.firstOrNull()?.let(onPicked) }
        ).show(showPickMore = false)
    }
}
