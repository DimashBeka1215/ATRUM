package com.atrum.chat

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.mods.ModInfo
import com.atrum.chat.mods.ModManager
import kotlinx.coroutines.launch

/**
 * Экран «Моды» (Фаза 1): тянет подписанный каталог из GitHub, проверяет подпись и
 * показывает список (имя/описание/версия/статус доверия). Установка/загрузка .dex —
 * Фаза 2 (кнопка пока показывает заглушку).
 */
class ModsActivity : SecureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mods)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val status = findViewById<TextView>(R.id.tvStatus)
        val container = findViewById<LinearLayout>(R.id.llMods)

        status.visibility = View.VISIBLE
        status.text = getString(R.string.mods_loading)

        lifecycleScope.launch {
            when (val r = ModManager.fetchCatalog()) {
                is ModManager.Result.Err -> {
                    status.visibility = View.VISIBLE
                    status.text = getString(R.string.mods_error_fmt, r.message)
                }
                is ModManager.Result.Ok -> {
                    when {
                        r.mods.isEmpty() -> {
                            status.visibility = View.VISIBLE
                            status.text = getString(R.string.mods_empty)
                        }
                        !r.verified -> {
                            status.visibility = View.VISIBLE
                            status.text = getString(R.string.mods_dev_banner)
                        }
                        else -> status.visibility = View.GONE
                    }
                    val inflater = LayoutInflater.from(this@ModsActivity)
                    for (mod in r.mods) {
                        container.addView(buildCard(inflater, container, mod, r.verified))
                    }
                }
            }
        }
    }

    private fun buildCard(
        inflater: LayoutInflater,
        parent: LinearLayout,
        mod: ModInfo,
        verified: Boolean
    ): View {
        val card = inflater.inflate(R.layout.item_mod, parent, false)
        card.findViewById<TextView>(R.id.tvModName).text = mod.name
        card.findViewById<TextView>(R.id.tvModDesc).text = mod.description
        card.findViewById<TextView>(R.id.tvModVer).text = "v${mod.versionName}"

        val signed = card.findViewById<TextView>(R.id.tvModSigned)
        val badge = card.findViewById<ImageView>(R.id.ivModBadge)
        val color = ContextCompat.getColor(this, if (verified) R.color.accent else R.color.warning)
        signed.text = getString(if (verified) R.string.mods_signed else R.string.mods_unverified)
        signed.setTextColor(color)
        badge.setImageResource(if (verified) R.drawable.ic_shield_check else R.drawable.ic_shield_x)
        ImageViewCompat.setImageTintList(badge, ColorStateList.valueOf(color))

        card.findViewById<TextView>(R.id.btnModAction).setOnClickListener {
            // Загрузка .dex с проверкой хеша и подписи (см. ModLoader). Без настроенного
            // издателя загрузчик откажет — это ожидаемо в dev-режиме Фазы 2.
            lifecycleScope.launch {
                val host = com.atrum.chat.mods.ModHostImpl(this@ModsActivity, BuildConfig.VERSION_CODE)
                when (val res = com.atrum.chat.mods.ModLoader.downloadAndLoad(this@ModsActivity, mod, host)) {
                    is com.atrum.chat.mods.ModLoader.Result.Ok ->
                        Toast.makeText(this@ModsActivity, getString(R.string.mods_loaded_fmt, mod.name), Toast.LENGTH_SHORT).show()
                    is com.atrum.chat.mods.ModLoader.Result.Err ->
                        Toast.makeText(this@ModsActivity, getString(R.string.mods_load_failed_fmt, res.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
        return card
    }
}
