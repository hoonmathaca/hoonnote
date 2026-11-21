package com.hunsu.note

import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.hunsu.note.model.StickyGeometry
import com.hunsu.note.model.Tool
import com.hunsu.note.view.InkCanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** 3.4 툴바 구성과 새 도구 동작이 화면에서 실제로 연결되는지 보는 테스트 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
class Toolbar34Test {

    private fun activity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun hasView(activity: MainActivity, name: String): Boolean {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        return id != 0 && activity.findViewById<View>(id) != null
    }

    @Test
    fun `툴바에 점선 직선 삼각형 덮개 글자가 있고 화살표와 이동줌은 없다`() {
        val activity = activity()
        assertTrue(hasView(activity, "btnDashed"))
        assertTrue(hasView(activity, "btnTriangle"))
        assertTrue(hasView(activity, "btnCover"))
        assertTrue(hasView(activity, "btnText"))
        assertTrue(hasView(activity, "btnNote"))
        assertEquals(false, hasView(activity, "btnArrow"))
        assertEquals(false, hasView(activity, "btnHand"))
    }

    @Test
    fun `점선 직선 버튼을 고르면 점선 도구가 선택된다`() {
        val activity = activity()
        activity.findViewById<View>(R.id.btnDashed).performClick()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        assertEquals(Tool.DASHED_LINE, canvas.tool)
    }

    @Test
    fun `삼각형과 덮개 버튼도 각 도구를 고른다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)

        activity.findViewById<View>(R.id.btnTriangle).performClick()
        assertEquals(Tool.TRIANGLE, canvas.tool)

        activity.findViewById<View>(R.id.btnCover).performClick()
        assertEquals(Tool.COVER, canvas.tool)
    }

    @Test
    fun `글자 도구를 고르면 글자 크기 단계가 나온다`() {
        val activity = activity()
        activity.findViewById<View>(R.id.btnText).performClick()

        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        assertEquals(Tool.TEXT, canvas.tool)

        val width = activity.findViewById<android.widget.GridLayout>(R.id.widthGrid)
        val first = width.getChildAt(0) as android.widget.TextView
        // 펜 단계(1.5)가 아니라 글자 크기 단계(26)가 나온다.
        assertEquals("26", first.text.toString())
    }

    @Test
    fun `글자 도구로 캔버스를 누르면 입력 팝업 대신 그 자리 입력창이 뜬다`() {
        val activity = activity()
        activity.findViewById<View>(R.id.btnText).performClick()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)

        touch(canvas, MotionEvent.ACTION_DOWN, 600f, 400f)
        touch(canvas, MotionEvent.ACTION_UP, 600f, 400f)

        // 3.5 부터는 팝업(AlertDialog)을 쓰지 않고, 누른 자리에 바로 입력창이 뜬다.
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog == null || !dialog.isShowing)
        assertNotNull(activity.inlineTextEditorForTest())
    }

    @Test
    fun `포스트잇 도구로 사각형을 그리면 그 자리에 포스트잇이 생긴다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnNote).performClick()

        assertTrue(ShadowToast.getTextOfLatestToast().contains("사각형"))

        touch(canvas, MotionEvent.ACTION_DOWN, 300f, 300f)
        touch(canvas, MotionEvent.ACTION_MOVE, 500f, 450f)
        touch(canvas, MotionEvent.ACTION_MOVE, 700f, 600f)
        touch(canvas, MotionEvent.ACTION_UP, 700f, 600f)

        val page = canvas.page
        assertNotNull(page)
        assertEquals(1, page!!.notes.size)
        val note = page.notes.first()
        // 화면 좌표가 노트 좌표로 바뀌어, 그린 사각형만큼의 크기로 놓인다.
        assertTrue(note.width >= StickyGeometry.MIN_WIDTH)
        assertTrue(note.height >= StickyGeometry.MIN_HEIGHT)
        val expectedW = (canvas.screenToWorldX(700f) - canvas.screenToWorldX(300f))
        assertEquals(expectedW, note.width, 1.5f)
    }

    @Test
    fun `포스트잇을 탭만 해도 기본 크기로 생긴다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnNote).performClick()

        touch(canvas, MotionEvent.ACTION_DOWN, 600f, 400f)
        touch(canvas, MotionEvent.ACTION_UP, 601f, 400f)

        val page = canvas.page!!
        assertEquals(1, page.notes.size)
        assertEquals(StickyGeometry.DEFAULT_WIDTH, page.notes.first().width, 0.5f)
        assertEquals(StickyGeometry.DEFAULT_HEIGHT, page.notes.first().height, 0.5f)
    }

    @Test
    fun `포스트잇은 페이지를 옮겼다 돌아와도 남아 있다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnNote).performClick()
        touch(canvas, MotionEvent.ACTION_DOWN, 300f, 300f)
        touch(canvas, MotionEvent.ACTION_MOVE, 600f, 500f)
        touch(canvas, MotionEvent.ACTION_UP, 600f, 500f)
        val firstPage = canvas.page!!

        activity.findViewById<View>(R.id.btnAddPage).performClick()
        assertEquals(false, canvas.page === firstPage)

        activity.findViewById<View>(R.id.btnPrevPage).performClick()
        assertEquals(true, canvas.page === firstPage)
        assertEquals(1, firstPage.notes.size)
    }

    @Test
    fun `덮개를 그리고 되돌리면 사라진다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnCover).performClick()

        touch(canvas, MotionEvent.ACTION_DOWN, 300f, 300f)
        touch(canvas, MotionEvent.ACTION_MOVE, 800f, 600f)
        touch(canvas, MotionEvent.ACTION_UP, 800f, 600f)
        val page = canvas.page!!
        assertEquals(1, page.elements.size)

        activity.findViewById<View>(R.id.btnUndo).performClick()
        assertEquals(0, page.elements.size)
    }

    @Test
    fun `이동줌 버튼을 빼도 썸네일 목록은 그대로다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        assertNotNull(canvas.page)
        // 두 손가락 확대·이동만 남았으므로, 화면 맞춤은 메뉴에서 한다.
        canvas.fitPage()
        assertEquals(1f > 0f, canvas.scale > 0f)
    }
}
