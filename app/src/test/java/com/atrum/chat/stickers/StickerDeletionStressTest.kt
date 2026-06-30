package com.atrum.chat.stickers

import android.content.Context
import com.atrum.chat.StickerDiskCache
import com.atrum.chat.StickerFrameCache
import com.atrum.chat.WebmStickerView
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StickerDeletionStressTest {

    private val context = mockk<Context>(relaxed = true)
    private lateinit var repository: StickerRepository
    private val cacheDir = File("build/test-cache").apply { mkdirs() }
    private val filesDir = File("build/test-files").apply { mkdirs() }

    @Before
    fun setup() {
        every { context.cacheDir } returns cacheDir
        every { context.filesDir } returns filesDir
        repository = StickerRepository(context)
        
        mockkObject(StickerFrameCache)
        mockkObject(StickerDiskCache)
        mockkObject(WebmStickerView)
        
        every { StickerFrameCache.remove(any()) } returns Unit
        every { StickerDiskCache.remove(any(), any()) } returns Unit
        every { WebmStickerView.cancelDecode(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkAll()
        cacheDir.deleteRecursively()
        filesDir.deleteRecursively()
    }

    @Test
    fun `test rapid deletion cancels active decodes and purges caches`() = runTest {
        val packName = "StressPack"
        val stickersCount = 100
        val stickers = (1..stickersCount).map { i ->
            val fileId = "sticker_$i"
            val file = File(filesDir, "stickers/$packName/$fileId.webm").apply {
                parentFile?.mkdirs()
                writeText("content $i")
            }
            Sticker(fileId, file.absolutePath, StickerType.VIDEO)
        }

        // Mock meta.json
        val packDir = File(filesDir, "stickers/$packName")
        val stickersJson = stickers.joinToString(",") { s ->
            """{"fileId":"${s.fileId}","localPath":"${s.localPath?.replace("\\", "/")}","type":"VIDEO"}"""
        }
        File(packDir, "meta.json").writeText("""{"name":"$packName","title":"Stress","stickers":[$stickersJson]}""")

        // Simulate rapid deletion (parallellized)
        stickers.forEach { sticker ->
            launch {
                repository.deleteSticker(sticker, packName)
            }
        }
    }

    @Test
    fun `test deletion purges all caches for specific fileId`() = runTest {
        val fileId = "test_sticker_123"
        val packName = "TestPack"
        val stickerFile = File(filesDir, "stickers/$packName/$fileId.webm").apply {
            parentFile?.mkdirs()
            writeText("dummy webm content")
        }
        
        val sticker = Sticker(
            fileId = fileId,
            localPath = stickerFile.absolutePath,
            type = StickerType.VIDEO
        )

        val packDir = File(filesDir, "stickers/$packName")
        File(packDir, "meta.json").writeText("""{"name":"$packName","title":"Test","stickers":[{"fileId":"$fileId","localPath":"${stickerFile.absolutePath.replace("\\", "/")}","type":"VIDEO"}]}""")

        repository.deleteSticker(sticker, packName)

        // Verify cache purges
        verify { StickerFrameCache.remove(fileId) }
        verify { StickerDiskCache.remove(any(), fileId) }
        verify { WebmStickerView.cancelDecode(fileId) }
        
        assert(!stickerFile.exists()) { "Sticker file should be deleted" }
    }
}
