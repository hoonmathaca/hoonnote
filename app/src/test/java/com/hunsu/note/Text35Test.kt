package com.hunsu.note

import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import com.hunsu.note.model.ElementKind
import com.hunsu.note.model.Tool
import com.hunsu.note.view.InkCanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * 3.5 글자 도구: 팝업 대신 캔버스 그 자리에 뜨는 인라인 입력창.
 * - 누른 지점에 투명 입력창이 바로 뜨고 키보드가 올라온다(포커스).
 * - 입력 중 글자 크기를 바꾸면 그 자리에서 바로 반영된다.
 * - 바깥(캔버스·다른 도구)을 누르면 확정되고, 빈 글자는 남지 않는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
class Text35Test {

    private fun activity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun tap(view: View, x: Float, y: Float) {
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
    }

    private fun startText(activity: MainActivity, x: Float = 600f, y: Float = 400f) {
        activity.findViewById<View>(R.id.btnText).performClick()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        tap(canvas, x, y)
        idle()
    }

    @Test
    fun `글자 도구로 캔버스를 누르면 팝업 없이 그 자리에 투명 입력창이 뜬다`() {
        val activity = activity()
        startText(activity)

        val edit = activity.inlineTextEditorForTest()
        assertNotNull("누른 자리에 입력창이 뜨지 않았습니다", edit)
        // 입력 팝업(AlertDialog)은 더 이상 쓰지 않는다.
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog == null || !dialog.isShowing)

        // 투명 배경 — 아래 캔버스가 그대로 비쳐 보인다.
        assertNull(edit!!.background)
        assertEquals(0, edit.paddingLeft)
        assertEquals(0, edit.paddingTop)
        // 바로 타이핑할 수 있게 포커스(=키보드)가 잡힌다.
        assertTrue("입력창에 포커스가 잡히지 않았습니다", edit.hasFocus())

