package com.atrum.chat.stickers

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.atrum.chat.NeonDialog
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
    private var currentPackIndex = 0
    private var stickerAdapter: StickerAdapter? = null

    private var loadJob: Job? = null
    private var isPanelVisible = false

    // ── Инициализация ────────────────────────────────────────────────────────

    fun init() {
        // 1. Инфлейтим панель в контейнер
        val inflater = LayoutInflater.from(context)
        panelBinding = ViewStickerPanelBinding.inflate(inflater, chatBinding.stickerPanelContainer, true)

        // 2. Настраиваем RecyclerView
        stickerAdapter = StickerAdapter(emptyList()) { sticker ->
            onStickerSelected(sticker)
            hidePanel()
        }
        panelBinding?.rvStickers?.apply {
            layoutManager = GridLayoutManager(context, 5)
            adapter = stickerAdapter
        }

        // 3. Кнопка добавить пак
        panelBinding?.btnStickerAddPack?.setOnClickListener { showAddPackDialog() }

        // 4. Кнопка помощи — повторный онбординг
        panelBinding?.btnStickerHelp?.setOnClickListener { showOnboarding() }

        // 5. Кнопка стикера в inputbar
        chatBinding.btnSticker.setOnClickListener { togglePanel() }

        // 6. Загружаем локальные паки
        loadLocalPacks()
    }

    fun destroy() {
        loadJob?.cancel()
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

        if (packs.isEmpty()) {
            binding.rvStickers.visibility       = View.GONE
            binding.stickerEmptyState.visibility = View.VISIBLE
            binding.stickerLoadingState.visibility = View.GONE
            rebuildTabs()
            return
        }

        binding.stickerEmptyState.visibility = View.GONE
        binding.stickerLoadingState.visibility = View.GONE
        binding.rvStickers.visibility = View.VISIBLE

        rebuildTabs()
        showPack(currentPackIndex.coerceIn(0, packs.lastIndex))
    }

    private fun rebuildTabs() {
        val binding = panelBinding ?: return
        binding.stickerTabsContainer.removeAllViews()

        packs.forEachIndexed { index, pack ->
            val tab = makeTabView(pack, index)
            binding.stickerTabsContainer.addView(tab)
        }
        updateTabSelection()
    }

    private fun makeTabView(pack: StickerPack, index: Int): View {
        val size  = (40 * context.resources.displayMetrics.density).toInt()
        val pad   = (8 * context.resources.displayMetrics.density).toInt()

        val btn = ImageButton(context).apply {
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
                val bmp = repository.renderFirstFrame(thumbSticker)
                if (bmp != null) {
                    btn.setImageBitmap(bmp)
                    btn.imageTintList = null
                }
            }
        }

        btn.setOnClickListener {
            currentPackIndex = index
            showPack(index)
            updateTabSelection()
        }
        return btn
    }

    private fun updateTabSelection() {
        val binding = panelBinding ?: return
        val container = binding.stickerTabsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val isSelected = (i == currentPackIndex)
            child.alpha = if (isSelected) 1f else 0.45f
        }
    }

    private fun showPack(index: Int) {
        if (index < 0 || index >= packs.size) return
        val pack = packs[index]
        panelBinding?.tvStickerPackLabel?.text = pack.title
        stickerAdapter?.update(pack.stickers)
    }

    // ── Добавление пака ──────────────────────────────────────────────────────

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

        // Показываем загрузку
        binding.rvStickers.visibility         = View.GONE
        binding.stickerEmptyState.visibility   = View.GONE
        binding.stickerLoadingState.visibility = View.VISIBLE

        scope.launch {
            try {
                val pack = repository.addPack(input)
                withContext(Dispatchers.Main) {
                    packs = packs + pack
                    currentPackIndex = packs.lastIndex
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
