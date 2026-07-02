package com.atrum.chat

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Своя галерея выбора фото (bottom sheet) вместо системного окна. Читает изображения из
 * MediaStore, показывает их сеткой 3-в-ряд с мультивыбором и порядковыми номерами. По
 * кнопке «Отправить» возвращает выбранные [Uri] через [onDone] — дальше они уходят в
 * полосу-стейджинг перед фактической отправкой.
 *
 * Альбомы: тап по заголовку открывает компактный список альбомов устройства (обложка,
 * имя, счётчик). «Недавние» = все фото. Выбор фото СОХРАНЯЕТСЯ при переключении альбомов.
 *
 * Приватность: работает и при частичном доступе (Android 14+) — MediaStore тогда отдаёт
 * только разрешённые фото. Кнопка «выбрать ещё» ([onPickMore]) показывается в этом режиме.
 */
class GalleryPicker(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val maxSelection: Int,
    private val onDone: (List<Uri>) -> Unit,
    private val onPickMore: (() -> Unit)? = null
) {

    private val selected = ArrayList<Uri>()
    private var photos: List<Uri> = emptyList()
    private var albums: List<Album> = emptyList()
    private var currentBucketId: String? = null
    private var albumsOpen = false

    private lateinit var dialog: BottomSheetDialog
    private lateinit var adapter: Adapter
    private lateinit var grid: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var sendBar: View
    private lateinit var sendText: TextView
    private lateinit var titleText: TextView
    private lateinit var chevron: View
    private lateinit var scrim: View
    private lateinit var albumsScroll: MaxHeightScrollView
    private lateinit var albumsBox: LinearLayout

    /** Альбом устройства. [id] = null → «Недавние» (все фото). */
    private class Album(val id: String?, val name: String, val count: Int, val cover: Uri?)

    /** Показывает шторку. [showPickMore] = true при частичном доступе к галерее. */
    fun show(showPickMore: Boolean) {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_gallery, null)
        dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)

        // Шторка ~55% высоты экрана — чат остаётся виден сверху, не занимает пол-экрана лишнего.
        (view.parent as? View)?.let { bs ->
            val sheetH = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
            bs.layoutParams = bs.layoutParams.apply { height = sheetH }
            // Прозрачность контейнера на некоторых прошивках не срабатывает и по краям
            // торчат серые углы. Поэтому ЗАЛИВАЕМ контейнер цветом поверхности — тем же,
            // что фон самой шторки (bg_sheet_top). Серого больше нет.
            bs.setBackgroundColor(androidx.core.content.ContextCompat.getColor(activity, R.color.surface))
            BottomSheetBehavior.from(bs).apply {
                peekHeight = sheetH
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }

        grid = view.findViewById(R.id.gallery_grid)
        emptyText = view.findViewById(R.id.gallery_empty)
        sendBar = view.findViewById(R.id.gallery_send_bar)
        sendText = view.findViewById(R.id.gallery_send_text)
        titleText = view.findViewById(R.id.gallery_title)
        chevron = view.findViewById(R.id.gallery_chevron)
        scrim = view.findViewById(R.id.gallery_scrim)
        albumsScroll = view.findViewById(R.id.gallery_albums_scroll)
        albumsBox = view.findViewById(R.id.gallery_albums)
        albumsScroll.maxHeightPx = (activity.resources.displayMetrics.density * 260).toInt()
        val pickMore = view.findViewById<TextView>(R.id.gallery_pick_more)

        view.findViewById<View>(R.id.gallery_close).setOnClickListener { dialog.dismiss() }
        pickMore.visibility = if (showPickMore) View.VISIBLE else View.GONE
        pickMore.setOnClickListener {
            dialog.dismiss()
            onPickMore?.invoke()
        }
        view.findViewById<View>(R.id.gallery_send_btn).setOnClickListener {
            if (selected.isNotEmpty()) {
                val out = selected.toList()
                dialog.dismiss()
                onDone(out)
            }
        }
        view.findViewById<View>(R.id.gallery_title_row).setOnClickListener { toggleAlbums() }
        scrim.setOnClickListener { closeAlbums() }

        adapter = Adapter()
        grid.layoutManager = GridLayoutManager(activity, SPAN)
        // Без аниматора: при отметке/снятии фото перерисовка номеров не даёт мелькания.
        grid.itemAnimator = null
        grid.adapter = adapter

        updateBar()
        dialog.show()

        loadImages(null)   // старт — «Недавние» (все фото)
        loadAlbums()       // список альбомов подтягиваем в фоне
    }

    // ── Загрузка данных ──────────────────────────────────────────────────────

    private fun loadImages(bucketId: String?) {
        scope.launch {
            val list = withContext(Dispatchers.IO) { queryImages(activity, bucketId) }
            photos = list
            emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    private fun loadAlbums() {
        scope.launch {
            albums = withContext(Dispatchers.IO) { queryAlbums(activity) }
        }
    }

    // ── Выпадающий список альбомов ───────────────────────────────────────────

    private fun toggleAlbums() {
        if (albumsOpen) closeAlbums() else openAlbums()
    }

    private fun openAlbums() {
        if (albums.isEmpty()) return
        buildAlbumRows()
        albumsOpen = true
        scrim.visibility = View.VISIBLE
        albumsScroll.visibility = View.VISIBLE
        chevron.rotation = 180f
    }

    private fun closeAlbums() {
        albumsOpen = false
        scrim.visibility = View.GONE
        albumsScroll.visibility = View.GONE
        chevron.rotation = 0f
    }

    private fun buildAlbumRows() {
        albumsBox.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        albums.forEach { album ->
            val row = inflater.inflate(R.layout.item_album, albumsBox, false)
            val cover = row.findViewById<ImageView>(R.id.album_cover)
            val name = row.findViewById<TextView>(R.id.album_name)
            val count = row.findViewById<TextView>(R.id.album_count)
            val check = row.findViewById<View>(R.id.album_check)
            name.text = album.name
            count.text = album.count.toString()
            check.visibility = if (album.id == currentBucketId) View.VISIBLE else View.GONE
            album.cover?.let { loadCover(it, cover) }
            row.setOnClickListener { selectAlbum(album) }
            albumsBox.addView(row)
        }
    }

    private fun selectAlbum(album: Album) {
        currentBucketId = album.id
        titleText.text = if (album.id == null) activity.getString(R.string.gallery_title) else album.name
        closeAlbums()
        loadImages(album.id)   // выбор фото (selected) при этом сохраняется
    }

    // ── Панель отправки ──────────────────────────────────────────────────────

    private fun updateBar() {
        if (selected.isEmpty()) {
            sendBar.visibility = View.GONE
        } else {
            sendBar.visibility = View.VISIBLE
            sendText.text = activity.getString(R.string.gallery_send_n, selected.size)
        }
    }

    // ── Adapter сетки ────────────────────────────────────────────────────────

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_photo, parent, false)
            // Размер ячейки: ширину задаёт GridLayoutManager по колонке, высоту (квадрат) —
            // SquareFrameLayout. Ручное вычисление убрано — оно ломало замощение (серые зазоры).
            return VH(v)
        }

        override fun getItemCount(): Int = photos.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(photos[position])
        }
    }

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val img: ImageView = v.findViewById(R.id.gp_img)
        private val ring: View = v.findViewById(R.id.gp_ring)
        private val badge: TextView = v.findViewById(R.id.gp_badge)

        fun bind(uri: Uri) {
            img.setImageDrawable(null)
            img.tag = uri
            loadThumb(uri, img)
            applySelection(uri)
            itemView.setOnClickListener {
                val idx = selected.indexOf(uri)
                if (idx >= 0) {
                    selected.removeAt(idx)
                } else {
                    if (selected.size >= maxSelection) {
                        Toast.makeText(activity,
                            activity.getString(R.string.error_too_many_images, maxSelection),
                            Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    selected.add(uri)
                }
                // Номера могли сдвинуться — перерисовываем видимые ячейки.
                adapter.notifyDataSetChanged()
                updateBar()
            }
        }

        private fun applySelection(uri: Uri) {
            val pos = selected.indexOf(uri)
            if (pos >= 0) {
                ring.visibility = View.VISIBLE
                badge.setBackgroundResource(R.drawable.bg_gallery_badge_on)
                badge.text = (pos + 1).toString()
            } else {
                ring.visibility = View.GONE
                badge.setBackgroundResource(R.drawable.bg_gallery_badge_off)
                badge.text = ""
            }
        }
    }

    private fun loadThumb(uri: Uri, target: ImageView) {
        thumbCache.get(uri.toString())?.let { target.setImageBitmap(it); return }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeThumb(activity, uri) }
            if (bmp != null) {
                thumbCache.put(uri.toString(), bmp)
                if (target.tag == uri) target.setImageBitmap(bmp)
            }
        }
    }

    private fun loadCover(uri: Uri, target: ImageView) {
        thumbCache.get(uri.toString())?.let { target.setImageBitmap(it); return }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeThumb(activity, uri) }
            if (bmp != null) {
                thumbCache.put(uri.toString(), bmp)
                target.setImageBitmap(bmp)
            }
        }
    }

    companion object {
        private const val SPAN = 3
        private const val THUMB_PX = 220

        /** In-memory LRU миниатюр (общий на сеанс) — плавный скролл сетки и обложки альбомов. */
        private val thumbCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        /** Изображения устройства; [bucketId] = null → все, иначе только из этого альбома. */
        private fun queryImages(ctx: Context, bucketId: String?): List<Uri> {
            val out = ArrayList<Uri>()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(MediaStore.Images.Media._ID)
            val sort = MediaStore.Images.Media.DATE_ADDED + " DESC"
            val sel = if (bucketId == null) null else MediaStore.Images.Media.BUCKET_ID + " = ?"
            val args = if (bucketId == null) null else arrayOf(bucketId)
            try {
                ctx.contentResolver.query(collection, proj, sel, args, sort)?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (c.moveToNext()) {
                        out.add(ContentUris.withAppendedId(collection, c.getLong(idIdx)))
                    }
                }
            } catch (_: Exception) {}
            return out
        }

        /** Список альбомов (buckets) с именем, счётчиком и обложкой (самое свежее фото). */
        private fun queryAlbums(ctx: Context): List<Album> {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val sort = MediaStore.Images.Media.DATE_ADDED + " DESC"
            var total = 0
            var recentCover: Uri? = null
            val order = ArrayList<String>()
            val names = HashMap<String, String>()
            val counts = HashMap<String, Int>()
            val covers = HashMap<String, Uri>()
            try {
                ctx.contentResolver.query(collection, proj, null, null, sort)?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val bIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val nIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(idIdx))
                        if (recentCover == null) recentCover = uri
                        total++
                        val bId = c.getString(bIdx) ?: continue
                        if (!counts.containsKey(bId)) {
                            order.add(bId)
                            names[bId] = c.getString(nIdx) ?: "—"
                            covers[bId] = uri
                            counts[bId] = 1
                        } else {
                            counts[bId] = (counts[bId] ?: 0) + 1
                        }
                    }
                }
            } catch (_: Exception) {}
            val out = ArrayList<Album>()
            out.add(Album(null, ctx.getString(R.string.gallery_title), total, recentCover))
            order.forEach { bId ->
                out.add(Album(bId, names[bId] ?: "—", counts[bId] ?: 0, covers[bId]))
            }
            return out
        }

        /** Декод миниатюры из Uri с downsample под размер ячейки (защита от OOM). */
        private fun decodeThumb(ctx: Context, uri: Uri): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > THUMB_PX * 2 || bounds.outHeight / sample > THUMB_PX * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }
}
