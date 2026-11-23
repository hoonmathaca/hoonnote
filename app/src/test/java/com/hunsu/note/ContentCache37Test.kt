package com.hunsu.note

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.Page
import com.hunsu.note.model.Tool
import com.hunsu.note.view.InkCanvasView
import com.hunsu.note.view.PagePainter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 3.7 필기 성능: 완성된 내용을 화면 크기 비트맵에 구워 두고, 그리는 동안에는 그 비트맵 + 지금 획만 그린다.
 * - 획을 긋는 동안 페이지 전체를 다시 긁지 않는다(캐시 재생성 0회).
 * - 캐시로 그린 화면이 페이지를 그대로 그린 화면과 픽셀 단위로 같다.
 * - 되돌리기·지우개처럼 내용이 바뀌면 캐시를 다시 만든다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentCache37Test {

    private val viewW = 1280
    private val viewH = 720

    private fun newView(): InkCanvasView {
        val page = Page()
        page.applyContentLayout(NoteAlign.LEFT, 0.02f)
        val view = InkCanvasView(ApplicationProvider.getApplicationContext())
        view.setPage(page, null)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(viewW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewH, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, viewW, viewH)
        view.penWidth = 24f
        view.tool = Tool.PEN
        return view
    }

    private fun drawToBitmap(view: InkCanvasView): Bitmap {
        val bmp = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        return bmp
    }

    private fun page(view: InkCanvasView): Page = view.page!!

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun cacheIsReusedWhileDrawingStroke() {
        val view = newView()
        drawToBitmap(view)
        assertEquals("첫 그리기에서 캐시를 한 번 긁는다", 1, view.contentRebuildCount)

        touch(view, MotionEvent.ACTION_DOWN, 200f, 300f)
        drawToBitmap(view)
        assertEquals("획 시작에 다시 긁지 않는다", 1, view.contentRebuildCount)

        for (i in 1..20) {
            touch(view, MotionEvent.ACTION_MOVE, 200f + i * 20f, 300f + (i % 3) * 3f)
            drawToBitmap(view)
        }
        assertEquals("획을 긋는 동안 페이지를 다시 긁지 않는다", 1, view.contentRebuildCount)

        touch(view, MotionEvent.ACTION_UP, 600f, 300f)
        val after = drawToBitmap(view)
        assertEquals("획을 마쳐도 전체를 다시 긁지 않는다(캐시 위에 덧그린다)", 1, view.contentRebuildCount)

        // 화면에 획이 실제로 남아 있다 (터치 좌표 = 화면 좌표)
        assertTrue("획이 화면에 보이지 않는다", Color.red(after.getPixel(400, 300)) < 60)
    }

    @Test
    fun cachedScreenMatchesFullRedraw() {
        val view = newView()
        repeat(3) { i ->
            // 두 번째는 형광펜 — 반투명 요소를 캐시 위에 덧그리는 경우도 같아야 한다.
            view.tool = if (i == 1) Tool.HIGHLIGHTER else Tool.PEN
            drawToBitmap(view)
            touch(view, MotionEvent.ACTION_DOWN, 150f + i * 60f, 250f)
            touch(view, MotionEvent.ACTION_MOVE, 250f + i * 60f, 300f)
            touch(view, MotionEvent.ACTION_UP, 350f + i * 60f, 250f)
            drawToBitmap(view)
        }
        val cached = drawToBitmap(view)

        // 같은 변환으로 페이지를 그대로 그린 화면
        val direct = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(direct)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(view.offsetX, view.offsetY)
        canvas.scale(view.scale, view.scale)
        canvas.clipRect(0f, 0f, page(view).worldWidth, page(view).worldHeight)
        PagePainter.drawPage(canvas, page(view), null, Paint(Paint.ANTI_ALIAS_FLAG))
        canvas.restore()

        var big = 0
        var worst = 0
        for (y in 0 until viewH step 3) {
            for (x in 0 until viewW step 3) {
                val a = cached.getPixel(x, y)
                val b = direct.getPixel(x, y)
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                    if (d > worst) worst = d
                    if (d > 8) {
                        big++
                        break
                    }
                }
            }
        }
        assertEquals("캐시 화면과 전체를 그린 화면이 다르다 (최대 차이 $worst)", 0, big)
    }

    @Test
    fun undoAndEraseRebuildCache() {
        val view = newView()
        touch(view, MotionEvent.ACTION_DOWN, 200f, 300f)
        touch(view, MotionEvent.ACTION_MOVE, 400f, 300f)
        touch(view, MotionEvent.ACTION_UP, 400f, 300f)
        drawToBitmap(view)
        assertEquals(1, view.contentRebuildCount)
        assertEquals(1, page(view).elements.size)

        assertTrue(view.undo())
        drawToBitmap(view)
        assertEquals("되돌리면 캐시를 다시 긁는다", 2, view.contentRebuildCount)
        assertEquals(0, page(view).elements.size)

        assertTrue(view.redo())
        drawToBitmap(view)
        assertEquals(3, view.contentRebuildCount)
        assertEquals(1, page(view).elements.size)
    }

    /** 화면을 확대·이동하면(제스처가 끝난 뒤) 지금 배율로 캐시를 다시 긁는다. */
    @Test
    fun zoomRebuildsOnNewTransform() {
        val view = newView()
        drawToBitmap(view)
        assertEquals(1, view.contentRebuildCount)
        view.fitPage()
        drawToBitmap(view)
        assertEquals("변환이 그대로면 다시 긁지 않는다", 1, view.contentRebuildCount)
        view.panBy(-40f, 0f)
        drawToBitmap(view)
        assertEquals("화면을 밀면 그 배율로 다시 긁는다", 2, view.contentRebuildCount)
    }

    /**
     * 그리는 중에도 화면에 획이 보여야 한다.
     * 3.6 에서 손을 떼기 전에는 다시 그리라고 알리지 않아(invalidate 누락) 획이 손을 뗀 뒤에야
     * 나타났다. 3.7 은 움직일 때마다 지나간 구간을 다시 그리게 한다.
     */
    @Test
    fun strokePreviewIsDrawnBeforeFingerUp() {
        val view = newView()
        drawToBitmap(view)
        touch(view, MotionEvent.ACTION_DOWN, 200f, 300f)
        for (i in 1..10) touch(view, MotionEvent.ACTION_MOVE, 200f + i * 20f, 300f)
        val during = drawToBitmap(view)
        assertTrue("손을 떼기 전 획이 보이지 않는다", Color.red(during.getPixel(300, 300)) < 60)
        assertEquals("미리보기 중에는 페이지를 다시 긁지 않는다", 1, view.contentRebuildCount)
    }

    /** 움직일 때 화면에 다시 그리라고 알려야 한다(3.6 에서 이 호출이 빠져 획이 늦게 나타났다). */
    @Test
    fun moveEventAsksForRedraw() {
        val view = newView()
        drawToBitmap(view)
        touch(view, MotionEvent.ACTION_DOWN, 200f, 300f)
        val before = view.liveInvalidateCount
        touch(view, MotionEvent.ACTION_MOVE, 260f, 300f)
        assertTrue("움직였는데 다시 그리라고 알리지 않았다", view.liveInvalidateCount > before)
    }

    @Test
    fun cacheBitmapIsCreatedOnlyOnce() {
        val view = newView()
        drawToBitmap(view)
        val first = view.contentRebuildCount
        assertNotNull(view.page)
        drawToBitmap(view)
        assertEquals(first, view.contentRebuildCount)
    }
}
