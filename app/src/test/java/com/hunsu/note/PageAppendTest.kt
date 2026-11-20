package com.hunsu.note

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Looper
import com.hunsu.note.io.PdfPageSource
import com.hunsu.note.model.DrawElement
import com.hunsu.note.model.ElementKind
import com.hunsu.note.model.Page
import com.hunsu.note.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 3.3 핵심: PDF·이미지를 새로 열 때 기존 페이지를 지우지 않고 '이어서 쌓는' 동작.
 *
 * - 두 번째 PDF는 앞에 있던 페이지와 필기 뒤에 붙는다.
 * - 방금 붙인 첫 새 페이지로 저절로 이동한다.
 * - 아직 아무것도 없는 빈 노트 한 장뿐이면 그 장은 지우고 새 PDF로 시작한다.
 * - 새 노트(전체 비우기)는 페이지·필기·배경 캐시를 모두 지운다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
class PageAppendTest {

    /** 페이지마다 다른 색으로 그려 주는 가짜 PDF (인덱스 0부터 pageCount-1) */
    private class FakePdf(private val pageCountValue: Int) : PdfPageSource {
        var requested = ArrayList<Int>()
        private var closed = false

        override val isOpen: Boolean get() = !closed

        override val pageCount: Int get() = this.pageCountValue

        override fun aspectOf(index: Int): Float = 0.7071f

        override fun render(index: Int, targetWidth: Int): Bitmap? {
            if (closed || index !in 0 until pageCountValue) return null
            requested.add(index)
            val width = targetWidth.coerceAtLeast(8)
            val height = (width / 0.7071f).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val color = Color.rgb(20 + index * 10, 90, 200)
            Canvas(bitmap).drawRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                Paint().apply { this.color = color }
            )
            return bitmap
        }

