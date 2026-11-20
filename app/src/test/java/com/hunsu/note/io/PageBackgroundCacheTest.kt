package com.hunsu.note.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 3.3 페이지 배경 캐시.
 *
 * PDF·이미지를 이어 쌓아도 램을 많이 쓰지 않도록
 * - 배경은 cacheDir 아래 파일로 두고([PageBackgroundCache.save]/[PageBackgroundCache.load]),
 * - 화면에 쓰는 몇 장만 메모리에 올리며([PageBackgroundCache.putMemory]),
 * - 메모리·디스크 예산을 넘으면 오래된 것부터 놓아준다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class PageBackgroundCacheTest {

    private lateinit var dir: File
    private lateinit var cache: PageBackgroundCache

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "bgcache-${System.nanoTime()}")
        dir.mkdirs()
        cache = PageBackgroundCache(dir)
    }

    @After
    fun tearDown() {
        cache.clear()
        dir.deleteRecursively()
    }

    private fun solid(color: Int, width: Int = 64, height: Int = 48): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint().apply { this.color = color }
        )
        return bitmap
    }

    @Test
    fun `저장한 배경을 파일에서 그대로 다시 읽는다`() {
        val key = "page_1"
        val bitmap = solid(Color.rgb(30, 120, 210))
        assertTrue(cache.save(key, bitmap))
        bitmap.recycle()

        assertTrue(cache.hasFile(key))
        assertEquals(1, cache.fileCount())
        assertTrue(cache.diskBytes() > 0)

        val loaded = cache.load(key)
        assertNotNull(loaded)
        assertEquals(64, loaded!!.width)
        assertEquals(48, loaded.height)
        val pixel = loaded.getPixel(10, 10)
        assertEquals(Color.rgb(30, 120, 210), Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel)))
        loaded.recycle()
    }

    @Test
    fun `썸네일용으로 읽을 때는 원본보다 작은 표본만 푼다`() {
        val key = "thumb"
        val bitmap = solid(Color.RED, 1024, 768)
        cache.save(key, bitmap)
        bitmap.recycle()

        val small = cache.load(key, targetWidth = 128)
        assertNotNull(small)
        // 표본화라 128 이상 256 미만으로 읽힌다(원본 1024 를 통째로 올리지 않는다).
        assertTrue("읽은 폭 ${small!!.width}", small.width in 128 until 256)
        small.recycle()
    }

    @Test
    fun `메모리 예산을 넘으면 가장 오래 안 본 배경부터 놓아준다`() {
        val oneMb = 500 * 500 * 4 // ARGB_8888 한 장 ≈ 1MB
        val small = PageBackgroundCache(dir, maxMemoryBytes = (oneMb * 2.5).toLong())

        val a = solid(Color.RED, 500, 500)
        val b = solid(Color.GREEN, 500, 500)
        val c = solid(Color.BLUE, 500, 500)
        assertEquals(oneMb.toLong(), PageBackgroundCache.bytesOf(a))

        small.putMemory("a", a)
        small.putMemory("b", b)
        // a 를 다시 보면 a 가 '최근'이 되고 b 가 가장 오래된 것이 된다.
        assertNotNull(small.getMemory("a"))
        small.putMemory("c", c)

        assertTrue("메모리 예산을 넘었습니다: ${small.memoryBytes()}", small.memoryBytes() <= oneMb * 2.5)
        assertNotNull(small.getMemory("a"))
        assertNull("가장 오래 안 본 b 가 남아 있습니다", small.getMemory("b"))
        assertNotNull(small.getMemory("c"))
        assertEquals(2, small.memoryCount())
    }

    @Test
    fun `지운 배경은 파일과 메모리에서 같이 사라진다`() {
        val key = "gone"
        val bitmap = solid(Color.RED)
        cache.save(key, bitmap)
        cache.putMemory(key, bitmap)
        assertTrue(cache.hasFile(key))
        assertNotNull(cache.getMemory(key))

        cache.evict(key)

        assertTrue(!cache.hasFile(key))
        assertNull(cache.getMemory(key))
        assertNull(cache.load(key))
        assertEquals(0, cache.fileCount())
    }

    @Test
    fun `캐시를 비우면 파일과 메모리가 모두 사라진다`() {
        val bitmap = solid(Color.RED)
        cache.save("p1", bitmap)
        cache.save("p2", bitmap)
        cache.putMemory("p1", bitmap)
        assertEquals(2, cache.fileCount())

        cache.clear()

        assertEquals(0, cache.fileCount())
        assertEquals(0, cache.memoryCount())
        assertEquals(0L, cache.diskBytes())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `디스크 예산을 넘으면 오래 전 파일부터 지우고 방금 저장한 것은 남긴다`() {
        val first = solid(Color.RED)
        val path = File(dir, "seed")
        val limited = PageBackgroundCache(dir, maxDiskBytes = 1L)
        assertTrue(limited.save("keep", first))
        assertEquals(1, limited.fileCount())
        assertTrue("방금 저장한 파일이 지워졌습니다", limited.hasFile("keep"))
        assertTrue(limited.diskBytes() > 0)
        first.recycle()
        path.delete()
    }

    @Test
    fun `같은 키를 다시 저장하면 예전 파일을 지우고 새로 쓴다`() {
        val key = "same"
        val red = solid(Color.RED)
        cache.save(key, red)
        red.recycle()
        val blue = solid(Color.BLUE)
        cache.save(key, blue)
        blue.recycle()

        assertEquals(1, cache.fileCount())
        val loaded = cache.load(key)!!
        val pixel = loaded.getPixel(5, 5)
        assertEquals(Color.BLUE, Color.rgb(Color.red(pixel), Color.green(pixel), Color.blue(pixel)))
        loaded.recycle()
    }

    @Test
    fun `이미지 배경은 JPEG 로 저장한다`() {
        val bitmap = solid(Color.rgb(200, 60, 40))
        assertTrue(cache.save("photo", bitmap, jpeg = true))
        bitmap.recycle()

        val files = dir.listFiles()!!.filter { it.name.startsWith(PageBackgroundCache.FILE_PREFIX) }
        assertEquals(1, files.size)
        assertTrue("JPEG 로 저장되지 않았습니다: ${files[0].name}", files[0].name.endsWith(".jpg"))
        val loaded = cache.load("photo")
        assertNotNull(loaded)
        assertNotEquals(0, loaded!!.width)
        loaded.recycle()
    }

    @Test
    fun `없는 키는 비어 있는 것으로 본다`() {
        assertTrue(!cache.hasFile("nothing"))
        assertNull(cache.load("nothing"))
        assertNull(cache.getMemory("nothing"))
    }
}
