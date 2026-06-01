package com.atrum.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.transport.GistTransport
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PartnerProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME          = "name"
        const val EXTRA_TAG           = "tag"
        const val EXTRA_STATUS        = "status"
        const val EXTRA_AVATAR_BASE64 = "avatar_base64"
        const val EXTRA_GIST_ID       = "gist_id"
        const val EXTRA_GIST_TOKEN    = "gist_token"
        const val EXTRA_CHAT_PASSWORD = "chat_password"
        const val EXTRA_IMAGE_REFS    = "image_refs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_partner_profile)

        val name         = intent.getStringExtra(EXTRA_NAME) ?: ""
        val tag          = intent.getStringExtra(EXTRA_TAG)
        val status       = intent.getStringExtra(EXTRA_STATUS)
        val avatarBase64 = intent.getStringExtra(EXTRA_AVATAR_BASE64)
        val gistId       = intent.getStringExtra(EXTRA_GIST_ID) ?: ""
        val gistToken    = intent.getStringExtra(EXTRA_GIST_TOKEN) ?: ""
        val chatPassword = intent.getStringExtra(EXTRA_CHAT_PASSWORD) ?: ""
        val imageRefs    = intent.getStringArrayListExtra(EXTRA_IMAGE_REFS) ?: arrayListOf()

        // Back button
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        // Name
        findViewById<TextView>(R.id.tv_profile_name).text = name

        // Tag
        val tvTag = findViewById<TextView>(R.id.tv_profile_tag)
        if (!tag.isNullOrBlank()) {
            tvTag.text = tag
            tvTag.visibility = View.VISIBLE
        } else {
            tvTag.visibility = View.GONE
        }

        // Avatar
        val ivAvatar = findViewById<ShapeableImageView>(R.id.iv_profile_avatar)
        val tvAvatarInitial = findViewById<TextView>(R.id.tv_avatar_initial)
        val avatarBitmap = AvatarUtils.fromBase64(avatarBase64)
        if (avatarBitmap != null) {
            ivAvatar.setImageBitmap(avatarBitmap)
            ivAvatar.visibility = View.VISIBLE
            tvAvatarInitial.visibility = View.GONE
        } else {
            ivAvatar.visibility = View.GONE
            tvAvatarInitial.visibility = View.VISIBLE
            tvAvatarInitial.text = name.trim().firstOrNull()?.uppercase() ?: "?"
        }

        // Status card
        val statusCard = findViewById<View>(R.id.card_status)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        if (!status.isNullOrBlank()) {
            tvStatus.text = status
            statusCard.visibility = View.VISIBLE
        } else {
            statusCard.visibility = View.GONE
        }

        // Photos grid
        val photosSection = findViewById<View>(R.id.section_photos)
        val gridContainer = findViewById<LinearLayout>(R.id.ll_photo_grid_row1)
        val gridRow2 = findViewById<LinearLayout>(R.id.ll_photo_grid_row2)

        if (imageRefs.isEmpty()) {
            photosSection.visibility = View.GONE
        } else {
            photosSection.visibility = View.VISIBLE
            loadPhotoGrid(imageRefs, gistId, gistToken, chatPassword, gridContainer, gridRow2)
        }
    }

    private fun loadPhotoGrid(
        refs: List<String>,
        gistId: String,
        gistToken: String,
        chatPassword: String,
        row1: LinearLayout,
        row2: LinearLayout
    ) {
        val api = GistApi(token = gistToken, gistId = gistId)
        val transport = GistTransport(api)
        val loader = ImageLoader(transport, chatPassword)

        // Show up to 6 photos (3 per row)
        val display = refs.takeLast(6)

        row1.removeAllViews()
        row2.removeAllViews()

        display.forEachIndexed { index, ref ->
            val cell = layoutInflater.inflate(R.layout.item_photo_grid_cell, null) as View
            val iv = cell.findViewById<ImageView>(R.id.iv_photo_cell)
            val row = if (index < 3) row1 else row2
            row.addView(cell)

            if (ref.startsWith("base64:")) {
                val b64 = ref.removePrefix("base64:")
                val bmp = AvatarUtils.fromBase64(b64)
                if (bmp != null) iv.setImageBitmap(bmp)
            } else {
                lifecycleScope.launch {
                    val bmp: Bitmap? = withContext(Dispatchers.IO) {
                        try { loader.loadBitmap(ref) } catch (_: Exception) { null }
                    }
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
            }

            cell.setOnClickListener {
                val startIndex = refs.size - display.size + index
                val intent = android.content.Intent(this, ImageViewActivity::class.java).apply {
                    putStringArrayListExtra(ImageViewActivity.EXTRA_REFS, ArrayList(refs))
                    putExtra(ImageViewActivity.EXTRA_START_INDEX, startIndex)
                }
                startActivity(intent)
            }
        }

        row2.visibility = if (display.size > 3) View.VISIBLE else View.GONE
    }
}
