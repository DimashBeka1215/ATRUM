package com.atrum.chat.stickers

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
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
    private val onStickerClick: (Sticker) -> Unit
) : RecyclerView.Adapter<StickerAdapter.VH>() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

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
        val path = sticker.localPath ?: return

        // Очищаем вью перед загрузкой нового стикера, чтобы не было фантомных кадров
        binding.ivSticker.tag = path
        binding.ivSticker.cancelAnimation()
        binding.ivSticker.setImageDrawable(null)

        when (sticker.type) {
            StickerType.STATIC -> {
                scope.launch {
                    val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
                    if (binding.ivSticker.tag == path && bitmap != null) {
                        binding.ivSticker.setImageBitmap(bitmap)
                    }
                }
            }
            StickerType.ANIMATED -> {
                scope.launch {
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
                            
                            // Важно: cacheKey должен быть уникальным
                            LottieCompositionFactory.fromJsonStringSync(jsonString, path).value
                        } catch (e: Exception) {
                            android.util.Log.e("StickerAdapter", "TGS decode failed for $path", e)
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
            }
            StickerType.VIDEO -> {
                // .webm видео-стикеры: в пикере показываем первый кадр (ImageView не умеет видео).
                // Полноценное проигрывание .webm требует TextureView — отдельная задача.
                scope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(path)
                                retriever.getFrameAtTime(0)
                            } finally { retriever.release() }
                        } catch (_: Exception) { null }
                    }
                    if (binding.ivSticker.tag == path && bitmap != null) {
                        binding.ivSticker.setImageBitmap(bitmap)
                    }
                }
            }
        }

        binding.root.setOnClickListener { onStickerClick(sticker) }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.binding.ivSticker.cancelAnimation()
        holder.binding.ivSticker.setImageDrawable(null)
        holder.binding.ivSticker.tag = null
    }

    fun update(newStickers: List<Sticker>) {
        stickers = newStickers
        notifyDataSetChanged()
    }
}
