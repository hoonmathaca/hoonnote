package com.hunsu.note.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.hunsu.note.model.DrawElement
import com.hunsu.note.model.ElementKind
import com.hunsu.note.model.Page
import com.hunsu.note.model.Pt
import com.hunsu.note.model.Tool
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 판서 캔버스.
 * - 한 손가락(또는 스타일러스): 그리기
 * - 두 손가락: 확대/축소 + 이동(팬) — 3.4 부터 화면 이동은 이 제스처만 쓴다.
 * - 좌표계: 노트 world 좌표(예: 2560 x 1440), 화면에는 scale/offset 으로 변환
 *
 * 지우개는 두 종류다.
 * - Tool.ERASER: 문지른 곳에 걸린 획을 통째로 지운다(선 전체 지우개).
 * - Tool.ERASER_PARTIAL: 문지른 부분만 잘라낸다(부분 지우개). 한 획이 두 동강 나면
 *   남은 조각들이 각각의 획으로 다시 만들어진다.
 * 덮개 떡메모지(COVER)·글자(TEXT)는 조각내지 않고 통째로 지운다. 덮개는 이름자로 문질러도
 * 지워지지 않도록 테두리 가까이를 문지를 때만 지운다(위에 쓴 필기를 지우다 덮개가 사라지는 일 방지).
 */
class InkCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var tool: Tool = Tool.PEN
        set(value) {
            field = value
            invalidate()
        }

    var penColor: Int = Color.BLACK
    var penWidth: Float = 6f

    /** true 면 손가락으로도 그릴 수 있다 (스타일러스 없는 기기 테스트용) */
    var fingerDrawingAllowed: Boolean = true

    /** 필압 감지: 스타일러스가 압력을 지원하면 선 굵기가 자연스럽게 변한다. */
    var pressureEnabled: Boolean = true

    var page: Page? = null
        private set

    /** PDF/이미지 페이지의 미리 렌더링된 배경 */
    var backgroundBitmap: Bitmap? = null

    var scale: Float = 1f
        private set
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    private var fitScale = 1f

    var onTransformChanged: (() -> Unit)? = null
    var onPageEdited: (() -> Unit)? = null
    var onStrokeStarted: (() -> Unit)? = null

    /** 포스트잇 도구로 사각형을 그렸을 때 (world 좌표). */
    var onNoteRect: ((Float, Float, Float, Float) -> Unit)? = null

    /** 글자 도구로 캔버스를 눌렀을 때 (world 좌표). */
    var onTextTap: ((Float, Float) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var mode = MODE_NONE
    private var current: DrawElement? = null
    private var erasePushed = false
    private var lastEraseX = 0f
    private var lastEraseY = 0f

    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDistance = 0f

    /** 포스트잇·글자 도구의 시작점 (world 좌표) */
    private var startWorldX = 0f
    private var startWorldY = 0f
    private var lastWorldX = 0f
    private var lastWorldY = 0f

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAW = 1
        private const val MODE_ERASE = 2
        private const val MODE_ERASE_PARTIAL = 3
        private const val MODE_GESTURE = 4
        private const val MODE_NOTE = 6
        private const val MODE_TEXT = 7

        private const val MIN_STROKE_STEP = 0.7f
        private const val ERASE_EXTRA = 14f

        /** 글자 도구는 이 안에서 손을 떼면 그 자리에 글자를 넣는다 (world 좌표) */
        private const val TEXT_TAP_SLOP = 24f

        /** 미리보기 점선 색 (하늘색 계열) */
        private const val PREVIEW_COLOR = 0xFF29B6F6.toInt()
    }

    init {
        isFocusable = true
        isClickable = true
    }

    // ---------------------------------------------------------------- 페이지

    fun setPage(page: Page?, background: Bitmap?) {
        this.page = page
        this.backgroundBitmap = background
        this.current = null
        this.mode = MODE_NONE
        fitPage()
        invalidate()
    }

    fun setPageBackground(bitmap: Bitmap?) {
        backgroundBitmap = bitmap
        invalidate()
    }

    fun refresh() {
        invalidate()
        onTransformChanged?.invoke()
    }

    fun undo(): Boolean {
        val p = page ?: return false
        val ok = p.undo()
        if (ok) invalidate()
        return ok
    }

    fun redo(): Boolean {
        val p = page ?: return false
        val ok = p.redo()
        if (ok) invalidate()
        return ok
    }

    // ---------------------------------------------------------------- 좌표 변환

    fun screenToWorldX(x: Float): Float = (x - offsetX) / scale
    fun screenToWorldY(y: Float): Float = (y - offsetY) / scale

    /** 화면(뷰) 좌표계에서 지금 노트 영역이 차지하는 사각형. 녹화 영역 계산에 쓴다. */
    fun fitPage() {
        val p = page ?: return
        if (width <= 0 || height <= 0) return
        val pad = 12f
        val sx = (width - pad * 2f) / p.worldWidth
        val sy = (height - pad * 2f) / p.worldHeight
        val s = min(sx, sy).coerceAtLeast(0.0001f)
        fitScale = s
        scale = s
        offsetX = (width - p.worldWidth * s) / 2f
        offsetY = (height - p.worldHeight * s) / 2f
        invalidate()
        onTransformChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitPage()
    }

    // ---------------------------------------------------------------- 그리기

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = page ?: return
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, p.worldWidth, p.worldHeight)

        PagePainter.drawPage(canvas, p, backgroundBitmap, paint)

        // 그리는 중인 요소 미리보기
        current?.let { PagePainter.drawElement(canvas, it, paint) }
        if (mode == MODE_NOTE) drawNotePreview(canvas)

        canvas.restore()
    }

    /** 포스트잇을 그리는 중의 점선 미리보기 사각형. */
    private fun drawNotePreview(canvas: Canvas) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f / scale.coerceAtLeast(0.0001f)
        paint.color = PREVIEW_COLOR
        paint.pathEffect = DashPathEffect(
            floatArrayOf(9f / scale.coerceAtLeast(0.0001f), 7f / scale.coerceAtLeast(0.0001f)), 0f
        )
        val r = PagePainter.rectOf(Pt(startWorldX, startWorldY), Pt(lastWorldX, lastWorldY))
        canvas.drawRect(r, paint)
        paint.pathEffect = null
    }

    // ---------------------------------------------------------------- 터치

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (page == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = MODE_NONE
                val wx = screenToWorldX(event.x)
                val wy = screenToWorldY(event.y)
                startWorldX = wx
                startWorldY = wy
                lastWorldX = wx
                lastWorldY = wy
                if (!canStartStroke(event.getToolType(0))) return true
                erasePushed = false
                when (tool) {
                    Tool.NOTE -> {
                        // 사각형을 그려 포스트잇을 놓는 중
                        mode = MODE_NOTE
                        invalidate()
                    }

                    Tool.TEXT -> {
                        mode = MODE_TEXT
                    }

                    Tool.ERASER -> {
                        mode = MODE_ERASE
                        eraseAt(wx, wy)
                    }

                    Tool.ERASER_PARTIAL -> {
                        mode = MODE_ERASE_PARTIAL
                        lastEraseX = wx
                        lastEraseY = wy
                        erasePartialAt(wx, wy)
                    }

                    else -> {
                        mode = MODE_DRAW
                        current = createElement(wx, wy, event)
                        onStrokeStarted?.invoke()
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    cancelCurrent()
                    mode = MODE_GESTURE
                    lastMidX = (event.getX(0) + event.getX(1)) / 2f
                    lastMidY = (event.getY(0) + event.getY(1)) / 2f
                    lastDistance = distanceOf(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    MODE_GESTURE -> updateGesture(event)

                    MODE_NOTE -> {
                        lastWorldX = screenToWorldX(event.x)
                        lastWorldY = screenToWorldY(event.y)
                        invalidate()
                    }

                    MODE_DRAW -> {
                        extendCurrent(event)
                        invalidate()
                    }

                    MODE_ERASE -> eraseAt(screenToWorldX(event.x), screenToWorldY(event.y))

                    MODE_ERASE_PARTIAL -> {
                        val wx = screenToWorldX(event.x)
                        val wy = screenToWorldY(event.y)
                        erasePartialSweep(lastEraseX, lastEraseY, wx, wy)
                        lastEraseX = wx
                        lastEraseY = wy
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 < 2) {
                    mode = MODE_NONE
                    lastDistance = 0f
                }
            }

            MotionEvent.ACTION_UP -> {
                val wx = screenToWorldX(event.x)
                val wy = screenToWorldY(event.y)
                when (mode) {
                    MODE_DRAW -> finishCurrent()
                    MODE_NOTE -> {
                        lastWorldX = wx
                        lastWorldY = wy
                        val handler = onNoteRect
                        invalidate()
                        handler?.invoke(startWorldX, startWorldY, wx, wy)
                    }

                    MODE_TEXT -> {
                        // 손을 크게 움직이지 않고 누른 자리에 글자를 넣는다.
                        val dx = wx - startWorldX
                        val dy = wy - startWorldY
                        if (sqrt(dx * dx + dy * dy) <= TEXT_TAP_SLOP) {
                            onTextTap?.invoke(startWorldX, startWorldY)
                        }
                    }
                }
                mode = MODE_NONE
                lastDistance = 0f
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelCurrent()
                mode = MODE_NONE
                lastDistance = 0f
            }
        }
        return true
    }

    private fun canStartStroke(toolType: Int): Boolean {
        val isPen = toolType == MotionEvent.TOOL_TYPE_STYLUS ||
            toolType == MotionEvent.TOOL_TYPE_ERASER
        return isPen || fingerDrawingAllowed
    }

    /** 기기가 보고하는 최대 압력 (압력 미지원이면 1). */
    private fun maxPressureOf(event: MotionEvent): Float = try {
        val max = event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE)?.max ?: 1f
        if (max > 0.0001f) max else 1f
    } catch (e: Exception) {
        1f
    }

    /** 스타일러스 압력을 0~1 로 정규화한다. 압력을 지원하지 않는 기기·손가락은 1 이 된다. */
    private fun pressureOf(event: MotionEvent): Float {
        val raw = try {
            event.getPressure(0)
        } catch (e: Exception) {
            return 1f
        }
        return (raw / maxPressureOf(event)).coerceIn(0f, 1f)
    }

    private fun createElement(x: Float, y: Float, event: MotionEvent): DrawElement {
        val kind = when (tool) {
            Tool.PEN -> ElementKind.STROKE
            Tool.HIGHLIGHTER -> ElementKind.HIGHLIGHTER
            Tool.LINE -> ElementKind.LINE
            Tool.DASHED_LINE -> ElementKind.DASHED_LINE
            Tool.RECT -> ElementKind.RECT
            Tool.OVAL -> ElementKind.OVAL
            Tool.TRIANGLE -> ElementKind.TRIANGLE
            Tool.COVER -> ElementKind.COVER
            Tool.ERASER, Tool.ERASER_PARTIAL, Tool.NOTE, Tool.TEXT -> ElementKind.STROKE
        }
        val width = when (kind) {
            ElementKind.HIGHLIGHTER -> max(penWidth, 6f)
            ElementKind.STROKE -> penWidth
            ElementKind.COVER -> 2f
            else -> max(penWidth, 2f)
        }
        val points = ArrayList<Pt>()
        points.add(Pt(x, y))
        if (PagePainter.isShape(kind)) points.add(Pt(x, y))
        val element = DrawElement(kind, penColor, width, points)
        if (usePressureFor(event) && kind == ElementKind.STROKE) {
            element.pressures.add(pressureOf(event))
        }
        return element
    }

    /** 이 입력에 필압을 적용할지: 설정이 켜져 있고, 스타일러스로 그린 획일 때 */
    private fun usePressureFor(event: MotionEvent): Boolean {
        if (!pressureEnabled) return false
        val toolType = event.getToolType(0)
        return toolType == MotionEvent.TOOL_TYPE_STYLUS
    }

    private fun extendCurrent(event: MotionEvent) {
        val el = current ?: return
        if (PagePainter.isShape(el.kind)) {
            el.points[el.points.size - 1] =
                Pt(screenToWorldX(event.x), screenToWorldY(event.y))
            el.cachedPath = null
            return
        }
        val maxPressure = maxPressureOf(event)
        for (h in 0 until event.historySize) {
            val historicalPressure = try {
                (event.getHistoricalPressure(0, h) / maxPressure).coerceIn(0f, 1f)
            } catch (e: Exception) {
                pressureOf(event)
            }
            addPoint(
                el,
                screenToWorldX(event.getHistoricalX(0, h)),
                screenToWorldY(event.getHistoricalY(0, h)),
                historicalPressure
            )
        }
        addPoint(el, screenToWorldX(event.x), screenToWorldY(event.y), pressureOf(event))
    }

    private fun addPoint(el: DrawElement, wxCandidate: Float, wyCandidate: Float, pressure: Float) {
        val last = el.points.lastOrNull() ?: return
        val dx = wxCandidate - last.x
        val dy = wyCandidate - last.y
        if (sqrt(dx * dx + dy * dy) < MIN_STROKE_STEP) return
        val trackPressure = el.pressures.isNotEmpty() && el.pressures.size == el.points.size
        el.points.add(Pt(wxCandidate, wyCandidate))
        if (trackPressure) el.pressures.add(pressure)
        el.cachedPath = null
    }

    private fun finishCurrent() {
        val el = current ?: return
        current = null
        val p = page ?: return
        if (PagePainter.isAtomic(el.kind)) {
            val a = el.points.first()
            val b = el.points.last()
            val dx = a.x - b.x
            val dy = a.y - b.y
            if (sqrt(dx * dx + dy * dy) < 3f) {
                invalidate()
                return
            }
        } else if (el.points.size < 2) {
            el.points.add(Pt(el.points[0].x + 0.6f, el.points[0].y + 0.6f))
            if (el.pressures.isNotEmpty()) el.pressures.add(el.pressures.last())
            el.cachedPath = null
        }
        if (el.pressures.size != el.points.size) el.pressures.clear()
        p.pushUndo()
        p.elements.add(el)
        invalidate()
        onPageEdited?.invoke()
    }

    private fun cancelCurrent() {
        current = null
        if (mode == MODE_DRAW) invalidate()
    }

    // ---------------------------------------------------------------- 제스처

    private fun distanceOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun updateGesture(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val midX = (event.getX(0) + event.getX(1)) / 2f
        val midY = (event.getY(0) + event.getY(1)) / 2f
        val distance = distanceOf(event)

        if (lastDistance > 0f && distance > 0f) {
            val factor = distance / lastDistance
            if (abs(factor - 1f) > 0.001f) {
                val newScale = (scale * factor).coerceIn(fitScale * 0.5f, fitScale * 10f)
                val applied = newScale / scale
                offsetX = midX - (midX - offsetX) * applied
                offsetY = midY - (midY - offsetY) * applied
                scale = newScale
            }
        }

        offsetX += midX - lastMidX
        offsetY += midY - lastMidY

        lastMidX = midX
        lastMidY = midY
        lastDistance = distance
        invalidate()
        onTransformChanged?.invoke()
    }

    // ---------------------------------------------------------------- 지우개

    /** 선 전체 지우개: 문지른 곳에 걸린 획을 통째로 지운다. */
    private fun eraseAt(x: Float, y: Float) {
        val p = page ?: return
        var removed: MutableList<DrawElement>? = null
        for (el in p.elements) {
            if (hitTest(el, x, y)) {
                if (removed == null) removed = ArrayList()
                removed.add(el)
            }
        }
        val list = removed ?: return
        if (!erasePushed) {
            p.pushUndo()
            erasePushed = true
        }
        p.elements.removeAll(list)
        invalidate()
        onPageEdited?.invoke()
    }

    /** 부분 지우개 반지름 (world 좌표) */
    private fun partialEraseRadius(): Float = max(penWidth, 6f) * 1.1f + 6f

    /** 빠르게 문질러도 끊기지 않도록 지난 지점과 지금 지점 사이를 촘촘히 지운다. */
    private fun erasePartialSweep(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val radius = partialEraseRadius()
        val dx = toX - fromX
        val dy = toY - fromY
        val length = sqrt(dx * dx + dy * dy)
        if (length < 0.001f) {
            erasePartialAt(toX, toY)
            return
        }
        val step = max(radius * 0.5f, 1f)
        val count = (length / step).toInt().coerceAtLeast(1)
        for (i in 1..count) {
            val t = i.toFloat() / count
            erasePartialAt(fromX + dx * t, fromY + dy * t)
        }
    }

    /**
     * 부분 지우개: 문지른 원 안에 들어온 점들을 잘라내고, 남은 조각을 새 획으로 다시 만든다.
     * 도형(직선·화살표·사각형·원)은 부분 삭제가 의미 없으므로 닿으면 통째로 지운다.
     */
    private fun erasePartialAt(x: Float, y: Float) {
        val p = page ?: return
        val radius = partialEraseRadius()
        val removed = ArrayList<DrawElement>()
        val added = ArrayList<DrawElement>()

        for (el in p.elements) {
            if (PagePainter.isAtomic(el.kind)) {
                if (hitTest(el, x, y)) removed.add(el)
                continue
            }
            val pts = el.points
            if (pts.isEmpty()) continue
            val reach = radius + el.width / 2f

            // 문지른 원 안에 들어온 점과, 원을 스쳐 지나간 선분을 모두 잘라낸다.
            val inside = BooleanArray(pts.size)
            var touched = false
            for (i in pts.indices) {
                val dx = pts[i].x - x
                val dy = pts[i].y - y
                if (sqrt(dx * dx + dy * dy) <= reach) {
                    inside[i] = true
                    touched = true
                }
            }
            val cutSegment = BooleanArray((pts.size - 1).coerceAtLeast(0))
            for (i in 0 until pts.size - 1) {
                if (distToSegment(x, y, pts[i], pts[i + 1]) <= reach) {
                    cutSegment[i] = true
                    touched = true
                }
            }
            if (!touched) continue

            removed.add(el)

            val runs = ArrayList<MutableList<Int>>()
            var run: MutableList<Int>? = null
            fun flush() {
                val current = run ?: return
                if (current.size >= 2) runs.add(current)
                run = null
            }

            for (i in pts.indices) {
                if (inside[i]) {
                    flush()
                    continue
                }
                if (i > 0 && cutSegment[i - 1]) flush()
                if (run == null) run = ArrayList()
                run!!.add(i)
            }
            flush()

            for (r in runs) {
                val newPoints = r.map { pts[it] }.toMutableList()
                val newPressures =
                    if (el.pressures.size == pts.size) r.map { el.pressures[it] }.toMutableList()
                    else ArrayList()
                added.add(DrawElement(el.kind, el.color, el.width, newPoints, newPressures))
            }
        }

        if (removed.isEmpty()) return
        if (!erasePushed) {
            p.pushUndo()
            erasePushed = true
        }
        p.elements.removeAll(removed)
        p.elements.addAll(added)
        invalidate()
        onPageEdited?.invoke()
    }

    private fun hitTest(el: DrawElement, x: Float, y: Float): Boolean {
        val threshold = el.width / 2f + ERASE_EXTRA
        val pts = el.points
        if (pts.isEmpty()) return false
        return when (el.kind) {
            ElementKind.LINE, ElementKind.DASHED_LINE ->
                distToSegment(x, y, pts.first(), pts.last()) <= threshold

            ElementKind.RECT, ElementKind.COVER -> {
                // 덮개는 이름자로 문질러도 지워지지 않게 테두리 가까이를 문지를 때만 잡힌다.
                val r = PagePainter.rectOf(pts.first(), pts.last())
                val inX = x >= r.left - threshold && x <= r.right + threshold
                val inY = y >= r.top - threshold && y <= r.bottom + threshold
                val nearEdge = (abs(x - r.left) <= threshold || abs(x - r.right) <= threshold ||
                    abs(y - r.top) <= threshold || abs(y - r.bottom) <= threshold)
                inX && inY && nearEdge
            }

            ElementKind.OVAL -> {
                val r = PagePainter.rectOf(pts.first(), pts.last())
                val rx = r.width() / 2f
                val ry = r.height() / 2f
                if (rx <= 0f || ry <= 0f) false else {
                    val cx = r.centerX()
                    val cy = r.centerY()
                    val nx = (x - cx) / rx
                    val ny = (y - cy) / ry
                    val value = sqrt(nx * nx + ny * ny)
                    abs(value - 1f) * min(rx, ry) <= threshold
                }
            }

            ElementKind.TRIANGLE -> {
                val r = PagePainter.rectOf(pts.first(), pts.last())
                val apex = Pt(r.centerX(), r.top)
                val right = Pt(r.right, r.bottom)
                val left = Pt(r.left, r.bottom)
                distToSegment(x, y, apex, right) <= threshold ||
                    distToSegment(x, y, right, left) <= threshold ||
                    distToSegment(x, y, left, apex) <= threshold
            }

            ElementKind.TEXT -> {
                for (line in PagePainter.textLineRects(el, paint)) {
                    if (x >= line.left - threshold && x <= line.right + threshold &&
                        y >= line.top - threshold && y <= line.bottom + threshold
                    ) return true
                }
                false
            }

            else -> {
                for (i in 0 until pts.size - 1) {
                    if (distToSegment(x, y, pts[i], pts[i + 1]) <= threshold) return true
                }
                if (pts.size == 1) {
                    val dx = x - pts[0].x
                    val dy = y - pts[0].y
                    sqrt(dx * dx + dy * dy) <= threshold
                } else {
                    false
                }
            }
        }
    }

    private fun distToSegment(px: Float, py: Float, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        if (lengthSq <= 0.0001f) {
            val ax = px - a.x
            val ay = py - a.y
            return sqrt(ax * ax + ay * ay)
        }
        var t = ((px - a.x) * dx + (py - a.y) * dy) / lengthSq
        t = t.coerceIn(0f, 1f)
        val cx = a.x + t * dx
        val cy = a.y + t * dy
        val ex = px - cx
        val ey = py - cy
        return sqrt(ex * ex + ey * ey)
    }
}
