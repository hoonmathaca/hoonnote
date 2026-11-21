package com.hunsu.note

import android.os.Build
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import com.hunsu.note.model.Tool
import com.hunsu.note.view.InkCanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 3.5 포스트잇: 입력을 마치고 다른 곳을 누르면 깜박이는 커서가 사라지고,
 * 포스트잇을 다시 누르면 그 자리에서 이어서 입력할 수 있다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
class Sticky35Test {

    private fun activity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun addNote(activity: MainActivity): Pair<MainActivity, EditText> {
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        activity.findViewById<View>(R.id.btnNote).performClick()
        touch(canvas, MotionEvent.ACTION_DOWN, 300f, 300f)
        touch(canvas, MotionEvent.ACTION_MOVE, 500f, 450f)
        touch(canvas, MotionEvent.ACTION_UP, 600f, 500f)
        idle()
        val layer = activity.findViewById<FrameLayout>(R.id.stickyLayer)
        val noteView = layer.getChildAt(0)
        assertNotNull("포스트잇이 놓이지 않았습니다", noteView)
        return activity to noteView.findViewById(R.id.stickyText)
    }

    @Test
    fun `다른 곳을 누르면 커서가 사라지고 다시 누르면 이어서 입력한다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        val (_, edit) = addNote(activity)
        edit.setText("메모")

        // 포스트잇을 눌러 입력을 시작한다.
        edit.performClick()
        idle()
        assertTrue("포스트잇 입력이 시작되지 않았습니다", edit.hasFocus())

        // 캔버스의 다른 곳을 누르면 커서(깜박임)가 사라진다.
        // (도구 버튼을 거치지 않고 캔버스만 눌러 확인한다)
        canvas.tool = Tool.PEN
        touch(canvas, MotionEvent.ACTION_DOWN, 950f, 700f)
        touch(canvas, MotionEvent.ACTION_UP, 950f, 700f)
        idle()
        assertFalse("다른 곳을 눌렀는데 커서가 남아 있습니다", edit.hasFocus())

        // 입력한 글자는 그대로 남는다.
        assertEquals("메모", canvas.page!!.notes.first().text)

        // 포스트잇을 다시 누르면 이어서 입력할 수 있다.
        edit.performClick()
        idle()
        assertTrue("다시 눌러도 이어서 입력할 수 없습니다", edit.hasFocus())
        edit.setText("메모 추가")
        assertEquals("메모 추가", canvas.page!!.notes.first().text)
    }

    @Test
    fun `도구를 바꾸면 포스트잇 커서도 닫힌다`() {
        val activity = activity()
        val (_, edit) = addNote(activity)
        edit.performClick()
        idle()
        assertTrue(edit.hasFocus())

        activity.findViewById<View>(R.id.btnPen).performClick()
        idle()
        assertFalse(edit.hasFocus())
    }

    @Test
    fun `포스트잇을 끌어 옮기는 것은 그대로 된다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        val (_, edit) = addNote(activity)
        val page = canvas.page!!
        val note = page.notes.first()
        val startX = note.x

        val header = activity.findViewById<FrameLayout>(R.id.stickyLayer)
            .getChildAt(0).findViewById<View>(R.id.stickyHeader)
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 400f, 310f, 0)
        header.dispatchTouchEvent(down)
        down.recycle()
        val move = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 500f, 310f, 0)
        header.dispatchTouchEvent(move)
        move.recycle()

        assertTrue("끌어서 옮겨지지 않았습니다", note.x > startX)
        assertFalse("끌어 옮길 때 커서가 잡히면 안 됩니다", edit.hasFocus())
    }
}
