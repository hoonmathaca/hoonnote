package com.hunsu.note.io

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.NoteLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.Closeable

/**
 * PDF 열기 경로 테스트.
 *
 * 실제 PdfRenderer 는 기기에서만 도는 네이티브라서, 같은 [PdfPageSource] 규약을 따르는
 * 가짜 PDF 를 넣어 "1페이지짜리 PDF" 도 노트 위에 실제 픽셀로 그려지는지 확인한다.
 * 3.0 의 버그는 페이지 번호(1부터)를 렌더러 인덱스(0부터)로 그대로 넘겨
 * 1페이지 PDF 의 모든 페이지가 범위 밖이 되고 흰 화면만 남던 것이었다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [android.os.Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
class PdfPagesTest {

    /** 항상 파란 화면을 돌려주는 1페이지짜리 가짜 PDF */
    private class FakePdfSource(
        override val pageCount: Int,
        private val aspect: Float,
        private val fillColor: Int = Color.BLUE
    ) : PdfPageSource, Closeable {

        val requestedIndices = ArrayList<Int>()
        var closed = false

        override val isOpen: Boolean get() = !closed

        override fun aspectOf(index: Int): Float = aspect

        override fun render(index: Int, targetWidth: Int): Bitmap? {
            if (closed || index < 0 || index >= pageCount) return null
            requestedIndices.add(index)
            val width = targetWidth.coerceAtLeast(8)
            val height = (width / aspect).toInt().coerceAtLeast(8)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                Paint().apply { color = fillColor }
            )
            return bitmap
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `페이지 번호는 0부터 시작하는 인덱스로 바뀐다`() {
        // 1페이지짜리 PDF: 사용자가 고른 1페이지는 인덱스 0
        assertEquals(listOf(0), PdfPages.toIndices(listOf(1), 1))
        // 5페이지 문서의 전체 열기: 1..5 → 0..4 (마지막 페이지도 범위 안)
        assertEquals((0..4).toList(), PdfPages.toIndices((1..5).toList(), 5))
        // 문서 밖 번호는 버린다
        assertEquals(emptyList<Int>(), PdfPages.toIndices(listOf(9), 3))
        assertEquals(emptyList<Int>(), PdfPages.toIndices(listOf(1), 0))
    }

    @Test
    fun `1페이지 PDF 도 노트 페이지를 만든다`() {
        val source = FakePdfSource(pageCount = 1, aspect = 0.7071f)
        val pages = PdfPages.buildPages(
            source, listOf(1), 0, NoteLayout.WIDE_16_9, NoteAlign.LEFT, 0.02f
        )
        assertEquals(1, pages.size)
        val page = pages.first()
        assertEquals(0, page.pdfPageIndex)
        // 1페이지 PDF 에서도 렌더러는 실제로 0번 인덱스를 요청해야 한다.
        assertNotNull(source.render(page.pdfPageIndex, page.backgroundPixelWidth()))
        assertEquals(listOf(0), source.requestedIndices)
    }

    @Test
    fun `1페이지 PDF 배경이 노트 안쪽에 맞춰 그려진다`() {
        val source = FakePdfSource(pageCount = 1, aspect = 0.7071f)
        val page = PdfPages.buildPages(
            source, listOf(1), 0, NoteLayout.WIDE_16_9, NoteAlign.LEFT, 0.02f
        ).single()
        val background = source.render(page.pdfPageIndex, page.backgroundPixelWidth())
        assertNotNull(background)
        page.renderedBackground = background

        // 노트 캔버스 크기로 페이지 전체를 그린다(화면에 보이는 것과 같은 경로).
        val note = Bitmap.createBitmap(
            page.worldWidth.toInt(), page.worldHeight.toInt(), Bitmap.Config.ARGB_8888
        )
        com.hunsu.note.view.PagePainter.drawPage(
            Canvas(note), page, background, Paint(Paint.ANTI_ALIAS_FLAG)
        )

        val content = page.contentRect
        // 1) 내용 영역 한가운데에 PDF 픽셀이 실제로 찍혀 있다 (흰 화면이 아니다)
        val inside = note.getPixel(content.centerX().toInt(), content.centerY().toInt())
        assertTrue("PDF 내용이 그려지지 않았습니다: #" + Integer.toHexString(inside),
            Color.blue(inside) > 200 && Color.red(inside) < 60)
        // 2) 노트 오른쪽 여백은 노트 배경(흰색) 그대로다 — 내용이 노트 밖으로 넘치지 않는다
        val marginY = (content.centerY() + 37).toInt()
        val margin = note.getPixel(page.worldWidth.toInt() - 8, marginY)
        assertTrue(
            "노트 여백이 PDF로 덮였습니다: #" + Integer.toHexString(margin),
            Color.red(margin) > 200 && Color.green(margin) > 200 && Color.blue(margin) > 200
        )
        // 3) fit-inside: 노트 높이를 거의 다 채우고(여백 2%씩) 노트 밖으로 넘치지 않는다
        assertTrue(content.height() >= page.worldHeight * 0.95f)
        assertTrue(content.width() <= page.worldWidth)
        assertEquals(page.worldWidth * 0.02f, content.left, 1.5f)
        // 4) 확대해도 선명하도록 노트 픽셀보다 촘촘하게(1.75배) 그린다
        assertTrue(background!!.width > content.width())
        assertTrue(background.width >= (content.width() * 1.5f).toInt())
    }

    @Test
    fun `회전해도 PDF 는 노트 안쪽에 맞춰진다`() {
        for (rotation in intArrayOf(90, 180, 270)) {
            val source = FakePdfSource(pageCount = 1, aspect = 0.7071f)
            val page = PdfPages.buildPages(
                source, listOf(1), rotation, NoteLayout.WIDE_16_9, NoteAlign.LEFT, 0.02f
            ).single()
            val content = page.contentRect
            assertTrue("회전 $rotation: 왼쪽 밖", content.left >= 0f)
            assertTrue("회전 $rotation: 위 밖", content.top >= 0f)
            assertTrue("회전 $rotation: 오른쪽 밖", content.right <= page.worldWidth + 0.5f)
            assertTrue("회전 $rotation: 아래 밖", content.bottom <= page.worldHeight + 0.5f)

            // 회전한 페이지도 실제로 그려진다.
            val background = source.render(page.pdfPageIndex, page.backgroundPixelWidth())
            assertNotNull(background)
            val note = Bitmap.createBitmap(
                page.worldWidth.toInt(), page.worldHeight.toInt(), Bitmap.Config.ARGB_8888
            )
            com.hunsu.note.view.PagePainter.drawPage(
                Canvas(note), page, background, Paint(Paint.ANTI_ALIAS_FLAG)
            )
            val inside = note.getPixel(content.centerX().toInt(), content.centerY().toInt())
            assertTrue("회전 $rotation: 내용이 안 그려짐", Color.blue(inside) > 200)
        }
    }
}
