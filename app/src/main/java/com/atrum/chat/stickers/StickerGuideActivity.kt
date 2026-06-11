package com.atrum.chat.stickers

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.atrum.chat.R
import com.atrum.chat.databinding.ActivityStickerGuideBinding
import org.json.JSONObject

/**
 * Гайд «как добавить стикер-пак» — WebView с анимированным пошаговым мокапом
 * (assets/sticker_guide.html). Тексты шагов локализованы: подставляются из @string
 * через applyStrings(L) после загрузки страницы.
 */
class StickerGuideActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityStickerGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }

        val web = binding.webGuide
        web.settings.javaScriptEnabled = true
        web.setBackgroundColor(Color.BLACK)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val json = buildStringsJson().toString()
                view?.evaluateJavascript("applyStrings($json);", null)
            }
        }
        web.loadUrl("file:///android_asset/sticker_guide.html")
    }

    /** Локализованные строки для инжекта в HTML (ru/en автоматически по локали). */
    private fun buildStringsJson(): JSONObject {
        val o = JSONObject()
        o.put("step", getString(R.string.sticker_guide_step))
        o.put("search", getString(R.string.sticker_guide_search))
        o.put("official", getString(R.string.sticker_guide_official))
        o.put("bfname", getString(R.string.sticker_guide_bf_name))
        o.put("botname", getString(R.string.sticker_guide_botname))
        o.put("bfuser", getString(R.string.sticker_guide_bf_user))
        o.put("tokenline", getString(R.string.sticker_guide_token_line))
        o.put("keep", getString(R.string.sticker_guide_keep))
        o.put("copied", getString(R.string.sticker_guide_copied))
        o.put("settings", getString(R.string.sticker_settings_title))
        o.put("tokensec", getString(R.string.sticker_settings_token_section))
        o.put("gettoken", getString(R.string.sticker_settings_get_token))
        o.put("save", getString(R.string.btn_save))
        o.put("addpack", getString(R.string.sticker_add_pack_title))
        o.put("loading", getString(R.string.sticker_guide_loading))
        o.put("done", getString(R.string.sticker_guide_done))
        o.put("s1t", getString(R.string.sticker_guide_s1_t)); o.put("s1d", getString(R.string.sticker_guide_s1_d))
        o.put("s2t", getString(R.string.sticker_guide_s2_t)); o.put("s2d", getString(R.string.sticker_guide_s2_d))
        o.put("s3t", getString(R.string.sticker_guide_s3_t)); o.put("s3d", getString(R.string.sticker_guide_s3_d))
        o.put("s4t", getString(R.string.sticker_guide_s4_t)); o.put("s4d", getString(R.string.sticker_guide_s4_d))
        o.put("s5t", getString(R.string.sticker_guide_s5_t)); o.put("s5d", getString(R.string.sticker_guide_s5_d))
        o.put("s6t", getString(R.string.sticker_guide_s6_t)); o.put("s6d", getString(R.string.sticker_guide_s6_d))
        return o
    }
}
