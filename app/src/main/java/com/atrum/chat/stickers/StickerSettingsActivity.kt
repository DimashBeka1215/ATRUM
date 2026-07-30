package com.atrum.chat.stickers

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
import com.atrum.chat.AppLock
import com.atrum.chat.NeonDialog
import com.atrum.chat.ZalgoFilter
import com.atrum.chat.Prefs
import com.atrum.chat.R
import com.atrum.chat.transparentNavBar
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

    private var idleJob: Job? = null

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

    override fun onResume() {
        super.onResume()
        startIdleAnimation()
    }

    override fun onPause() {
        super.onPause()
        idleJob?.cancel()
    }

    private fun startIdleAnimation() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(12000) // Пауза 12 секунд
                if (binding.packsContainer.childCount > 0) {
                    val firstPack = binding.packsContainer.getChildAt(0)
                    // Проверяем, что это не TextView "Пусто" (у него нет id R.id.tvPackTitle)
                    if (firstPack?.findViewById<View>(R.id.tvPackTitle) != null) {
                        animateHint(firstPack)
                    }
                }
            }
        }
    }

    private fun animateHint(view: View) {
        view.animate()
            .scaleX(1.02f)
            .scaleY(1.02f)
            .setDuration(600)
            .setInterpolator(android.view.animation.CycleInterpolator(1f))
            .start()
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

            row.setOnClickListener {
                showPackStickers(pack)
            }

            container.addView(row)

            // Запускаем анимацию-подсказку только один раз на первом добавленном паке
            if (index == 0 && !prefs.stickerPackHintShown) {
                prefs.stickerPackHintShown = true
                row.postDelayed({ animateHint(row) }, 500)
            }

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

    private fun showPackStickers(pack: StickerPack) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar).also { it.window?.transparentNavBar() }
        val dialogBinding = com.atrum.chat.databinding.DialogStickerPackInfoBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Применяем стиль Neon к окну диалога
        dialog.window?.apply {
            val bg = com.atrum.chat.NeonDialog.run { neonBg() }
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            dialogBinding.root.background = bg
            dialogBinding.root.clipToOutline = true
            
            val w = (resources.displayMetrics.widthPixels * 0.90f).toInt()
            setLayout(w, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(android.view.Gravity.CENTER)
        }

        dialogBinding.tvToolbarTitle.text = getString(R.string.sticker_pack_title_detail)
        dialogBinding.tvPackTitle.text = pack.title
        dialogBinding.tvPackCount.text = resources.getQuantityString(
            R.plurals.sticker_count, pack.stickers.size, pack.stickers.size
        )

        // Превью пака
        val thumbSticker = pack.stickers.firstOrNull { it.localPath == pack.thumbPath } ?: pack.stickers.firstOrNull()
        if (thumbSticker != null) {
            val thumbPx = (64 * resources.displayMetrics.density).toInt()
            scope.launch {
                val bmp = repository.renderFirstFrame(thumbSticker, maxSize = thumbPx)
                if (bmp != null) dialogBinding.ivPackThumb.setImageBitmap(bmp)
            }
        }

        // Сетка стикеров (показываем первые 12 по умолчанию)
        val initialCount = 12
        val threshold = 4 // Показываем кнопку "Все", только если скрыто больше 4
        var showingAll = pack.stickers.size <= (initialCount + threshold)
        
        fun updateAdapter() {
            val list = if (showingAll) pack.stickers else pack.stickers.take(initialCount)
            val adapter = StickerAdapter(list, onStickerClick = {})
            adapter.onStickerLongClick = { sticker ->
                showStickerContextMenu(sticker, pack, adapter, dialog)
            }
            dialogBinding.rvStickers.adapter = adapter
            
            // Ограничиваем высоту скролла, чтобы диалог не уходил за экран
            dialogBinding.stickerScrollContainer.post {
                if (dialog.isShowing) {
                    val maxH = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                    if (dialogBinding.stickerScrollContainer.measuredHeight > maxH) {
                        dialogBinding.stickerScrollContainer.layoutParams.height = maxH
                        dialogBinding.stickerScrollContainer.requestLayout()
                    }
                }
            }
        }

        dialogBinding.rvStickers.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 4)
        updateAdapter()

        if (!showingAll) {
            dialogBinding.btnShowAll.visibility = View.VISIBLE
            dialogBinding.btnShowAll.text = getString(R.string.sticker_show_all_format, pack.stickers.size)
            dialogBinding.btnShowAll.setOnClickListener {
                showingAll = true
                dialogBinding.btnShowAll.visibility = View.GONE
                updateAdapter()
            }
        }

        dialogBinding.btnBack.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnDeletePack.setOnClickListener {
            dialog.dismiss()
            confirmDelete(pack)
        }

        dialog.show()
    }

    private fun showStickerContextMenu(sticker: Sticker, pack: StickerPack, adapter: StickerAdapter, parentDialog: android.app.Dialog) {
        scope.launch {
            val isFav = withContext(Dispatchers.IO) { repository.isFavorite(sticker.fileId) }
            val items = mutableListOf<NeonDialog.Item>()
            
            items.add(NeonDialog.Item(
                label = if (isFav) getString(R.string.sticker_remove_favorite) else getString(R.string.sticker_add_favorite),
                action = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (isFav) repository.removeFromFavorites(sticker.fileId)
                            else repository.addToFavorites(sticker)
                        }
                        Toast.makeText(this@StickerSettingsActivity, R.string.sticker_fav_updated, Toast.LENGTH_SHORT).show()
                    }
                }
            ))

            items.add(NeonDialog.Item(
                label = getString(R.string.sticker_delete_action),
                action = {
                    com.atrum.chat.NeonDialog.showConfirm(
                        ctx = this@StickerSettingsActivity,
                        title = getString(R.string.sticker_delete_confirm),
                        positiveText = getString(R.string.sticker_settings_delete_confirm),
                        positiveIsDestructive = true,
                        negativeText = getString(R.string.btn_cancel),
                        onPositive = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        repository.deleteSticker(sticker, pack.name)
                                    }
                                    val updatedPack = repository.loadLocalPacks().find { it.name == pack.name }
                                    if (updatedPack != null) {
                                        adapter.update(updatedPack.stickers)
                                    } else {
                                        parentDialog.dismiss()
                                    }
                                    loadPacks() // Update the list in the activity as well
                                } catch (e: Exception) {
                                    Toast.makeText(this@StickerSettingsActivity, R.string.sticker_error_network, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            ))

            NeonDialog.showMenu(this@StickerSettingsActivity, getString(R.string.sticker_menu_title), items)
        }
    }

    private fun showRenameDialog(pack: StickerPack) {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.sticker_rename_title),
            initialText = pack.title,
            positiveText = getString(R.string.sticker_rename_save),
            negativeText = getString(R.string.sticker_add_pack_cancel),
            subtitle = getString(R.string.sticker_rename_hint),
            validator = { it.isNotBlank() && !ZalgoFilter.containsZalgo(it) }
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
                try {
                    withContext(Dispatchers.IO) { repository.removePack(pack.name) }
                    loadPacks()
                    Toast.makeText(
                        this@StickerSettingsActivity,
                        getString(R.string.sticker_settings_deleted),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(this@StickerSettingsActivity, R.string.sticker_error_network, Toast.LENGTH_SHORT).show()
                }
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
        binding.networkWarningPanel.visibility = View.GONE
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
                binding.networkWarningPanel.visibility = View.VISIBLE
            }
        }
    }

    /** Открывает @BotFather в Telegram. */
    private fun openBotFather() {
        try {
            AppLock.beginShareGrace()
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("tg://resolve?domain=BotFather")
                )
            )
        } catch (_: Exception) {
            try {
                AppLock.beginShareGrace()
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
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }
}
