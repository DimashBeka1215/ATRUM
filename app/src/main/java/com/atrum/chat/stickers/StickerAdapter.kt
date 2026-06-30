package com.atrum.chat.stickers

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.atrum.chat.databinding.ItemStickerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

class StickerAdapter(
    private var stickers: List<Sticker>,
    private val onStickerClick: (Sticker) -> Unit,
    var onStickerLongClick: ((Sticker) -> Unit)? = null
) : RecyclerView.Adapter<StickerAdapter.VH>() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val loadingJobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()

    inner class VH(val binding: ItemStickerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemStickerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount() = stickers.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val sticker = stickers[position]
        val binding = holder.binding
        val path = sticker.localPath

        // Сброс и настройка слушателей в самом начале (всегда!)
        binding.stickerForeground.setOnClickListener { onStickerClick(sticker) }
        binding.stickerForeground.setOnLongClickListener {
            onStickerLongClick?.invoke(sticker)
            onStickerLongClick != null
        }

        binding.ivSticker.tag = path
        binding.ivSticker.cancelAnimation()
        binding.ivSticker.setImageDrawable(null)
        binding.ivSticker.visibility = View.VISIBLE
        binding.webmSticker.visibility = View.GONE
        binding.webmSticker.release()

        loadingJobs[holder.bindingAdapterPosition]?.cancel()

        if (path == null) return

        val job = scope.launch {
            when (sticker.type) {
                StickerType.STATIC -> {
                    val cached = com.atrum.chat.ImageCache.getBitmap(path)
                    if (binding.ivSticker.tag == path && cached != null) {
                        binding.ivSticker.setImageBitmap(cached)
                        return@launch
                    }
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            BitmapFactory.decodeFile(path)
                        } catch (e: Exception) { null }
                    }
                    if (binding.ivSticker.tag == path && bitmap != null) {
                        com.atrum.chat.ImageCache.putBitmap(path, bitmap)
                        binding.ivSticker.setImageBitmap(bitmap)
                    }
                }
                StickerType.ANIMATED -> {
                    val cached = com.atrum.chat.ImageCache.getComposition(path)
                    if (cached != null) {
                        if (binding.ivSticker.tag == path) {
                            binding.ivSticker.setComposition(cached)
                            binding.ivSticker.repeatCount = LottieDrawable.INFINITE
                            binding.ivSticker.setRenderMode(com.airbnb.lottie.RenderMode.HARDWARE)
                            binding.ivSticker.playAnimation()
                        }
                        return@launch
                    }

                    val comp = withContext(Dispatchers.IO) {
                        try {
                            val file = File(path)
                            if (!file.exists()) return@withContext null
                            
                            val bytes = file.readBytes()
                            val gzis = GZIPInputStream(java.io.ByteArrayInputStream(bytes))
                            val jsonString = gzis.bufferedReader().use { it.readText() }
                            
                            LottieCompositionFactory.fromJsonStringSync(jsonString, path).value
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (binding.ivSticker.tag == path && comp != null) {
                        com.atrum.chat.ImageCache.putComposition(path, comp)
                        binding.ivSticker.setComposition(comp)
                        binding.ivSticker.repeatCount = LottieDrawable.INFINITE
                        binding.ivSticker.setRenderMode(com.airbnb.lottie.RenderMode.HARDWARE)
                        binding.ivSticker.playAnimation()
                    }
                }
                StickerType.VIDEO -> {
                    binding.ivSticker.visibility = View.GONE
                    binding.ivSticker.setImageDrawable(null)
                    binding.webmSticker.visibility = View.VISIBLE
                    binding.webmSticker.tag = path
                    binding.webmSticker.play(File(path), path)
                }
            }
        }
        loadingJobs[holder.bindingAdapterPosition] = job
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        loadingJobs[holder.bindingAdapterPosition]?.cancel()
        loadingJobs.remove(holder.bindingAdapterPosition)
        holder.binding.ivSticker.cancelAnimation()
        holder.binding.ivSticker.setImageDrawable(null)
        holder.binding.ivSticker.tag = null
        holder.binding.webmSticker.tag = null
        holder.binding.webmSticker.release()
    }

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        // Вернулся в кадр — бесшовно продолжаем с того же места.
        if (holder.binding.webmSticker.visibility == View.VISIBLE) holder.binding.webmSticker.resume()
        holder.binding.ivSticker.resumeAnimation()
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        super.onViewDetachedFromWindow(holder)
        // Ушёл за кадр — ставим анимацию на паузу без сброса (плавная прокрутка, экономия CPU).
        holder.binding.webmSticker.pause()
        holder.binding.ivSticker.pauseAnimation()
    }

    fun update(newStickers: List<Sticker>) {
        val callback = object : DiffUtil.Callback() {
            override fun getOldListSize() = stickers.size
            override fun getNewListSize() = newStickers.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = stickers[oldPos].fileId == newStickers[newPos].fileId
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = stickers[oldPos] == newStickers[newPos]
        }
        val result = DiffUtil.calculateDiff(callback)
        stickers = newStickers
        result.dispatchUpdatesTo(this)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        // Отменяем корутины загрузки превью, иначе scope живёт и держит ссылки на view.
        scope.coroutineContext[Job]?.cancel()
    }
}
