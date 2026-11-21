package com.hunsu.note.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.hunsu.note.model.DrawElement
import com.hunsu.note.model.ElementKind
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.Page
import com.hunsu.note.model.Pt
import com.hunsu.note.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 3.4 도구(삼각형·점선 직선·덮개 떡메모지·글자·포스트잇 사각형)의 캔버스 동작과 그리기 테스트.
 * 캔버스 뷰를 화면에 붙이지 않아 확대 배율이 1 이므로, 화면 좌표 = 노트 world 좌표다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Drawing34Test {

    private fun blankPage(): Page {
        val page = Page()
        page.applyContentLayout(NoteAlign.LEFT, 0.02f)
        return page
    }

    /** 배경(PDF) 위에 그리는 상황을 만드는 페이지 */
    private fun pageWithBackground(): Page {
        val page = Page(baseAspect = 1.6f)
        page.applyContentLayout(NoteAlign.LEFT, 0.02f)
        return page
    }

    private fun canvas(page: Page): InkCanvasView {
        val view = InkCanvasView(ApplicationProvider.getApplicationContext())
        view.setPage(page, null)
        return view
    }

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

    private fun multi(view: View, action: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val props = Array(2) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(2) { i ->
            MotionEvent.PointerCoords().apply {
                x = if (i == 0) x0 else x1
                y = if (i == 0) y0 else y1
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            0L, 0L, action, 2, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun render(page: Page, background: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(
            page.worldWidth.toInt(), page.worldHeight.toInt(), Bitmap.Config.ARGB_8888
        )
        PagePainter.drawPage(Canvas(bitmap), page, background, Paint(Paint.ANTI_ALIAS_FLAG))
        return bitmap
    }

    private fun darkBackground(): Bitmap {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.DKGRAY)
        return bitmap
    }

    // ------------------------------------------------------------ 삼각형

    @Test
    fun `삼각형 도구로 드래그하면 삼각형이 생긴다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.TRIANGLE
        view.penColor = Color.RED
        view.penWidth = 8f

        drag(view, 100f, 100f, 300f, 250f)

        assertEquals(1, page.elements.size)
        val el = page.elements.first()
        assertEquals(ElementKind.TRIANGLE, el.kind)
        assertEquals(Color.RED, el.color)
        assertEquals(8f, el.width, 0.01f)
        assertEquals(2, el.points.size)
        assertTrue(PagePainter.isShape(ElementKind.TRIANGLE))
        assertTrue(PagePainter.isAtomic(ElementKind.TRIANGLE))
    }

    @Test
    fun `삼각형은 테두리만 그려지고 안쪽은 비어 있다`() {
        val page = blankPage()
        page.elements.add(
            DrawElement(
                ElementKind.TRIANGLE, Color.RED, 6f,
                arrayListOf(Pt(100f, 100f), Pt(300f, 250f))
            )
        )
        val bitmap = render(page, null)

        // 꼭대기(200,101) 근처는 선이 지나가고, 무게중심(200,200)은 비어 있다.
        var onEdge = 0
        for (dy in -3..3) {
            val c = bitmap.getPixel(200, 101 + dy)
            if (c != Color.WHITE) onEdge++
        }
        assertTrue("삼각형 꼭대기 선이 그려지지 않았습니다", onEdge > 0)
        assertEquals(Color.WHITE, bitmap.getPixel(200, 200))
    }

    @Test
    fun `삼각형도 지우개로 지워진다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.TRIANGLE
        drag(view, 100f, 100f, 300f, 250f)
        assertEquals(1, page.elements.size)

        view.tool = Tool.ERASER
        view.penWidth = 6f
        // 왼쪽 아래 모서리(100,250) 근처를 문지른다.
        touch(view, MotionEvent.ACTION_DOWN, 102f, 248f)
        touch(view, MotionEvent.ACTION_UP, 102f, 248f)

        assertEquals(0, page.elements.size)
    }

    // ------------------------------------------------------------ 점선 직선

    @Test
    fun `점선 직선은 점선 요소로 저장되고 끊긴 선으로 그려진다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.DASHED_LINE
        view.penColor = Color.BLACK
        view.penWidth = 8f

        drag(view, 100f, 100f, 400f, 100f)

        assertEquals(1, page.elements.size)
        assertEquals(ElementKind.DASHED_LINE, page.elements.first().kind)

        val bitmap = render(page, null)
        var colored = 0
        var blank = 0
        for (x in 120..380) {
            if (bitmap.getPixel(x, 100) == Color.WHITE) blank++ else colored++
        }
        assertTrue("점선이 그려지지 않았습니다", colored > 0)
        assertTrue("점선에 끊긴 곳이 없습니다 (실선으로 그려짐)", blank > 0)
    }

    @Test
    fun `점선 직선도 되돌리기와 다시실행이 된다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.DASHED_LINE
        drag(view, 100f, 100f, 400f, 200f)
        assertEquals(1, page.elements.size)

        assertTrue(view.undo())
        assertEquals(0, page.elements.size)
        assertTrue(view.redo())
        assertEquals(1, page.elements.size)
    }

    // ------------------------------------------------------------ 덮개 떡메모지

    @Test
    fun `덮개는 배경을 하얗게 가리고 그 위 필기는 보인다`() {
        val page = pageWithBackground()
        val view = canvas(page)
        val background = darkBackground()

        view.tool = Tool.COVER
        drag(view, 400f, 300f, 900f, 700f)
        assertEquals(1, page.elements.size)
        assertEquals(ElementKind.COVER, page.elements.first().kind)

        // 덮개만 있는 상태: 덮개 안쪽은 흰색, 바깥 배경은 그대로 어둡다.
        var bitmap = render(page, background)
        assertEquals(Color.WHITE, bitmap.getPixel(650, 500))
        assertEquals(Color.DKGRAY, bitmap.getPixel(200, 200))

        // 덮개 위에 펜으로 그으면 그 선은 덮개 위에 보인다.
        view.tool = Tool.PEN
        view.penColor = Color.RED
        view.penWidth = 20f
        drag(view, 450f, 500f, 850f, 500f)

        bitmap = render(page, background)
        assertEquals(2, page.elements.size)
        var red = 0
        for (dx in -2..2) {
            if (bitmap.getPixel(500, 500 + dx) == Color.RED) red++
        }
        assertTrue("덮개 위 필기가 보이지 않습니다", red > 0)
        // 필기가 없는 덮개 부분은 여전히 하얗다.
        assertEquals(Color.WHITE, bitmap.getPixel(650, 650))
    }

    @Test
    fun `덮개 안쪽을 지워도 덮개는 남고 테두리를 지우면 사라진다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.COVER
        drag(view, 400f, 300f, 900f, 700f)
        val cover = page.elements.first()

        view.tool = Tool.ERASER
        view.penWidth = 6f
        touch(view, MotionEvent.ACTION_DOWN, 650f, 500f)
        touch(view, MotionEvent.ACTION_UP, 650f, 500f)
        assertTrue("덮개 안쪽을 지웠더니 덮개가 지워졌습니다", page.elements.contains(cover))

        // 테두리를 문지르면 덮개도 지운다.
        touch(view, MotionEvent.ACTION_DOWN, 401f, 500f)
        touch(view, MotionEvent.ACTION_UP, 401f, 500f)
        assertEquals(0, page.elements.size)
    }

    @Test
    fun `덮개는 부분 지우개로도 조각나지 않는다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.COVER
        drag(view, 400f, 300f, 900f, 700f)

        view.tool = Tool.ERASER_PARTIAL
        view.penWidth = 6f
        touch(view, MotionEvent.ACTION_DOWN, 650f, 500f)
        touch(view, MotionEvent.ACTION_MOVE, 700f, 520f)
        touch(view, MotionEvent.ACTION_UP, 700f, 520f)

        assertEquals(1, page.elements.size)
        assertEquals(ElementKind.COVER, page.elements.first().kind)
    }

    // ------------------------------------------------------------ 글자

    @Test
    fun `글자 도구로 누르면 그 자리에서 글자 입력을 요청한다`() {
        val page = blankPage()
        val view = canvas(page)
        var tapped: Pair<Float, Float>? = null
        view.onTextTap = { x, y -> tapped = x to y }
        view.tool = Tool.TEXT

        touch(view, MotionEvent.ACTION_DOWN, 500f, 400f)
        touch(view, MotionEvent.ACTION_UP, 500f, 400f)

        assertNotNull(tapped)
        assertEquals(500f, tapped!!.first, 0.01f)
        assertEquals(400f, tapped!!.second, 0.01f)
        // 글자 도구는 획을 남기지 않는다.
        assertEquals(0, page.elements.size)
    }

    @Test
    fun `글자는 배경 없이 선택한 색과 크기로 그려진다`() {
        val page = blankPage()
        val el = DrawElement(
            ElementKind.TEXT, Color.BLUE, 100f,
            arrayListOf(Pt(200f, 200f)), ArrayList(), "가나다"
        )
        page.elements.add(el)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = PagePainter.textBounds(el, paint)
        assertTrue(bounds.width() > 0f)
        assertTrue(bounds.height() >= 100f)

        val bitmap = render(page, null)
        var drawn = 0
        var y = bounds.top.toInt() + 20
        while (y < bounds.bottom.toInt()) {
            var x = bounds.left.toInt() + 2
            while (x < bounds.right.toInt() - 2) {
                if (bitmap.getPixel(x, y) != Color.WHITE) drawn++
                x += 3
            }
            y += 4
        }
        assertTrue("글자가 그려지지 않았습니다", drawn > 0)
    }

    @Test
    fun `글자도 되돌리기로 사라지고 다시실행으로 돌아온다`() {
        val page = blankPage()
        page.pushUndo()
        page.elements.add(
            DrawElement(
                ElementKind.TEXT, Color.BLACK, 80f,
                arrayListOf(Pt(100f, 100f)), ArrayList(), "해설"
            )
        )
        assertEquals(1, page.elements.size)
        assertTrue(page.undo())
        assertEquals(0, page.elements.size)
        assertTrue(page.redo())
        assertEquals(1, page.elements.size)
        assertEquals("해설", page.elements.first().text)
    }

    @Test
    fun `글자 요소는 복사해도 내용이 남는다`() {
        val el = DrawElement(
            ElementKind.TEXT, Color.BLACK, 60f,
            arrayListOf(Pt(10f, 20f)), ArrayList(), "삼각형"
        )
        val copy = el.deepCopy()
        assertEquals("삼각형", copy.text)
        assertEquals(ElementKind.TEXT, copy.kind)
        assertEquals(false, copy.hasPressure)
    }

    @Test
    fun `글자도 지우개로 지워진다`() {
        val page = blankPage()
        val view = canvas(page)
        val el = DrawElement(
            ElementKind.TEXT, Color.BLACK, 80f,
            arrayListOf(Pt(200f, 300f)), ArrayList(), "W"
        )
        page.elements.add(el)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = PagePainter.textBounds(el, paint)

        view.tool = Tool.ERASER
        view.penWidth = 6f
        touch(view, MotionEvent.ACTION_DOWN, bounds.left + 5f, bounds.top + 20f)
        touch(view, MotionEvent.ACTION_UP, bounds.left + 5f, bounds.top + 20f)

        assertEquals(0, page.elements.size)
    }

    // ------------------------------------------------------------ 포스트잇

    @Test
    fun `포스트잇 도구로 그린 사각형이 그대로 놓인다`() {
        val page = blankPage()
        val view = canvas(page)
        var rect: FloatArray? = null
        view.onNoteRect = { x0, y0, x1, y1 -> rect = floatArrayOf(x0, y0, x1, y1) }
        view.tool = Tool.NOTE

        drag(view, 600f, 500f, 900f, 700f)

        assertNotNull(rect)
        assertEquals(listOf(600f, 500f, 900f, 700f), rect!!.toList())
        // 포스트잇 도구는 판서 요소를 남기지 않는다.
        assertEquals(0, page.elements.size)
    }

    // ------------------------------------------------------------ 이동·줌

    @Test
    fun `이동줌 도구가 없어도 두 손가락으로 확대하고 이동한다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.PEN
        val startScale = view.scale

        touch(view, MotionEvent.ACTION_DOWN, 400f, 400f)
        multi(view, MotionEvent.ACTION_POINTER_DOWN, 400f, 400f, 500f, 400f)
        multi(view, MotionEvent.ACTION_MOVE, 300f, 400f, 700f, 400f)
        multi(view, MotionEvent.ACTION_MOVE, 200f, 420f, 800f, 420f)
        multi(view, MotionEvent.ACTION_POINTER_UP, 200f, 420f, 800f, 420f)
        touch(view, MotionEvent.ACTION_UP, 800f, 420f)

        assertTrue("두 손가락으로 확대되지 않았습니다", view.scale > startScale)
        // 확대·이동해도 획은 남지 않는다.
        assertEquals(0, page.elements.size)
    }

    @Test
    fun `한 손가락으로는 화면이 움직이지 않고 필기만 된다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.PEN
        val beforeX = view.offsetX
        val beforeY = view.offsetY

        drag(view, 100f, 100f, 300f, 200f)

        assertEquals(1, page.elements.size)
        assertEquals(beforeX, view.offsetX, 0.001f)
        assertEquals(beforeY, view.offsetY, 0.001f)
        assertEquals(1f, view.scale, 0.001f)
    }
}
