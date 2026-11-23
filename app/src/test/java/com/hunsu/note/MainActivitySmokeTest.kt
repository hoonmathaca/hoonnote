package com.hunsu.note

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.hunsu.note.model.Page
import com.hunsu.note.model.BackgroundStyle
import com.hunsu.note.view.PagePainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * 화면이 실제로 뜨는지 보는 스모크 테스트.
 * 레이아웃 인플레이트·툴바 연결·종료 확인 팝업·PDF 회전 그리기가 예외 없이 도는지 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
class MainActivitySmokeTest {

    @Test
    fun `메인 화면이 뜨고 툴바 버튼이 연결된다`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)

        val canvas = activity.findViewById<android.view.View>(R.id.canvasView)
        assertNotNull(canvas)
        assertNotNull(activity.findViewById<android.view.View>(R.id.btnEraserPartial))
        assertNotNull(activity.findViewById<android.view.View>(R.id.widthGrid))
        assertNotNull(activity.findViewById<android.view.View>(R.id.colorGrid))
    }

    @Test
    fun `상단 제목에 빌드된 버전이 보인다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val title = activity.findViewById<android.widget.TextView>(R.id.tvTitle)
        assertEquals("훈노트 " + BuildConfig.VERSION_NAME, title.text.toString())
    }

    @Test
    fun `형광펜과 지우개 두 종류를 눌러도 문제 없다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<android.view.View>(R.id.btnHighlighter).performClick()
        activity.findViewById<android.view.View>(R.id.btnEraserPartial).performClick()
        activity.findViewById<android.view.View>(R.id.btnEraser).performClick()
        activity.findViewById<android.view.View>(R.id.btnPen).performClick()
    }

    @Test
    fun `뒤로가기는 종료 확인 팝업을 띄운다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.onBackPressedDispatcher.onBackPressed()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull(dialog)
        assertTrue(dialog!!.isShowing)
        // 취소를 누르면 앱이 닫히지 않는다.
        assertEquals(false, activity.isFinishing)
    }

    @Test
    fun `형광펜을 고르면 형광펜 색과 굵기 단계로 바뀐다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<android.view.View>(R.id.btnHighlighter).performClick()

        val grid = activity.findViewById<android.widget.GridLayout>(R.id.colorGrid)
        // 형광펜은 노랑·연두·분홍·하늘·주황·보라 6색
        assertEquals(6, grid.childCount)

        val width = activity.findViewById<android.widget.GridLayout>(R.id.widthGrid)
        val first = width.getChildAt(0) as android.widget.TextView
        assertEquals("8", first.text.toString())
    }

    @Test
    fun `녹화 버튼은 영역 선택 팝업 없이 곧바로 녹화를 시작한다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val record = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRecord)
        record.performClick()

        // 3.1: '페이지만/전체화면' 선택 팝업은 없어지고 전체화면 녹화만 남았다.
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue("녹화 영역 선택 팝업이 아직 뜹니다", dialog == null || !dialog.isShowing)
        // 녹화 버튼은 그대로 있고, 녹화가 시작되지 않았으면 중지 표시도 아니다.
        assertEquals(activity.getString(R.string.record), record.text.toString())
    }

    @Test
    fun `부분 지우개는 문지른 구간만 잘라낸다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val canvas = activity.findViewById<com.hunsu.note.view.InkCanvasView>(R.id.canvasView)
        val page = com.hunsu.note.model.Page()
        page.applyContentLayout(com.hunsu.note.model.NoteAlign.LEFT, 0.02f)
        canvas.setPage(page, null)

        dispatch(canvas, android.view.MotionEvent.ACTION_DOWN, 100f, 100f)
        var x = 100
        while (x < 600) {
            x += 20
            dispatch(canvas, android.view.MotionEvent.ACTION_MOVE, x.toFloat(), 100f)
        }
        dispatch(canvas, android.view.MotionEvent.ACTION_UP, 600f, 100f)
        assertEquals(1, page.elements.size)

        // 가운데를 부분 지우개로 문지르면 한 획이 두 조각으로 나뉜다.
        canvas.tool = com.hunsu.note.model.Tool.ERASER_PARTIAL
        canvas.penWidth = 6f
        dispatch(canvas, android.view.MotionEvent.ACTION_DOWN, 350f, 100f)
        dispatch(canvas, android.view.MotionEvent.ACTION_UP, 350f, 100f)

        assertEquals(2, page.elements.size)
        assertTrue(page.canUndo)
    }

    @Test
    fun `선 지우개는 닿은 획을 통째로 지운다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val canvas = activity.findViewById<com.hunsu.note.view.InkCanvasView>(R.id.canvasView)
        val page = com.hunsu.note.model.Page()
        page.applyContentLayout(com.hunsu.note.model.NoteAlign.LEFT, 0.02f)
        canvas.setPage(page, null)

        dispatch(canvas, android.view.MotionEvent.ACTION_DOWN, 100f, 100f)
        dispatch(canvas, android.view.MotionEvent.ACTION_MOVE, 300f, 100f)
        dispatch(canvas, android.view.MotionEvent.ACTION_UP, 300f, 100f)
        assertEquals(1, page.elements.size)

        canvas.tool = com.hunsu.note.model.Tool.ERASER
        canvas.penWidth = 6f
        dispatch(canvas, android.view.MotionEvent.ACTION_DOWN, 200f, 100f)
        dispatch(canvas, android.view.MotionEvent.ACTION_UP, 200f, 100f)

        assertEquals(0, page.elements.size)
    }

    @Test
    fun `필압이 있으면 점마다 굵기가 달라진다`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val canvas = activity.findViewById<com.hunsu.note.view.InkCanvasView>(R.id.canvasView)
        val page = com.hunsu.note.model.Page()
        page.applyContentLayout(com.hunsu.note.model.NoteAlign.LEFT, 0.02f)
        canvas.setPage(page, null)
        canvas.penWidth = 8f

        stylus(canvas, android.view.MotionEvent.ACTION_DOWN, 100f, 100f, 0.15f)
        stylus(canvas, android.view.MotionEvent.ACTION_MOVE, 200f, 120f, 0.9f)
        stylus(canvas, android.view.MotionEvent.ACTION_MOVE, 300f, 160f, 0.5f)
        stylus(canvas, android.view.MotionEvent.ACTION_UP, 300f, 160f, 0.5f)

        assertEquals(1, page.elements.size)
        val element = page.elements.first()
        assertTrue(element.hasPressure)
        assertTrue(element.widthAt(1) > element.widthAt(0))

        // 필압 선(리본)도 캔버스에 그릴 수 있다.
        val bitmap = Bitmap.createBitmap(
            page.worldWidth.toInt(), page.worldHeight.toInt(), Bitmap.Config.ARGB_8888
        )
        PagePainter.drawPage(Canvas(bitmap), page, null, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    private fun stylus(
        view: android.view.View,
        action: Int,
        x: Float,
        y: Float,
        pressure: Float
    ) {
        val props = arrayOf(
            android.view.MotionEvent.PointerProperties().apply {
                id = 0
                toolType = android.view.MotionEvent.TOOL_TYPE_STYLUS
            }
        )
        val coords = arrayOf(
            android.view.MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                this.pressure = pressure
                size = 1f
            }
        )
        val event = android.view.MotionEvent.obtain(
            0L, 0L, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0,
            android.view.InputDevice.SOURCE_STYLUS, 0
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun dispatch(view: android.view.View, action: Int, x: Float, y: Float) {
        val event = android.view.MotionEvent.obtain(0L, 0L, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun `회전한 PDF 페이지도 그릴 수 있다`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val page = Page(
            style = BackgroundStyle.WHITE,
            pdfPageIndex = 0,
            baseAspect = 0.7071f
        )
        page.setRotation(90)
        page.applyContentLayout(com.hunsu.note.model.NoteAlign.LEFT, 0.02f)
        assertTrue(page.contentAspect > 1f)

        val bitmap = Bitmap.createBitmap(
            page.worldWidth.toInt(), page.worldHeight.toInt(), Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val background = Bitmap.createBitmap(200, 283, Bitmap.Config.ARGB_8888)
        PagePainter.drawPage(canvas, page, background, Paint(Paint.ANTI_ALIAS_FLAG))
        assertNotNull(context)
    }
}
