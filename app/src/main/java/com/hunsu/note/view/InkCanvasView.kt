package com.hunsu.note.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
 * - 한 손가락(또는 스타일러스): 그리기 (손 도구일 때는 이동)
 * - 두 손가락: 확대/축소 + 이동(팬)
 * - 좌표계: 노트 world 좌표(예: 2560 x 1440), 화면에는 scale/offset 으로 변환
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var mode = MODE_NONE
    private var current: DrawElement? = null
    private var erasePushed = false

    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastDistance = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAW = 1
        private const val MODE_ERASE = 2
        private const val MODE_GESTURE = 3
        private const val MODE_PAN = 4

        private const val MIN_STROKE_STEP = 0.7f
        private const val ERASE_EXTRA = 14f
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

        canvas.restore()
    }

    // ---------------------------------------------------------------- 터치

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (page == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = MODE_NONE
                if (tool == Tool.HAND) {
                    mode = MODE_PAN
                    lastPanX = event.x
                    lastPanY = event.y
                    return true
                }
                if (!canStartStroke(event.getToolType(0))) return true
                val wx = screenToWorldX(event.x)
                val wy = screenToWorldY(event.y)
                if (tool == Tool.ERASER) {
                    mode = MODE_ERASE
                    erasePushed = false
                    eraseAt(wx, wy)
                } else {
                    mode = MODE_DRAW
                    current = createElement(wx, wy)
                    onStrokeStarted?.invoke()
                    invalidate()
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
                    MODE_PAN -> {
                        if (event.pointerCount > 0) {
                            offsetX += event.getX(0) - lastPanX
                            offsetY += event.getY(0) - lastPanY
                            lastPanX = event.getX(0)
                            lastPanY = event.getY(0)
                            invalidate()
                            onTransformChanged?.invoke()
                        }
                    }

                    MODE_GESTURE -> updateGesture(event)
                    MODE_DRAW -> {
                        extendCurrent(event)
                        invalidate()
                    }

                    MODE_ERASE -> eraseAt(screenToWorldX(event.x), screenToWorldY(event.y))
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 < 2) {
                    mode = MODE_NONE
                    lastDistance = 0f
                }
            }

            MotionEvent.ACTION_UP -> {
                if (mode == MODE_DRAW) finishCurrent()
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

    private fun createElement(x: Float, y: Float): DrawElement {
        val kind = when (tool) {
            Tool.PEN -> ElementKind.STROKE
            Tool.HIGHLIGHTER -> ElementKind.HIGHLIGHTER
            Tool.LINE -> ElementKind.LINE
            Tool.ARROW -> ElementKind.ARROW
            Tool.RECT -> ElementKind.RECT
            Tool.OVAL -> ElementKind.OVAL
            Tool.ERASER, Tool.HAND -> ElementKind.STROKE
        }
        val width = when (kind) {
            ElementKind.HIGHLIGHTER -> max(penWidth * 4f, 18f)
            ElementKind.STROKE -> penWidth
            else -> max(penWidth, 2f)
        }
        val points = ArrayList<Pt>()
        points.add(Pt(x, y))
        if (PagePainter.isShape(kind)) points.add(Pt(x, y))
        return DrawElement(kind, penColor, width, points)
    }

    private fun extendCurrent(event: MotionEvent) {
        val el = current ?: return
        if (PagePainter.isShape(el.kind)) {
            el.points[el.points.size - 1] =
                Pt(screenToWorldX(event.x), screenToWorldY(event.y))
            el.cachedPath = null
            return
        }
        for (h in 0 until event.historySize) {
            addPoint(
                el,
                screenToWorldX(event.getHistoricalX(0, h)),
                screenToWorldY(event.getHistoricalY(0, h))
            )
        }
        addPoint(el, screenToWorldX(event.x), screenToWorldY(event.y))
    }

    private fun addPoint(el: DrawElement, wxCandidate: Float, wyCandidate: Float) {
        val last = el.points.lastOrNull() ?: return
        val dx = wxCandidate - last.x
        val dy = wyCandidate - last.y
        if (sqrt(dx * dx + dy * dy) < MIN_STROKE_STEP) return
        el.points.add(Pt(wxCandidate, wyCandidate))
        el.cachedPath = null
    }

    private fun finishCurrent() {
        val el = current ?: return
        current = null
        val p = page ?: return
        if (PagePainter.isShape(el.kind)) {
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
            el.cachedPath = null
        }
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

    private fun hitTest(el: DrawElement, x: Float, y: Float): Boolean {
        val threshold = el.width / 2f + ERASE_EXTRA
        val pts = el.points
        if (pts.isEmpty()) return false
        return when (el.kind) {
            ElementKind.LINE, ElementKind.ARROW -> distToSegment(x, y, pts.first(), pts.last()) <= threshold

            ElementKind.RECT -> {
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
