package com.atrum.chat.stickers

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
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
import java.io.FileInputStream
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

        // Сброс и остановка старой анимации
        binding.ivSticker.cancelAnimation()
        binding.ivSticker.setImageDrawable(null)
        binding.ivSticker.tag = path
        binding.tvAnimBadge.visibility = View.GONE

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
                binding.tvAnimBadge.visibility = View.VISIBLE
                scope.launch {
                    val comp = withContext(Dispatchers.IO) {
                        try {
                            val json = GZIPInputStream(FileInputStream(File(path)))
                                .bufferedReader().readText()
                            LottieCompositionFactory.fromJsonStringSync(json, path)?.value
                        } catch (_: Exception) { null }
                    }
                    if (binding.ivSticker.tag == path && comp != null) {
                        binding.ivSticker.setComposition(comp)
                        binding.ivSticker.repeatCount = LottieDrawable.INFINITE
                        binding.ivSticker.playAnimation()
                    }
                }
            }
            StickerType.VIDEO -> {
                binding.tvAnimBadge.visibility = View.VISIBLE
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
