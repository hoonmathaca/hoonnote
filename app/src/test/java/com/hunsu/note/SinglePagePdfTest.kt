package com.hunsu.note

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Looper
import com.hunsu.note.io.PdfPageSource
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.NoteLayout
import com.hunsu.note.model.Page
import com.hunsu.note.view.InkCanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 3.1 실기기 피드백 재현 테스트: "1페이지짜리 PDF를 열면 흰 화면만 나온다".
 *
 * 실제 PDF 파일 대신 같은 규약([PdfPageSource])의 가짜 PDF 를 넣어
 * 메인 화면의 PDF 열기 경로(openPdfPages)를 그대로 태우고, 캔버스에 올라간 배경이
 * 흰색이 아니라 실제 PDF 픽셀인지 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
class SinglePagePdfTest {

    /** 1페이지 PDF: 0번 인덱스만 그릴 수 있다 (2.0/3.0 버그에서는 1번을 요청해 늘 실패했다) */
    private class OnePagePdf(private val color: Int = Color.rgb(20, 90, 200)) : PdfPageSource {
        var requested = ArrayList<Int>()
        private var closed = false

        override val isOpen: Boolean get() = !closed
        override val pageCount: Int = 1

        override fun aspectOf(index: Int): Float = 0.7071f

        override fun render(index: Int, targetWidth: Int): Bitmap? {
            if (closed || index != 0) return null
            requested.add(index)
            val width = targetWidth.coerceAtLeast(8)
            val height = (width / 0.7071f).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                Paint().apply { this.color = this@OnePagePdf.color }
            )
            return bitmap
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `1페이지 PDF를 열면 노트 위에 실제 PDF 픽셀이 그려진다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)

        val source = OnePagePdf()
        activity.openPdfPages(source, listOf(1), 0)

        // 배경 렌더링은 워커 스레드에서 끝난 뒤 메인 루퍼로 돌아온다.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && canvas.backgroundBitmap == null) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }

        assertEquals(listOf(0), source.requested)
        val background = canvas.backgroundBitmap
        assertNotNull("1페이지 PDF 배경이 그려지지 않았습니다(흰 화면)", background)

        val page = canvas.page
        assertNotNull(page)
        assertEquals(0, page!!.pdfPageIndex)

        // 화면에 보이는 것과 같은 경로로 노트 전체를 그려 픽셀을 확인한다.
        val note = Bitmap.createBitmap(
            page.worldWidth.toInt(), page.worldHeight.toInt(), Bitmap.Config.ARGB_8888
        )
        com.hunsu.note.view.PagePainter.drawPage(
            Canvas(note), page, background, Paint(Paint.ANTI_ALIAS_FLAG)
        )
        val content = page.contentRect
        val pixel = note.getPixel(content.centerX().toInt(), content.centerY().toInt())
        assertTrue(
            "노트에 PDF 픽셀이 찍히지 않았습니다: #" + Integer.toHexString(pixel),
            Color.blue(pixel) > 150 && Color.red(pixel) < 90
        )
    }

    @Test
    fun `빈 페이지와 PDF 페이지는 노트 안에서 구분된다`() {
        val blank = Page(NoteLayout.WIDE_16_9)
        blank.applyContentLayout(NoteAlign.LEFT, 0.02f)
        assertTrue(blank.contentRect.isEmpty)

        val source = OnePagePdf()
        activityPages(source).single().let { page ->
            assertEquals(0, page.pdfPageIndex)
            assertTrue(!page.contentRect.isEmpty)
            assertTrue(page.contentRect.right <= page.worldWidth)
            assertTrue(page.contentRect.bottom <= page.worldHeight)
        }
    }

    private fun activityPages(source: PdfPageSource): List<Page> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.openPdfPages(source, listOf(1), 0)
        return activity.pdfPagesForTest()
    }
}