        // 누른 화면 지점에 놓인다.
        assertEquals(600f, edit.x, 1.5f)
        // 위아래는 한 줄 높이 안에서 누른 지점에 맞춘다(글자 기준선 정렬).
        assertTrue("입력창이 누른 높이에서 벗어났습니다: ${edit.y}", edit.y >= 400f - 1f && edit.y <= 400f + edit.textSize * 1.2f)
    }

    @Test
    fun `입력 중에는 요소가 되지 않고 바깥을 누르면 그 자리에 확정된다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        startText(activity, 600f, 400f)

        val edit = activity.inlineTextEditorForTest()!!
        edit.setText("해설")
        assertEquals("입력 중인 글자가 벌써 요소로 들어갔습니다", 0, canvas.page!!.elements.size)

        // 다른 곳을 누르면 확정된다.
        tap(canvas, 950f, 650f)
        idle()

        val page = canvas.page!!
        assertEquals(1, page.elements.size)
        val el = page.elements.first()
        assertEquals(ElementKind.TEXT, el.kind)
        assertEquals("해설", el.text)
        assertEquals(canvas.screenToWorldX(600f), el.points.first().x, 0.5f)
        assertEquals(canvas.screenToWorldY(400f), el.points.first().y, 0.5f)
        assertEquals(activity.inlineTextEditorForTest()?.text?.toString(), "") // 새 빈 입력창
    }

    @Test
    fun `도구를 바꾸면 입력이 확정된다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        startText(activity)
        activity.inlineTextEditorForTest()!!.setText("삼각형")

        activity.findViewById<View>(R.id.btnPen).performClick()
        idle()

        assertEquals(Tool.PEN, canvas.tool)
        assertNull("확정 뒤 입력창이 남아 있습니다", activity.inlineTextEditorForTest())
        val page = canvas.page!!
        assertEquals(1, page.elements.size)
        assertEquals("삼각형", page.elements.first().text)
    }

    @Test
    fun `빈 글자는 확정되지 않는다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        startText(activity)
        activity.inlineTextEditorForTest()!!.setText("   ")

        activity.findViewById<View>(R.id.btnPen).performClick()
        idle()

        assertEquals(0, canvas.page!!.elements.size)
        assertNull(activity.inlineTextEditorForTest())
    }

    @Test
    fun `입력 중 글자 크기를 바꾸면 그 자리에서 바로 커진다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        startText(activity)
        val edit = activity.inlineTextEditorForTest()!!
        val before = edit.textSize

        val grid = activity.findViewById<GridLayout>(R.id.widthGrid)
        // 글자 크기 마지막 단계(120)
        (grid.getChildAt(grid.childCount - 1) as TextView).performClick()
        idle()

        val after = edit.textSize
        assertTrue("입력 중 글자 크기가 바로 바뀌지 않았습니다 ($before → $after)", after > before * 1.5f)
        // 칩의 숫자도 글자 크기 단계다.
        assertEquals("120", (grid.getChildAt(grid.childCount - 1) as TextView).text.toString())

        edit.setText("크게")
        activity.commitInlineTextForTest()
        val el = canvas.page!!.elements.first()
        assertEquals(ElementKind.TEXT, el.kind)
        assertEquals(120f, el.width, 0.01f)
    }

    @Test
    fun `글자 크기 칩 위에 지금 무엇을 고르는지 나온다`() {
        val activity = activity()
        val label = activity.findViewById<TextView>(R.id.tvSizeLabel)
        assertEquals("굵기", label.text.toString())

        activity.findViewById<View>(R.id.btnText).performClick()
        assertEquals("글자 크기", label.text.toString())

        activity.findViewById<View>(R.id.btnHighlighter).performClick()
        assertEquals("형광펜 굵기", label.text.toString())
    }

    @Test
    fun `화면을 움직여도 입력창은 글자 자리에 남는다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        startText(activity, 600f, 400f)
        val edit = activity.inlineTextEditorForTest()!!
        val beforeX = edit.x
        val beforeY = edit.y
        val worldX = canvas.screenToWorldX(600f)
        val worldY = canvas.screenToWorldY(400f)

        canvas.panBy(-100f, -50f)
        idle()

        assertEquals(beforeX - 100f, edit.x, 1f)
        assertEquals(beforeY - 50f, edit.y, 1f)
        // 보이는 위치가 바뀌어도 저장될 글자 위치는 처음 누른 지점 그대로다.
        edit.setText("이동")
        activity.commitInlineTextForTest()
        val el = canvas.page!!.elements.first()
        assertEquals(worldX, el.points.first().x, 0.5f)
        assertEquals(worldY, el.points.first().y, 0.5f)
    }

    @Test
    fun `포스트잇으로 포커스가 넘어가도 입력하던 글자는 확정된다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)

        // 포스트잇을 먼저 놓아 둔다(300,300 → 600,500).
        activity.findViewById<View>(R.id.btnNote).performClick()
        touch(canvas, MotionEvent.ACTION_DOWN, 300f, 300f)
        touch(canvas, MotionEvent.ACTION_MOVE, 450f, 400f)
        touch(canvas, MotionEvent.ACTION_UP, 600f, 500f)
        idle()
        val sticky = activity.findViewById<FrameLayout>(R.id.stickyLayer)
            .getChildAt(0).findViewById<EditText>(R.id.stickyText)
        assertNotNull("포스트잇이 놓이지 않았습니다", sticky)

        startText(activity, 700f, 650f)
        val edit = activity.inlineTextEditorForTest()!!
        edit.setText("입력 중")

        // 포스트잇을 눌러 그쪽으로 포커스를 넘긴다.
        sticky.performClick()
        idle()

        assertTrue("포스트잇으로 포커스가 넘어가지 않았습니다", sticky.hasFocus())
        assertNull("포커스가 넘어갔는데 글자 입력창이 남아 있습니다", activity.inlineTextEditorForTest())
        val page = canvas.page!!
        assertEquals("포커스가 넘어가며 입력하던 글자가 사라졌습니다: ${page.elements.map { it.text }}", 1, page.elements.size)
        assertEquals("입력 중", page.elements.first().text)
        assertEquals(canvas.screenToWorldX(700f), page.elements.first().points.first().x, 0.5f)
        assertEquals(canvas.screenToWorldY(650f), page.elements.first().points.first().y, 0.5f)
    }

    @Test
    fun `입력 중 뒤로가기는 종료하지 않고 글자를 확정한다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        startText(activity, 600f, 400f)
        activity.inlineTextEditorForTest()!!.setText("뒤로")

        activity.onBackPressedDispatcher.onBackPressed()
        idle()

        assertEquals("뒤로가기로 글자가 확정되지 않았습니다", 1, canvas.page!!.elements.size)
        assertEquals("뒤로", canvas.page!!.elements.first().text)
        assertNull("확정 뒤 입력창이 남아 있습니다", activity.inlineTextEditorForTest())
        assertFalse("입력 중 뒤로가기로 앱이 종료됐습니다", activity.isFinishing)
        // 종료 확인창이 떠도 안 된다(한 번 더 눌러야 종료).
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog == null || !dialog.isShowing)
    }

    @Test
    fun `글자 입력은 되돌리기로 사라진다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        startText(activity)
        activity.inlineTextEditorForTest()!!.setText("해설")
        activity.commitInlineTextForTest()
        assertEquals(1, canvas.page!!.elements.size)

        activity.findViewById<View>(R.id.btnUndo).performClick()
        assertEquals(0, canvas.page!!.elements.size)
        assertFalse(canvas.page!!.canUndo)
    }
}
