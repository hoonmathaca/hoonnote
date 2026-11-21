package com.hunsu.note

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.hunsu.note.model.DrawElement
import com.hunsu.note.model.ElementKind
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.Page
import com.hunsu.note.model.Pt
import com.hunsu.note.model.Tool
import com.hunsu.note.view.InkCanvasView
import com.hunsu.note.view.PagePainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 3.5 덮개 떡메모지: 그릴 때 '테두리 있음 / 테두리 없음'을 고를 수 있고,
 * 고른 결과가 요소에 남아 페이지 전환·Undo/Redo·내보내기에서도 유지된다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Cover35Test {

    private fun activity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun drag(view: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        touch(view, MotionEvent.ACTION_DOWN, x0, y0)
        touch(view, MotionEvent.ACTION_MOVE, (x0 + x1) / 2f, (y0 + y1) / 2f)
        touch(view, MotionEvent.ACTION_MOVE, x1, y1)
        touch(view, MotionEvent.ACTION_UP, x1, y1)
    }

    /** 덮개 하나만 있는 페이지를 그려 본다. */
    private fun renderCover(border: Boolean): Bitmap {
        val page = Page()
        page.applyContentLayout(NoteAlign.LEFT, 0.02f)
        page.elements.add(
            DrawElement(
                ElementKind.COVER, 0xFF000000.toInt(), 2f,
                arrayListOf(Pt(400f, 300f), Pt(900f, 700f)),
                ArrayList(), "", border
            )
        )
        val bitmap = Bitmap.createBitmap(
            page.worldWidth.toInt(), page.worldHeight.toInt(), Bitmap.Config.ARGB_8888
        )
        PagePainter.drawPage(Canvas(bitmap), page, null, Paint(Paint.ANTI_ALIAS_FLAG))
        return bitmap
    }

    @Test
    fun `떡메모지 도구를 고르면 테두리 있음 없음 선택이 보이고 다른 도구에서는 숨는다`() {
        val activity = activity()
        val row = activity.findViewById<View>(R.id.coverBorderRow)
        assertEquals(View.GONE, row.visibility)

        activity.findViewById<View>(R.id.btnCover).performClick()
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnCoverBorderOn).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnCoverBorderOff).visibility)

        activity.findViewById<View>(R.id.btnPen).performClick()
        assertEquals(View.GONE, row.visibility)
    }

    @Test
    fun `테두리 있음을 고르고 그린 덮개는 테두리를 갖는다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnCover).performClick()
        activity.findViewById<View>(R.id.btnCoverBorderOn).performClick()
        assertEquals(true, activity.coverBorderedForTest())

        drag(canvas, 300f, 300f, 800f, 600f)

        val page = canvas.page!!
        assertEquals(1, page.elements.size)
        val cover = page.elements.first()
        assertEquals(ElementKind.COVER, cover.kind)
        assertTrue("테두리 있음을 골랐는데 요소에 남지 않았습니다", cover.border)
    }

    @Test
    fun `테두리 없음을 고르면 순수 흰 면만 그려진다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnCover).performClick()
        activity.findViewById<View>(R.id.btnCoverBorderOff).performClick()
        assertEquals(false, activity.coverBorderedForTest())

        drag(canvas, 300f, 300f, 800f, 600f)
        assertEquals(false, canvas.page!!.elements.first().border)

        val bitmap = renderCover(false)
        // 가장자리까지 전부 흰색이다.
        assertEquals(Color.WHITE, bitmap.getPixel(400, 500))
        assertEquals(Color.WHITE, bitmap.getPixel(401, 500))
        assertEquals(Color.WHITE, bitmap.getPixel(900, 500))
        assertEquals(Color.WHITE, bitmap.getPixel(650, 500))
    }

    @Test
    fun `테두리 있는 덮개는 가장자리에 얇은 선이 보이고 안쪽은 흰색이다`() {
        val bitmap = renderCover(true)
        val edge = bitmap.getPixel(401, 500)
        assertTrue("덮개 테두리가 그려지지 않았습니다", edge != Color.WHITE)
        assertEquals("덮개 안쪽이 흰색이 아닙니다", Color.WHITE, bitmap.getPixel(650, 500))
        assertEquals("덮개 안쪽이 흰색이 아닙니다", Color.WHITE, bitmap.getPixel(650, 650))
        // 아래·위 가장자리도 테두리가 있다.
        assertTrue(bitmap.getPixel(650, 301) != Color.WHITE)
    }

    @Test
    fun `테두리 선택은 실행취소와 다시실행에도 유지된다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnCover).performClick()
        activity.findViewById<View>(R.id.btnCoverBorderOn).performClick()
        drag(canvas, 300f, 300f, 800f, 600f)

        activity.findViewById<View>(R.id.btnUndo).performClick()
        assertEquals(0, canvas.page!!.elements.size)

        activity.findViewById<View>(R.id.btnRedo).performClick()
        val cover = canvas.page!!.elements.first()
        assertEquals(ElementKind.COVER, cover.kind)
        assertTrue("다시실행한 덮개의 테두리가 사라졌습니다", cover.border)
    }

    @Test
    fun `테두리 선택은 페이지를 옮겼다 돌아와도 유지된다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnCover).performClick()
        activity.findViewById<View>(R.id.btnCoverBorderOn).performClick()
        drag(canvas, 300f, 300f, 800f, 600f)
        val firstPage = canvas.page!!

        activity.findViewById<View>(R.id.btnAddPage).performClick()
        assertEquals(false, canvas.page === firstPage)

        activity.findViewById<View>(R.id.btnPrevPage).performClick()
        assertEquals(true, canvas.page === firstPage)
        assertTrue(firstPage.elements.first().border)
    }

    @Test
    fun `테두리 선택은 앱을 다시 켜도 기억한다`() {
        val first = activity()
        first.findViewById<View>(R.id.btnCover).performClick()
        first.findViewById<View>(R.id.btnCoverBorderOn).performClick()
        assertEquals(true, Prefs.coverBorder)

        val second = activity()
        assertEquals(true, second.coverBorderedForTest())
        second.findViewById<View>(R.id.btnCover).performClick()
        // 새로 그리는 덮개에도 켜진 상태가 이어진다.
        second.findViewById<View>(R.id.btnCoverBorderOff).performClick()
        assertEquals(false, Prefs.coverBorder)
    }

    @Test
    fun `떡메모지 도구가 아니어도 테두리 값은 캔버스에 그대로 전달된다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnCoverBorderOn).performClick()
        assertEquals(true, canvas.coverBorder)
        assertEquals(Tool.PEN, canvas.tool)
    }
}
