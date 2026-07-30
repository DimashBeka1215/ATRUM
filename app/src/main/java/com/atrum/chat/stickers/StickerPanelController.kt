package com.atrum.chat.stickers

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.atrum.chat.NeonDialog
import com.atrum.chat.ZalgoFilter
import com.atrum.chat.Prefs
import com.atrum.chat.R
import com.atrum.chat.databinding.ActivityChatBinding
import com.atrum.chat.databinding.ViewStickerOnboardingBinding
import com.atrum.chat.databinding.ViewStickerPanelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Управляет всей логикой панели стикеров в ChatActivity.
 *
 * Использование:
 *   val controller = StickerPanelController(context, binding, prefs, lifecycleScope)
 *   controller.init()
 *   // В onDestroy:
 *   controller.destroy()
 *
 * ChatActivity не знает про Repository, Adapter, онбординг — всё инкапсулировано здесь.
 */
class StickerPanelController(
    private val context: Context,
    private val chatBinding: ActivityChatBinding,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val onStickerSelected: (Sticker) -> Unit
) {

    private val repository  = StickerRepository(context)
    private var panelBinding: ViewStickerPanelBinding? = null
    private var onboardingBinding: ViewStickerOnboardingBinding? = null

    private var packs: List<StickerPack> = emptyList()
    private var currentTabId: String = TAB_FAVORITES
    private var stickerAdapter: StickerAdapter? = null

    private var loadJob: Job? = null
    private var isPanelVisible = false

    companion object {
        private const val TAB_FAVORITES = "favorites_tab"
    }

    // ── Инициализация ────────────────────────────────────────────────────────

    fun init() {
        // 1. Инфлейтим панель в контейнер
        val inflater = LayoutInflater.from(context)
        panelBinding = ViewStickerPanelBinding.inflate(inflater, chatBinding.stickerPanelContainer, true)

        // 2. Настраиваем RecyclerView
        stickerAdapter = StickerAdapter(
            stickers = emptyList(),
            onStickerClick = { sticker ->
                scope.launch { repository.recordUsage(sticker) }
                onStickerSelected(sticker)
                // hidePanel() // Убираем авто-закрытие, теперь ChatActivity управляет этим
            },
            onStickerLongClick = { sticker ->
                showStickerContextMenu(sticker)
            }
        )
        panelBinding?.rvStickers?.apply {
            layoutManager = GridLayoutManager(context, 5)
            adapter = stickerAdapter
        }

        // 3. Кнопка добавить пак
        panelBinding?.btnStickerAddPack?.setOnClickListener { showAddPackDialog() }

        // 4. Кнопка помощи — повторный онбординг
        panelBinding?.btnStickerHelp?.setOnClickListener { showOnboarding() }

        // 4.1 Карандаш справа от названия — переименовать текущий пак
        panelBinding?.btnStickerRenamePack?.setOnClickListener { showRenamePackDialog() }

        // 5. Кнопка стикера в inputbar
        chatBinding.btnSticker.setOnClickListener { togglePanel() }

        // 6. Загружаем локальные паки
        scope.launch { repository.loadFavorites() }
        loadLocalPacks()
    }

    fun destroy() {
        loadJob?.cancel()
        // Отвязываем адаптер — холдеры ресайклятся, любые ресурсы стикеров освобождаются.
        try { panelBinding?.rvStickers?.adapter = null } catch (_: Exception) {}
        stickerAdapter = null
    }

    // ── Видимость панели ─────────────────────────────────────────────────────

    fun togglePanel() {
        if (isPanelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        chatBinding.stickerPanelContainer.visibility = View.VISIBLE
        isPanelVisible = true

        // Первый запуск — показать онбординг
        if (!prefs.stickerOnboardingShown) {
            showOnboarding()
        }
    }

    fun setSendingState(isSending: Boolean) {
        panelBinding?.stickerSendingOverlay?.visibility = if (isSending) View.VISIBLE else View.GONE
    }

    fun hidePanel() {
        chatBinding.stickerPanelContainer.visibility = View.GONE
        isPanelVisible = false
    }

    // ── Онбординг ────────────────────────────────────────────────────────────

    private fun showOnboarding() {
        // Добавляем оверлей поверх root FrameLayout (activity_chat.xml)
        val rootFrame = chatBinding.root as? ViewGroup ?: return
        if (onboardingBinding != null) return // уже показан

        val inflater = LayoutInflater.from(context)
        onboardingBinding = ViewStickerOnboardingBinding.inflate(inflater, rootFrame, true)

        onboardingBinding?.btnStickerOnboardingOk?.setOnClickListener {
            dismissOnboarding()
        }
        // Тап по оверлею вне карточки тоже закрывает
        onboardingBinding?.root?.setOnClickListener {
            dismissOnboarding()
        }
    }

    private fun dismissOnboarding() {
        val rootFrame = chatBinding.root as? ViewGroup ?: return
        onboardingBinding?.let { binding ->
            rootFrame.removeView(binding.root)
        }
        onboardingBinding = null
        prefs.stickerOnboardingShown = true
    }

    // ── Загрузка паков ───────────────────────────────────────────────────────

    private fun loadLocalPacks() {
        loadJob = scope.launch {
            val loaded = repository.loadLocalPacks()
            withContext(Dispatchers.Main) {
                packs = loaded
                refreshUI()
            }
        }
    }

    private fun refreshUI() {
        val binding = panelBinding ?: return

        // Если паков нет ВООБЩЕ, но могут быть избранные — всё равно показываем панель,
        // но только с вкладкой избранного (которая может быть пуста).
        binding.stickerEmptyState.visibility = View.GONE
        binding.stickerLoadingState.visibility = View.GONE
        binding.rvStickers.visibility = View.VISIBLE

        rebuildTabs()
        showTab(currentTabId)
    }

    private fun rebuildTabs() {
        val binding = panelBinding ?: return
        binding.stickerTabsContainer.removeAllViews()

        // 1. Вкладка "Избранное"
        val favTab = makeFavTabView()
        binding.stickerTabsContainer.addView(favTab)

        // 2. Вкладки паков
        packs.forEach { pack ->
            val tab = makePackTabView(pack)
            binding.stickerTabsContainer.addView(tab)
        }
        updateTabSelection()
    }

    private fun makeFavTabView(): View {
        val size = (40 * context.resources.displayMetrics.density).toInt()
        val pad = (10 * context.resources.displayMetrics.density).toInt()

        return ImageButton(context).apply {
            tag = TAB_FAVORITES
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_icon_button)
            contentDescription = context.getString(R.string.sticker_menu_title)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.ic_heart)
            tint(ContextCompat.getColor(context, R.color.accent))

            setOnClickListener {
                currentTabId = TAB_FAVORITES
                showTab(currentTabId)
                updateTabSelection()
            }
        }
    }

    private fun makePackTabView(pack: StickerPack): View {
        val size  = (40 * context.resources.displayMetrics.density).toInt()
        val pad   = (8 * context.resources.displayMetrics.density).toInt()

        val btn = ImageButton(context).apply {
            tag = pack.name
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(context, R.drawable.bg_icon_button)
            contentDescription = pack.title
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }

        btn.setImageResource(R.drawable.ic_image_outline)
        btn.tint(ContextCompat.getColor(context, R.color.text_secondary))

        // Используем первый стикер как превью, если thumbPath не задан или не грузится
        val thumbSticker = pack.stickers.firstOrNull { it.localPath == pack.thumbPath } ?: pack.stickers.firstOrNull()
        if (thumbSticker != null) {
            scope.launch {
                val bmp = repository.renderFirstFrame(thumbSticker, maxSize = size)
                if (bmp != null) {
                    btn.setImageBitmap(bmp)
                    btn.imageTintList = null
                }
            }
        }

        btn.setOnClickListener {
            currentTabId = pack.name
            showTab(currentTabId)
            updateTabSelection()
        }
        return btn
    }

    private fun updateTabSelection() {
        val binding = panelBinding ?: return
        val container = binding.stickerTabsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val isSelected = (child.tag == currentTabId)
            child.alpha = if (isSelected) 1f else 0.45f
        }
    }

    private fun showTab(tabId: String) {
        if (tabId == TAB_FAVORITES) {
            panelBinding?.tvStickerPackLabel?.text = context.getString(R.string.sticker_menu_title)
            panelBinding?.btnStickerRenamePack?.visibility = View.GONE

            // Step 1: Try to show cached data instantly
            repository.getFavoritesCached()?.let { cached ->
                stickerAdapter?.update(cached)
                updateEmptyState(cached.isEmpty())
            }

            // Step 2: Refresh from disk if needed (non-blocking)
            scope.launch {
                val favs = repository.loadFavorites()
                withContext(Dispatchers.Main) {
                    stickerAdapter?.update(favs)
                    updateEmptyState(favs.isEmpty())
                }
            }
        } else {
            val pack = packs.find { it.name == tabId } ?: return
            panelBinding?.tvStickerPackLabel?.text = pack.title
            panelBinding?.btnStickerRenamePack?.visibility = View.VISIBLE
            panelBinding?.rvStickers?.visibility = View.VISIBLE
            panelBinding?.stickerEmptyState?.visibility = View.GONE
            stickerAdapter?.update(pack.stickers)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        panelBinding?.rvStickers?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        panelBinding?.stickerEmptyState?.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    // ── Контекстное меню стикера ─────────────────────────────────────────────

    private fun showStickerContextMenu(sticker: Sticker) {
        scope.launch {
            val isFav = repository.isFavorite(sticker.fileId)
            
            // Находим пак, которому принадлежит стикер (для удаления)
            val pack = packs.find { p -> p.stickers.any { it.fileId == sticker.fileId } }

            withContext(Dispatchers.Main) {
                val items = mutableListOf<NeonDialog.Item>()

                items.add(NeonDialog.Item(
                    label = if (isFav) context.getString(R.string.sticker_remove_favorite) else context.getString(R.string.sticker_add_favorite),
                    action = { toggleFavorite(sticker) }
                ))

                if (pack != null) {
                    items.add(NeonDialog.Item(
                        label = context.getString(R.string.sticker_delete_action),
                        action = {
                            com.atrum.chat.NeonDialog.showConfirm(
                                ctx = context,
                                title = context.getString(R.string.sticker_delete_confirm),
                                positiveText = context.getString(R.string.sticker_settings_delete_confirm),
                                positiveIsDestructive = true,
                                negativeText = context.getString(R.string.btn_cancel),
                                onPositive = {
                                    scope.launch {
                                        repository.deleteSticker(sticker, pack.name)
                                        loadLocalPacks()
                                    }
                                }
                            )
                        }
                    ))
                }

                NeonDialog.showMenu(
                    ctx = context,
                    title = context.getString(R.string.sticker_menu_title),
                    items = items
                )
            }
        }
    }

    private fun toggleFavorite(sticker: Sticker) {
        scope.launch {
            val isFav = repository.isFavorite(sticker.fileId)
            if (isFav) {
                repository.removeFromFavorites(sticker.fileId)
            } else {
                repository.addToFavorites(sticker)
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(context, R.string.sticker_fav_updated, android.widget.Toast.LENGTH_SHORT).show()
                if (currentTabId == TAB_FAVORITES) {
                    showTab(TAB_FAVORITES) // Обновляем список, если мы на вкладке избранного
                }
            }
        }
    }

    // ── Добавление пака ──────────────────────────────────────────────────────

    /** Переименование текущего пака (карандаш у названия в панели). */
    private fun showRenamePackDialog() {
        val pack = packs.find { it.name == currentTabId } ?: return
        NeonDialog.showEdit(
            ctx = context,
            title = context.getString(R.string.sticker_rename_title),
            initialText = pack.title,
            positiveText = context.getString(R.string.sticker_rename_save),
            negativeText = context.getString(R.string.sticker_add_pack_cancel),
            subtitle = context.getString(R.string.sticker_rename_hint),
            validator = { it.isNotBlank() && !ZalgoFilter.containsZalgo(it) }
        ) { newTitle ->
            val clean = newTitle.trim()
            if (clean.isNotBlank() && clean != pack.title) {
                scope.launch {
                    withContext(Dispatchers.IO) { repository.renamePack(pack.name, clean) }
                    // Обновляем in-memory и подпись на месте — без перезагрузки и смены порядка табов.
                    packs = packs.map { if (it.name == pack.name) it.copy(title = clean) else it }
                    panelBinding?.tvStickerPackLabel?.text = clean
                }
            }
        }
    }

    private fun showAddPackDialog() {
        NeonDialog.showEdit(
            ctx = context,
            title = context.getString(R.string.sticker_add_pack_title),
            initialText = "",
            positiveText = context.getString(R.string.sticker_add_pack_btn),
            negativeText = context.getString(R.string.sticker_add_pack_cancel),
            subtitle = context.getString(R.string.sticker_add_pack_hint)
        ) { input ->
            if (input.isNotBlank()) downloadAndAddPack(input)
        }
    }

    private fun downloadAndAddPack(input: String) {
        val binding = panelBinding ?: return

        // Показываем загрузку с определённой полосой прогресса (как в настройках)
        binding.rvStickers.visibility         = View.GONE
        binding.stickerEmptyState.visibility   = View.GONE
        binding.stickerLoadingState.visibility = View.VISIBLE
        binding.pbStickerDownload.max = 100
        binding.pbStickerDownload.progress = 0
        binding.tvStickerLoadingCount.text = ""

        scope.launch {
            try {
                val pack = repository.addPack(input) { downloaded, total ->
                    // onProgress зовётся из IO — обновляем UI на главном потоке через post.
                    val b = panelBinding ?: return@addPack
                    b.pbStickerDownload.post {
                        b.pbStickerDownload.max = total
                        b.pbStickerDownload.progress = downloaded
                        b.tvStickerLoadingCount.text =
                            context.getString(R.string.sticker_loading_count, downloaded, total)
                    }
                }
                withContext(Dispatchers.Main) {
                    packs = packs + pack
                    currentTabId = pack.name
                    refreshUI()
                }
            } catch (e: IllegalArgumentException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.sticker_error_invalid_link, Toast.LENGTH_SHORT).show()
                    refreshUI()
                }
            } catch (e: StickerException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "${context.getString(R.string.sticker_error_not_found)}: ${e.message}", Toast.LENGTH_LONG).show()
                    refreshUI()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.sticker_error_network, Toast.LENGTH_SHORT).show()
                    refreshUI()
                }
            }
        }
    }
}

// Хелпер: установить tint на ImageButton без app:tint из XML
private fun android.widget.ImageButton.tint(color: Int) {
    imageTintList = android.content.res.ColorStateList.valueOf(color)
}