        override fun close() {
            closed = true
        }
    }

    private fun activity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    /** 배경 로딩은 워커 스레드에서 끝나 메인 루퍼로 돌아온다 — 그때까지 기다린다. */
    private fun waitForBackground(activity: MainActivity, expectedPage: Page) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && expectedPage.renderedBackground == null) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("배경이 준비되지 않았습니다", expectedPage.renderedBackground)
    }

    /** 지금 페이지에 필기 한 획을 남긴다(사용자가 펜으로 그린 것과 같은 상태). */
    private fun ink(page: Page) {
        page.pushUndo()
        page.elements.add(
            DrawElement(
                ElementKind.STROKE,
                Color.BLACK,
                3f,
                mutableListOf(Pt(10f, 10f), Pt(120f, 120f))
            )
        )
    }

    @Test
    fun `두 번째 PDF는 기존 페이지와 필기 뒤에 이어서 붙는다`() {
        val activity = activity()
        activity.openPdfPages(FakePdf(2), listOf(1, 2), 0)

        val first = activity.pdfPagesForTest()
        assertEquals(2, first.size)
        ink(first[0])

        activity.openPdfPages(FakePdf(3), listOf(1, 2, 3), 0)

        val all = activity.pdfPagesForTest()
        assertEquals("앞 PDF 2장 + 새 PDF 3장 = 5장이어야 합니다", 5, all.size)
        // 앞 페이지와 필기가 그대로 남아 있다.
        assertEquals(1, all[0].elements.size)
        assertEquals(0, all[0].pdfPageIndex)
        assertEquals(1, all[1].pdfPageIndex)
        // 새 PDF 페이지들이 뒤에 붙었다.
        assertEquals(0, all[2].pdfPageIndex)
        assertEquals(1, all[3].pdfPageIndex)
        assertEquals(2, all[4].pdfPageIndex)
    }

    @Test
    fun `이어 붙인 뒤에는 방금 붙은 첫 새 페이지로 이동한다`() {
        val activity = activity()
        activity.openPdfPages(FakePdf(2), listOf(1, 2), 0)
        assertEquals(0, activity.currentPageIndexForTest())

        activity.openPdfPages(FakePdf(2), listOf(1, 2), 0)

        assertEquals(2, activity.currentPageIndexForTest())
        val page = activity.pdfPagesForTest()[activity.currentPageIndexForTest()]
        waitForBackground(activity, page)
    }

    @Test
    fun `빈 노트 한 장뿐이면 그 장을 지우고 새 PDF로 시작한다`() {
        val activity = activity()
        val before = activity.pdfPagesForTest()
        assertEquals(1, before.size)
        assertTrue(!before[0].hasContent)

        activity.openPdfPages(FakePdf(3), listOf(1, 2, 3), 0)

        val pages = activity.pdfPagesForTest()
        assertEquals("빈 장이 남아 4장이 되면 안 됩니다", 3, pages.size)
        assertTrue(pages.all { it.hasContent })
        assertEquals(0, activity.currentPageIndexForTest())
    }

    @Test
    fun `PDF를 여러 번 이어 쌓으면 페이지 수가 계속 늘어난다`() {
        val activity = activity()
        repeat(4) { activity.openPdfPages(FakePdf(3), listOf(1, 2, 3), 0) }

        assertEquals(12, activity.pdfPagesForTest().size)
        // 마지막에 붙인 묶음의 첫 장으로 이동해 있다.
        assertEquals(9, activity.currentPageIndexForTest())
    }

    @Test
    fun `수십 장을 이어 쌓아도 화면에 올린 배경만 메모리에 남는다`() {
        val activity = activity()
        val cache = activity.backgroundCacheForTest()

        activity.openPdfPages(FakePdf(40), (1..40).toList(), 0)
        val pages = activity.pdfPagesForTest()
        assertEquals(40, pages.size)

        val current = pages[activity.currentPageIndexForTest()]
        waitForBackground(activity, current)

        // 배경은 보는 페이지만 그린다(지연 로딩) — 40장 중 1장.
        assertTrue(
            "메모리에 올라간 배경이 너무 많습니다: ${cache.memoryCount()}",
            cache.memoryCount() <= 2
        )
        assertEquals(
            "화면에 올린 배경 외에 다른 페이지가 비트맵을 들고 있습니다",
            1,
            pages.count { it.renderedBackground != null }
        )
        // 그 배경은 캐시 파일로 남아 다음에 다시 그리지 않는다.
        assertTrue(cache.hasFile(current.backgroundKey))
        assertTrue(cache.fileCount() >= 1)
    }

    @Test
    fun `페이지를 옮겨 다녀도 메모리에 올라간 배경 수가 제한된다`() {
        val activity = activity()
        val cache = activity.backgroundCacheForTest()
        activity.openPdfPages(FakePdf(12), (1..12).toList(), 0)

        for (index in 0 until 12) {
            activity.goToPageForTest(index)
            val page = activity.pdfPagesForTest()[index]
            waitForBackground(activity, page)
            shadowOf(Looper.getMainLooper()).idle()
        }

        // 파일 캐시에는 12장이 모두 있고, 메모리에는 몇 장만 남는다.
        assertEquals(12, cache.fileCount())
        assertTrue(
            "메모리 캐시가 페이지 수만큼 늘었습니다: ${cache.memoryCount()}",
            cache.memoryCount() < 12
        )
    }

    @Test
    fun `앞뒤 페이지 배경은 미리 캐시해 둔다`() {
        val activity = activity()
        activity.openPdfPages(FakePdf(12), (1..12).toList(), 0)
        val pages = activity.pdfPagesForTest()
        waitForBackground(activity, pages[0])

        val cache = activity.backgroundCacheForTest()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !cache.hasFile(pages[1].backgroundKey)) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("다음 장 배경이 미리 준비되지 않았습니다", cache.hasFile(pages[1].backgroundKey))
        // 미리 만들어 둔 배경은 메모리가 아니라 캐시 파일에 둔다.
        assertEquals(1, pages.count { it.renderedBackground != null })
    }

    @Test
    fun `새 노트는 페이지와 필기, 캐시를 모두 지운다`() {
        val activity = activity()
        activity.openPdfPages(FakePdf(3), listOf(1, 2, 3), 0)
        val pages = activity.pdfPagesForTest()
        ink(pages[0])
        waitForBackground(activity, pages[0])
        assertTrue(activity.backgroundCacheForTest().fileCount() >= 1)

        activity.startNewNote()

        val after = activity.pdfPagesForTest()
        assertEquals(1, after.size)
        assertTrue(!after[0].hasContent)
        assertTrue(after[0].elements.isEmpty())
        assertEquals(0, activity.currentPageIndexForTest())
        // 임시 캐시 파일도 남지 않는다.
        assertEquals(0, activity.backgroundCacheForTest().fileCount())
        assertEquals(0, activity.backgroundCacheForTest().memoryCount())
    }

    @Test
    fun `페이지를 삭제하면 그 페이지의 배경 캐시도 지운다`() {
        val activity = activity()
        activity.openPdfPages(FakePdf(2), listOf(1, 2), 0)
        val pages = activity.pdfPagesForTest()
        waitForBackground(activity, pages[0])
        val cache = activity.backgroundCacheForTest()
        assertTrue(cache.hasFile(pages[0].backgroundKey))

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(dialog == null || !dialog.isShowing)
        activity.findViewById<android.view.View>(R.id.btnDeletePage).performClick()
        val confirm = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(confirm)
        (confirm as androidx.appcompat.app.AlertDialog)
            .getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, activity.pdfPagesForTest().size)
        assertTrue("삭제한 페이지의 캐시 파일이 남았습니다", !cache.hasFile(pages[0].backgroundKey))
        assertNull(cache.getMemory(pages[0].backgroundKey))
    }
}
