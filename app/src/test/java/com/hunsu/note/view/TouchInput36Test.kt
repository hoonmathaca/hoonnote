package com.hunsu.note.view

import android.graphics.Color
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sqrt

/**
 * 3.6 세로줄 버그(가끔 화면 위아래로 긴 직선이 그어지던 현상) 회귀 테스트.
 *
 * 캔버스 뷰를 화면에 붙이지 않아 배율이 1 이므로 화면 좌표 = 노트 world 좌표다.
 * 노트는 기본 2560x1440 이라 대각선의 15%(약 441px)를 넘는 한 샘플 이동은 "튄 좌표"로 본다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchInput36Test {

    /** 한 포인터의 상태 */
    private class P(
        val id: Int,
        val tool: Int,
        val x: Float,
        val y: Float,
        val pressure: Float = 1f
    )

    private fun blankPage(): Page {
        val page = Page()
        page.applyContentLayout(NoteAlign.LEFT, 0.02f)
        return page
    }

    private fun canvas(page: Page, finger: Boolean = true): InkCanvasView {
        val view = InkCanvasView(ApplicationProvider.getApplicationContext())
        view.setPage(page, null)
        view.fingerDrawingAllowed = finger
        return view
    }

    /** 여러 포인터(손가락·펜)를 담은 터치 이벤트를 만들어 보낸다. */
    private fun send(
        view: View,
        actionMasked: Int,
        vararg pointers: P,
        actionIndex: Int = 0,
        time: Long = 0L
    ) {
        val props = Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].id
                toolType = pointers[i].tool
            }
        }
        val coords = Array(pointers.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x
                y = pointers[i].y
                pressure = pointers[i].pressure
                size = 1f
            }
        }
        val action = if (actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            actionMasked == MotionEvent.ACTION_POINTER_UP
        ) {
            actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            actionMasked
        }
        val event = MotionEvent.obtain(
            0L, time, action, pointers.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun pen(x: Float, y: Float, pressure: Float = 1f) =
        P(0, MotionEvent.TOOL_TYPE_STYLUS, x, y, pressure)

    private fun finger(x: Float, y: Float) = P(0, MotionEvent.TOOL_TYPE_FINGER, x, y)

    /** 한 요소 안에서 이어진 두 점 사이의 가장 긴 거리 (world 좌표 = 화면 좌표) */
    private fun longestSegment(el: DrawElement): Float {
        var longest = 0f
        for (i in 0 until el.points.size - 1) {
            val a = el.points[i]
            val b = el.points[i + 1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            longest = maxOf(longest, sqrt(dx * dx + dy * dy))
        }
        return longest
    }

    private fun yRange(elements: List<DrawElement>): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (el in elements) {
            for (p in el.points) {
                lo = minOf(lo, p.y)
                hi = maxOf(hi, p.y)
            }
        }
        return lo to hi
    }

    // ------------------------------------------------------------ 포인터 분리

    @Test
    fun `손바닥이 먼저 닿아 펜이 두 번째 포인터여도 손바닥 좌표가 획에 섞이지 않는다`() {
        val page = blankPage()
        // 펜 전용 모드: 손바닥(손가락)으로는 그리지 않는다.
        val view = canvas(page, finger = false)
        view.tool = Tool.PEN
        view.penWidth = 6f

        // 손바닥이 먼저 닿는다 (획이 시작되지 않는다).
        send(view, MotionEvent.ACTION_DOWN, P(0, MotionEvent.TOOL_TYPE_FINGER, 30f, 60f))
        // 그 위로 펜이 내려온다 — 펜(index 1)으로 획이 시작되어야 한다.
        send(
            view, MotionEvent.ACTION_POINTER_DOWN,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 30f, 60f), P(1, MotionEvent.TOOL_TYPE_STYLUS, 500f, 300f),
            actionIndex = 1, time = 8L
        )
        // 펜은 제자리에서 조금씩 움직이고, 손바닥은 화면 아래로 미끄러진다.
        send(
            view, MotionEvent.ACTION_MOVE,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 30f, 1200f), P(1, MotionEvent.TOOL_TYPE_STYLUS, 520f, 310f),
            time = 16L
        )
        send(
            view, MotionEvent.ACTION_MOVE,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 40f, 1400f), P(1, MotionEvent.TOOL_TYPE_STYLUS, 540f, 320f),
            time = 24L
        )
        send(
            view, MotionEvent.ACTION_UP, P(1, MotionEvent.TOOL_TYPE_STYLUS, 560f, 330f),
            time = 32L
        )

        assertEquals("펜으로 그린 획 하나만 남아야 합니다", 1, page.elements.size)
        val el = page.elements.first()
        assertEquals(ElementKind.STROKE, el.kind)
        for (p in el.points) {
            assertTrue("손바닥 좌표가 획에 섞였습니다: (${p.x}, ${p.y})", p.y in 290f..340f)
            assertTrue("손바닥 좌표가 획에 섞였습니다: (${p.x}, ${p.y})", p.x in 490f..570f)
        }
        assertTrue("펜 좌표가 충분히 모이지 않았습니다", el.points.size >= 3)
        assertTrue("획이 세로로 길게 그어졌습니다", longestSegment(el) < 40f)
    }

    @Test
    fun `펜으로 쓰는 중 손바닥이 닿아도 획이 이어지고 손바닥 좌표는 들어가지 않는다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.PEN

        send(view, MotionEvent.ACTION_DOWN, pen(400f, 300f))
        send(view, MotionEvent.ACTION_MOVE, pen(420f, 320f), time = 8L)
        // 펜이 닿은 상태에서 손바닥이 내려온다.
        send(
            view, MotionEvent.ACTION_POINTER_DOWN,
            pen(420f, 320f), P(1, MotionEvent.TOOL_TYPE_FINGER, 60f, 1200f),
            actionIndex = 1, time = 16L
        )
        send(
            view, MotionEvent.ACTION_MOVE,
            pen(440f, 340f), P(1, MotionEvent.TOOL_TYPE_FINGER, 70f, 1250f),
            time = 24L
        )
        // 손바닥이 먼저 떨어진다 — 펜 획은 그대로 이어져야 한다.
        send(
            view, MotionEvent.ACTION_POINTER_UP,
            pen(440f, 340f), P(1, MotionEvent.TOOL_TYPE_FINGER, 70f, 1250f),
            actionIndex = 1, time = 32L
        )
        send(view, MotionEvent.ACTION_MOVE, pen(460f, 360f), time = 40L)
        send(view, MotionEvent.ACTION_UP, pen(480f, 380f), time = 48L)

        assertEquals("쓰던 획이 사라졌습니다", 1, page.elements.size)
        val el = page.elements.first()
        val (lo, hi) = yRange(page.elements)
        assertTrue("손바닥 좌표(y=1200 부근)가 획에 들어갔습니다", hi < 400f && lo > 250f)
        assertEquals(480f, el.points.last().x, 0.5f)
        assertEquals(380f, el.points.last().y, 0.5f)
    }

    // ------------------------------------------------------------ 튄 좌표

    @Test
    fun `위 끝으로 갑자기 튄 좌표로 화면 세로줄이 그어지지 않는다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.PEN

        send(view, MotionEvent.ACTION_DOWN, finger(400f, 600f))
        send(view, MotionEvent.ACTION_MOVE, finger(402f, 590f), time = 8L)
        // 한 샘플 만에 y=0 으로 튄다 (예전에는 여기서 위아래로 긴 선이 그어졌다).
        send(view, MotionEvent.ACTION_MOVE, finger(404f, 0f), time = 16L)
        send(view, MotionEvent.ACTION_UP, finger(404f, 0f), time = 24L)

        assertTrue("획이 남지 않았습니다", page.elements.isNotEmpty())
        for (el in page.elements) {
            assertTrue(
                "튄 좌표로 긴 선이 그어졌습니다 (${longestSegment(el)}px)",
                longestSegment(el) <= 441f
            )
        }
        val (lo, _) = yRange(page.elements)
        assertTrue("y=0 좌표가 획에 들어갔습니다", lo >= 500f)
    }

    @Test
    fun `화면 밖 좌표는 획에 들어가지 않고 그 뒤 필기는 정상으로 이어진다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.PEN

        send(view, MotionEvent.ACTION_DOWN, finger(400f, 600f))
        send(view, MotionEvent.ACTION_MOVE, finger(410f, 610f), time = 8L)
        // 호버·손바닥에서 흔히 오는 범위 밖 좌표
        send(view, MotionEvent.ACTION_MOVE, finger(410f, 99999f), time = 16L)
        send(view, MotionEvent.ACTION_MOVE, finger(-5000f, 620f), time = 24L)
        send(view, MotionEvent.ACTION_MOVE, finger(420f, 620f), time = 32L)
        send(view, MotionEvent.ACTION_UP, finger(430f, 630f), time = 40L)

        assertEquals("정상 필기가 끊기거나 갈라졌습니다", 1, page.elements.size)
        val el = page.elements.first()
        for (p in el.points) {
            assertTrue("범위 밖 좌표가 들어갔습니다: (${p.x}, ${p.y})", p.y in 580f..640f)
            assertTrue("범위 밖 좌표가 들어갔습니다: (${p.x}, ${p.y})", p.x in 390f..440f)
        }
        assertEquals(430f, el.points.last().x, 0.5f)
    }

    @Test
    fun `도형도 튄 좌표로 엉뚱하게 커지지 않는다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.RECT

        send(view, MotionEvent.ACTION_DOWN, finger(400f, 300f))
        send(view, MotionEvent.ACTION_MOVE, finger(420f, 320f), time = 8L)
        send(view, MotionEvent.ACTION_MOVE, finger(430f, 99999f), time = 16L)
        send(view, MotionEvent.ACTION_MOVE, finger(500f, 400f), time = 24L)
        send(view, MotionEvent.ACTION_UP, finger(500f, 400f), time = 32L)

        assertEquals(1, page.elements.size)
        val el = page.elements.first()
        assertEquals(ElementKind.RECT, el.kind)
        assertEquals(500f, el.points.last().x, 0.5f)
        assertEquals(400f, el.points.last().y, 0.5f)
    }

    @Test
    fun `필압 획을 끊어도 점과 필압 개수가 어긋나지 않는다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.PEN
        view.pressureEnabled = true

        send(view, MotionEvent.ACTION_DOWN, pen(400f, 300f, 0.6f))
        send(view, MotionEvent.ACTION_MOVE, pen(410f, 310f, 0.7f), time = 8L)
        // 펜이 화면 반대편으로 튄다 → 획을 끊고 새 획으로 이어 간다.
        send(view, MotionEvent.ACTION_MOVE, pen(1400f, 900f, 0.8f), time = 16L)
        send(view, MotionEvent.ACTION_MOVE, pen(1410f, 910f, 0.8f), time = 24L)
        send(view, MotionEvent.ACTION_UP, pen(1420f, 920f, 0.8f), time = 32L)

        assertTrue("획이 남지 않았습니다", page.elements.isNotEmpty())
        for (el in page.elements) {
            assertTrue(
                "점 개수와 필압 개수가 어긋났습니다",
                el.pressures.isEmpty() || el.pressures.size == el.points.size
            )
            assertTrue("튄 좌표로 긴 선이 그어졌습니다", longestSegment(el) <= 441f)
        }
    }

    // ------------------------------------------------------------ 제스처

    @Test
    fun `확대 중 손가락이 하나 떨어져도 화면이 튀지 않는다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.PEN
        val beforeX = view.offsetX
        val beforeY = view.offsetY
        val beforeScale = view.scale

        send(view, MotionEvent.ACTION_DOWN, P(0, MotionEvent.TOOL_TYPE_FINGER, 200f, 200f))
        send(
            view, MotionEvent.ACTION_POINTER_DOWN,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 200f, 200f), P(1, MotionEvent.TOOL_TYPE_FINGER, 500f, 400f),
            actionIndex = 1, time = 8L
        )
        send(
            view, MotionEvent.ACTION_POINTER_DOWN,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 200f, 200f), P(1, MotionEvent.TOOL_TYPE_FINGER, 500f, 400f),
            P(2, MotionEvent.TOOL_TYPE_FINGER, 600f, 600f),
            actionIndex = 2, time = 16L
        )
        // 0번 손가락이 떨어진다 → 남은 두 손가락(500,400)·(600,600)으로 기준을 다시 잡아야 한다.
        send(
            view, MotionEvent.ACTION_POINTER_UP,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 200f, 200f), P(1, MotionEvent.TOOL_TYPE_FINGER, 500f, 400f),
            P(2, MotionEvent.TOOL_TYPE_FINGER, 600f, 600f),
            actionIndex = 0, time = 24L
        )
        send(
            view, MotionEvent.ACTION_MOVE,
            P(1, MotionEvent.TOOL_TYPE_FINGER, 500f, 400f), P(2, MotionEvent.TOOL_TYPE_FINGER, 600f, 600f),
            time = 32L
        )

        assertEquals("화면이 가로로 튀었습니다", beforeX, view.offsetX, 1f)
        assertEquals("화면이 세로로 튀었습니다", beforeY, view.offsetY, 1f)
        assertEquals("배율이 저절로 바뀌었습니다", beforeScale, view.scale, 0.01f)
        assertEquals("확대·이동 중에 획이 그어졌습니다", 0, page.elements.size)
    }

    @Test
    fun `펜 전용 모드에서도 두 손가락 확대는 된다`() {
        val page = blankPage()
        val view = canvas(page, finger = false)
        view.tool = Tool.PEN
        val startScale = view.scale

        send(view, MotionEvent.ACTION_DOWN, P(0, MotionEvent.TOOL_TYPE_FINGER, 400f, 400f))
        send(
            view, MotionEvent.ACTION_POINTER_DOWN,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 400f, 400f), P(1, MotionEvent.TOOL_TYPE_FINGER, 500f, 400f),
            actionIndex = 1, time = 8L
        )
        send(
            view, MotionEvent.ACTION_MOVE,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 300f, 400f), P(1, MotionEvent.TOOL_TYPE_FINGER, 700f, 400f),
            time = 16L
        )
        send(
            view, MotionEvent.ACTION_POINTER_UP,
            P(0, MotionEvent.TOOL_TYPE_FINGER, 300f, 400f), P(1, MotionEvent.TOOL_TYPE_FINGER, 700f, 400f),
            actionIndex = 1, time = 24L
        )
        send(view, MotionEvent.ACTION_UP, P(0, MotionEvent.TOOL_TYPE_FINGER, 300f, 400f), time = 32L)

        assertTrue("펜 전용 모드에서 두 손가락 확대가 되지 않았습니다", view.scale > startScale)
        assertEquals("펜 전용 모드에서 손가락으로 획이 그어졌습니다", 0, page.elements.size)
    }

    @Test
    fun `일반 필기는 그대로 한 획으로 남는다`() {
        val page = blankPage()
        val view = canvas(page)
        view.tool = Tool.PEN
        view.penColor = Color.BLACK
        view.penWidth = 8f

        send(view, MotionEvent.ACTION_DOWN, finger(400f, 300f))
        send(view, MotionEvent.ACTION_MOVE, finger(420f, 310f), time = 8L)
        send(view, MotionEvent.ACTION_MOVE, finger(440f, 350f), time = 16L)
        send(view, MotionEvent.ACTION_MOVE, finger(500f, 380f), time = 24L)
        send(view, MotionEvent.ACTION_UP, finger(520f, 400f), time = 32L)

        assertEquals(1, page.elements.size)
        val el = page.elements.first()
        assertEquals(5, el.points.size)
        assertEquals(400f, el.points.first().x, 0.01f)
        assertEquals(520f, el.points.last().x, 0.01f)
        assertTrue(page.canUndo)
    }
}
