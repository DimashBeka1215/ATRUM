package com.atrum.chat.stickers

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.atrum.chat.NeonDialog
import com.atrum.chat.Prefs
import com.atrum.chat.R
import com.atrum.chat.databinding.ActivityStickerSettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StickerSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStickerSettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var repository: StickerRepository
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStickerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        repository = StickerRepository(this)

        updateTokenPreview()
        binding.btnTokenContainer.setOnClickListener { showEditTokenDialog() }
        binding.btnSaveToken.setOnClickListener { showEditTokenDialog() }
        binding.btnGetToken.setOnClickListener { openBotFather() }
        binding.btnHelp.setOnClickListener {
            startActivity(android.content.Intent(this, StickerGuideActivity::class.java))
        }
        binding.btnBack.setOnClickListener { finish() }

        // Add pack
        binding.btnAddPack.setOnClickListener { showAddPackDialog() }

        // Load pack list
        loadPacks()
    }

    private fun updateTokenPreview() {
        val token = prefs.stickerBotToken
        binding.tvTokenPreview.text = if (token.isBlank()) {
            getString(R.string.sticker_settings_token_hint)
        } else {
            "••••" + token.takeLast(4).padStart(token.length - 4, '•')
        }
    }

    private fun showEditTokenDialog() {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.sticker_settings_token_section),
            initialText = prefs.stickerBotToken,
            positiveText = getString(R.string.sticker_settings_token_save),
            negativeText = getString(R.string.sticker_add_pack_cancel),
            subtitle = getString(R.string.sticker_settings_token_hint_desc)
        ) { newToken ->
            prefs.stickerBotToken = newToken
            updateTokenPreview()
            Toast.makeText(this, getString(R.string.sticker_settings_token_saved), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPacks() {
        scope.launch {
            val packs = withContext(Dispatchers.IO) { repository.loadLocalPacks() }
            renderPacks(packs)
        }
    }

    private fun renderPacks(packs: List<StickerPack>) {
        val container = binding.packsContainer
        container.removeAllViews()

        if (packs.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.sticker_empty_title)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@StickerSettingsActivity, R.color.text_secondary))
                setPadding(64, 32, 64, 32)
            }
            container.addView(empty)
            return
        }

        packs.forEachIndexed { index, pack ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_sticker_pack_row, container, false)

            row.findViewById<TextView>(R.id.tvPackTitle).text = pack.title
            row.findViewById<TextView>(R.id.tvPackCount).text =
                resources.getQuantityString(
                    R.plurals.sticker_count, pack.stickers.size, pack.stickers.size
                )

            val thumb = row.findViewById<ImageView>(R.id.ivPackThumb)
            val thumbSticker = pack.stickers.firstOrNull { it.localPath == pack.thumbPath } ?: pack.stickers.firstOrNull()
            
            if (thumbSticker != null) {
                val thumbPx = (56 * resources.displayMetrics.density).toInt()
                scope.launch {
                    val bmp = repository.renderFirstFrame(thumbSticker, maxSize = thumbPx)
                    if (bmp != null) thumb.setImageBitmap(bmp)
                }
            }

            row.findViewById<ImageButton>(R.id.btnRenamePack).setOnClickListener {
                showRenameDialog(pack)
            }

            row.findViewById<ImageButton>(R.id.btnDeletePack).setOnClickListener {
                confirmDelete(pack)
            }

            container.addView(row)

            if (index < packs.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.marginStart = (52 * resources.displayMetrics.density).toInt() }
                    setBackgroundColor(
                        ContextCompat.getColor(this@StickerSettingsActivity, R.color.border)
                    )
                }
                container.addView(divider)
            }
        }
    }

    private fun showRenameDialog(pack: StickerPack) {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.sticker_rename_title),
            initialText = pack.title,
            positiveText = getString(R.string.sticker_rename_save),
            negativeText = getString(R.string.sticker_add_pack_cancel),
            subtitle = getString(R.string.sticker_rename_hint)
        ) { newTitle ->
            if (newTitle.isNotBlank() && newTitle.trim() != pack.title) {
                scope.launch {
                    withContext(Dispatchers.IO) { repository.renamePack(pack.name, newTitle) }
                    loadPacks()
                }
            }
        }
    }

    private fun confirmDelete(pack: StickerPack) {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.sticker_settings_delete_title),
            message = getString(R.string.sticker_settings_delete_msg, pack.title),
            positiveText = getString(R.string.sticker_settings_delete_confirm),
            positiveIsDestructive = true,
            negativeText = getString(R.string.sticker_add_pack_cancel)
        ) {
            scope.launch {
                withContext(Dispatchers.IO) { repository.removePack(pack.name) }
                loadPacks()
                Toast.makeText(
                    this@StickerSettingsActivity,
                    getString(R.string.sticker_settings_deleted),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showAddPackDialog() {
        if (prefs.stickerBotToken.isBlank()) {
            Toast.makeText(
                this, getString(R.string.sticker_settings_no_token), Toast.LENGTH_LONG
            ).show()
            showEditTokenDialog()
            return
        }

        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.sticker_add_pack_title),
            initialText = "",
            positiveText = getString(R.string.sticker_add_pack_btn),
            negativeText = getString(R.string.sticker_add_pack_cancel),
            subtitle = getString(R.string.sticker_add_pack_hint)
        ) { input ->
            if (input.isNotBlank()) downloadPack(input)
        }
    }

    private fun downloadPack(input: String) {
        binding.loadingContainer.visibility = View.VISIBLE
        binding.pbDownload.progress = 0
        
        scope.launch {
            try {
                repository.addPack(input) { downloaded, total ->
                    runOnUiThread {
                        binding.pbDownload.max = total
                        binding.pbDownload.progress = downloaded
                    }
                }
                binding.loadingContainer.visibility = View.GONE
                loadPacks()
            } catch (e: IllegalArgumentException) {
                binding.loadingContainer.visibility = View.GONE
                Toast.makeText(
                    this@StickerSettingsActivity,
                    getString(R.string.sticker_error_invalid_link),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: StickerException) {
                binding.loadingContainer.visibility = View.GONE
                Toast.makeText(
                    this@StickerSettingsActivity,
                    getString(R.string.sticker_error_not_found) + ": " + e.message,
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                binding.loadingContainer.visibility = View.GONE
                Toast.makeText(
                    this@StickerSettingsActivity,
                    getString(R.string.sticker_error_network),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Открывает @BotFather в Telegram/браузере — там пользователь создаёт бота и получает токен. */
    private fun openBotFather() {
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://t.me/BotFather")
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.sticker_error_network, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }
}
