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
 *
 * 3.6 입력 방어: 모든 좌표는 **지금 쓰는 포인터의 id** 로만 읽고(손바닥·다른 손가락 좌표가 획에
 * 섞이지 않게), 화면에서 크게 벗어난 좌표나 한 샘플에서 갑자기 멀리 튄 좌표는 획에 넣지 않는다.
 * 그런 좌표가 들어오면 획을 끊고 새 획으로 잇기 때문에 화면 위아래로 긴 세로줄이 그어지지 않는다.
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

    /**
     * 캔버스를 처음 누를 때마다 부른다 (한 손가락이든 두 손가락 시작이든).
     * 3.5: 글자 인라인 입력 확정과 포스트잇 커서 닫기에 쓴다 — 바깥을 누르면 입력이 끝난다.
     */
    var onCanvasTouchDown: (() -> Unit)? = null

    /** 지금 그리는 덮개 떡메모지에 얇은 테두리를 둘지 (3.5 툴바의 '테두리 있음/없음') */
    var coverBorder: Boolean = false

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

    /**
     * 3.6: 지금 획을 그리는(동작 중인) 포인터의 id.
     * 예전에는 모든 좌표를 0번 인덱스에서 읽어, 손바닥이 먼저 닿아 펜이 1번이 되면
     * 손바닥 좌표가 획에 섞여 화면 위아래로 긴 세로줄이 그어졌다.
     */
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeIsStylus = false

    /** 마지막으로 받아들인 점의 eventTime(ms) — 갑자기 튄 좌표를 속도로도 가려낸다. */
    private var lastPointTime = 0L

    /** 방금 좌표가 이상해 점을 버렸다 — 다음에 받아들인 점은 새 획으로 시작한다. */
    private var reanchorPending = false

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

        /** 화면(비율) 밖으로 이만큼 넘어간 좌표는 손바닥·호버 등으로 보고 버린다 (3.6) */
        private const val OUT_OF_VIEW_FRACTION = 0.25f
        private const val OUT_OF_VIEW_MIN_PX = 160f

        /**
         * 한 샘플에서 화면으로 이 비율(대각선 기준)을 넘게 튀거나, 아래 속도보다 빠르면
         * 좌표가 잘못 들어온 것으로 본다 (3.6). 화면 px 기준이라 확대·축소해도 같은 감각으로 걸러진다.
         */
        private const val MAX_STEP_FRACTION = 0.35f
        private const val MIN_STEP_LIMIT_PX = 180f

        /** 사람이 펜으로 낼 수 있는 속도(화면 px/ms)를 넘는 이동은 튄 좌표로 본다 */
        private const val MAX_STEP_SPEED_PX_PER_MS = 25f

        /** 이벤트 시간이 같거나 없으면 한 샘플(60Hz)로 본다 */
        private const val DEFAULT_SAMPLE_MS = 16L

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

    /**
     * 화면을 [dx], [dy] 만큼 민다.
     * 글자 입력창처럼 캔버스 위의 한 점을 키보드 위로 올려 보여줄 때 쓴다.
     */
    fun panBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        offsetX += dx
        offsetY += dy
        invalidate()
        onTransformChanged?.invoke()
    }

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
            MotionEvent.ACTION_DOWN -> return onTouchDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onTouchMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onTouchUp(event)
            MotionEvent.ACTION_CANCEL -> {
                // 취소된 입력의 좌표는 쓰지 않는다 — 그리던 미리보기만 버린다.
                cancelCurrent()
                endTouch()
            }
        }
        return true
    }

    private fun onTouchDown(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        mode = MODE_NONE
        current = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activeIsStylus = false
        reanchorPending = false
        lastPointTime = event.eventTime
        // 바깥을 눌렀으니 입력 중이던 글자·포스트잇 커서를 먼저 닫는다.
        onCanvasTouchDown?.invoke()
        // 0·음수·범위 밖 좌표(호버·손바닥)로는 획을 시작하지 않는다.
        if (!isUsablePoint(event.getX(0), event.getY(0))) return true
        if (!canStartStroke(event.getToolType(0))) return true
        activePointerId = event.getPointerId(0)
        activeIsStylus = isStylusTool(event.getToolType(0))
        startTouchAt(event.getX(0), event.getY(0), event, 0)
        return true
    }

    /** 한 포인터가 닿은 지점에서 도구별 동작을 시작한다. */
    private fun startTouchAt(x: Float, y: Float, event: MotionEvent, index: Int) {
        val wx = screenToWorldX(x)
        val wy = screenToWorldY(y)
        startWorldX = wx
        startWorldY = wy
        lastWorldX = wx
        lastWorldY = wy
        erasePushed = false
        when (tool) {
            Tool.NOTE -> {
                // 사각형을 그려 포스트잇을 놓는 중
                mode = MODE_NOTE
                invalidate()
            }

            Tool.TEXT -> mode = MODE_TEXT

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
                current = createElement(wx, wy, event, index)
                onStrokeStarted?.invoke()
                invalidate()
            }
        }
    }

    private fun onPointerDown(event: MotionEvent) {
        val index = event.actionIndex
        val newIsStylus = isStylusTool(event.getToolType(index))
        // 펜으로 쓰는 중에 손바닥·손가락이 닿으면 그 포인터는 무시한다(쓰던 획은 그대로 둔다).
        if (mode == MODE_DRAW && activeIsStylus && !newIsStylus) return
        // 손바닥이 먼저 닿아 있던 자리에 펜이 내려오면, 펜으로 새 획을 시작한다.
        if (mode == MODE_NONE && newIsStylus && canStartStroke(event.getToolType(index)) &&
            isUsablePoint(event.getX(index), event.getY(index))
        ) {
            cancelCurrent()
            activePointerId = event.getPointerId(index)
            activeIsStylus = true
            lastPointTime = event.eventTime
            startTouchAt(event.getX(index), event.getY(index), event, index)
            return
        }
        if (event.pointerCount >= 2) {
            // 두 손가락 확대·이동. 그리던 획은 버려 손가락으로 그은 점이 남지 않게 한다.
            cancelCurrent()
            mode = MODE_GESTURE
            activePointerId = MotionEvent.INVALID_POINTER_ID
            activeIsStylus = false
            anchorGesture(event, -1)
        }
    }

    private fun onTouchMove(event: MotionEvent) {
        when (mode) {
            MODE_GESTURE -> updateGesture(event)

            MODE_NOTE -> {
                val index = activeIndex(event)
                if (index < 0) return
                if (!isUsablePoint(event.getX(index), event.getY(index))) return
                lastWorldX = screenToWorldX(event.getX(index))
                lastWorldY = screenToWorldY(event.getY(index))
                invalidate()
            }

            MODE_DRAW -> extendCurrent(event)

            MODE_ERASE -> {
                val index = activeIndex(event)
                if (index < 0) return
                if (!isUsablePoint(event.getX(index), event.getY(index))) return
                eraseAt(screenToWorldX(event.getX(index)), screenToWorldY(event.getY(index)))
            }

            MODE_ERASE_PARTIAL -> {
                val index = activeIndex(event)
                if (index < 0) return
                if (!isUsablePoint(event.getX(index), event.getY(index))) return
                val wx = screenToWorldX(event.getX(index))
                val wy = screenToWorldY(event.getY(index))
                erasePartialSweep(lastEraseX, lastEraseY, wx, wy)
                lastEraseX = wx
                lastEraseY = wy
            }
        }
    }

    /**
     * 포인터 하나가 떨어졌다.
     * 쓰던 포인터가 아니면(손바닥이 먼저 떨어지는 등) 그대로 계속 그린다.
     */
    private fun onPointerUp(event: MotionEvent) {
        val index = event.actionIndex
        val isActive = activePointerId != MotionEvent.INVALID_POINTER_ID &&
            event.getPointerId(index) == activePointerId
        if (isActive) {
            finishTouchWith(event, index)
            activePointerId = MotionEvent.INVALID_POINTER_ID
            activeIsStylus = false
        }
        if (mode == MODE_GESTURE) {
            if (event.pointerCount - 1 >= 2) {
                // 아직 두 손가락 이상 — 남은 포인터로 기준점을 다시 잡아 튀지 않게 한다.
                anchorGesture(event, index)
            } else {
                mode = MODE_NONE
                lastDistance = 0f
            }
        }
    }

    private fun onTouchUp(event: MotionEvent) {
        val index = activeIndex(event)
        if (index >= 0) {
            // 마지막으로 떨어지는 포인터가 쓰던 포인터일 때만 그 좌표로 마무리한다.
            finishTouchWith(event, index)
        } else if (mode == MODE_DRAW) {
            // 쓰던 포인터는 이미 떨어졌다 — 다른 포인터의 좌표를 섞지 않고 지금까지 그린 대로 남긴다.
            finishCurrent()
        } else if (mode == MODE_NOTE) {
            // 좌표를 모르면 포스트잇을 놓지 않는다(엉뚱한 자리에 생기는 것 방지).
            invalidate()
        }
        endTouch()
    }

    /**
     * 지금 동작을 [index] 포인터의 마지막 좌표로 마무리한다.
     * 좌표가 범위를 벗어났으면 마지막으로 받아들인 좌표를 쓴다.
     */
    private fun finishTouchWith(event: MotionEvent, index: Int) {
        val sx = event.getX(index)
        val sy = event.getY(index)
        val usable = isUsablePoint(sx, sy)
        val wx = if (usable) screenToWorldX(sx) else lastWorldX
        val wy = if (usable) screenToWorldY(sy) else lastWorldY
        when (mode) {
            MODE_DRAW -> {
                if (usable) extendCurrent(event)
                finishCurrent()
            }

            MODE_NOTE -> {
                if (usable) {
                    lastWorldX = wx
                    lastWorldY = wy
                }
                invalidate()
                onNoteRect?.invoke(startWorldX, startWorldY, lastWorldX, lastWorldY)
            }

            MODE_TEXT -> {
                // 손을 크게 움직이지 않고 누른 자리에 글자를 넣는다.
                val dx = wx - startWorldX
                val dy = wy - startWorldY
                if (usable && sqrt(dx * dx + dy * dy) <= TEXT_TAP_SLOP) {
                    onTextTap?.invoke(startWorldX, startWorldY)
                }
            }

            else -> Unit
        }
        mode = MODE_NONE
    }

    private fun endTouch() {
        mode = MODE_NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        activeIsStylus = false
        reanchorPending = false
        lastDistance = 0f
    }

    /** 지금 동작 중인 포인터의 이벤트 내 인덱스. 다른 포인터의 좌표를 섞지 않으려고 항상 이 값을 쓴다. */
    private fun activeIndex(event: MotionEvent): Int {
        if (activePointerId == MotionEvent.INVALID_POINTER_ID) return -1
        return event.findPointerIndex(activePointerId)
    }

    private fun isStylusTool(toolType: Int): Boolean =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

    private fun canStartStroke(toolType: Int): Boolean = isStylusTool(toolType) || fingerDrawingAllowed

    // ---------------------------------------------------------------- 좌표 방어

    /** 화면 폭(px). 화면에 붙기 전이면 노트 world 폭 × 배율을 쓴다. */
    private fun screenWidthPx(): Float =
        if (width > 0) width.toFloat() else (page?.worldWidth ?: 0f) * scale

    private fun screenHeightPx(): Float =
        if (height > 0) height.toFloat() else (page?.worldHeight ?: 0f) * scale

    /**
     * 쓸 수 있는 좌표인지. NaN·무한대이거나 화면(화면 크기를 모르면 노트)에서 크게 벗어난
     * 좌표는 호버·손바닥·리프트에서 잘못 들어온 값으로 보고 버린다.
     */
    private fun isUsablePoint(x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        val w = screenWidthPx()
        val h = screenHeightPx()
        if (w <= 0f || h <= 0f) return true
        val marginX = max(w * OUT_OF_VIEW_FRACTION, OUT_OF_VIEW_MIN_PX)
        val marginY = max(h * OUT_OF_VIEW_FRACTION, OUT_OF_VIEW_MIN_PX)
        return x >= -marginX && x <= w + marginX && y >= -marginY && y <= h + marginY
    }

    /**
     * 한 샘플에서 이만큼(화면 px) 넘게 튀면 좌표가 잘못 들어온 것으로 본다.
     * 오래 비었다가 들어온 점은 그 사이 실제로 크게 움직였을 수 있으므로 시간도 함께 본다.
     */
    private fun jumpLimit(dtMs: Long): Float {
        val w = screenWidthPx()
        val h = screenHeightPx()
        val diag = sqrt(w * w + h * h)
        val dt = if (dtMs > DEFAULT_SAMPLE_MS) dtMs.toFloat() else DEFAULT_SAMPLE_MS.toFloat()
        return max(
            min(diag * MAX_STEP_FRACTION, MAX_STEP_SPEED_PX_PER_MS * dt),
            MIN_STEP_LIMIT_PX
        )
    }

    /** 마지막 점에서 너무 멀리 튀었는지 (화면 px 기준) */
    private fun stepTooFar(last: Pt, worldX: Float, worldY: Float, dtMs: Long): Boolean {
        val dx = (worldX - last.x) * scale
        val dy = (worldY - last.y) * scale
        return sqrt(dx * dx + dy * dy) > jumpLimit(dtMs)
    }

    // ---------------------------------------------------------------- 획

    /** 기기가 보고하는 최대 압력 (압력 미지원이면 1). */
    private fun maxPressureOf(event: MotionEvent): Float = try {
        val max = event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE)?.max ?: 1f
        if (max > 0.0001f) max else 1f
    } catch (e: Exception) {
        1f
    }

    /** 스타일러스 압력을 0~1 로 정규화한다. 압력을 지원하지 않는 기기·손가락은 1 이 된다. */
    private fun pressureOf(event: MotionEvent, index: Int): Float {
        val raw = try {
            event.getPressure(index)
        } catch (e: Exception) {
            return 1f
        }
        return (raw / maxPressureOf(event)).coerceIn(0f, 1f)
    }

    private fun createElement(x: Float, y: Float, event: MotionEvent, index: Int): DrawElement {
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
        // 덮개 떡메모지는 그 순간의 '테두리 있음/없음' 선택을 요소에 남긴다.
        if (kind == ElementKind.COVER) element.border = coverBorder
        if (usePressureFor(event, index) && kind == ElementKind.STROKE) {
            element.pressures.add(pressureOf(event, index))
        }
        return element
    }

    /** 이 입력에 필압을 적용할지: 설정이 켜져 있고, 스타일러스로 그린 획일 때 */
    private fun usePressureFor(event: MotionEvent, index: Int): Boolean {
        if (!pressureEnabled) return false
        return event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS
    }

    private fun extendCurrent(event: MotionEvent) {
        val el = current ?: return
        val index = activeIndex(event)
        // 쓰던 포인터가 이 이벤트에 없으면 다른 포인터의 좌표를 섞지 않고 넘긴다.
        if (index < 0) return
        if (PagePainter.isShape(el.kind)) {
            // 도형은 시작점과 지금 점 두 점만 쓴다 — 범위 밖 좌표만 버리고 그대로 따라간다.
            val sx = event.getX(index)
            val sy = event.getY(index)
            if (!isUsablePoint(sx, sy)) return
            el.points[el.points.size - 1] =
                Pt(screenToWorldX(sx), screenToWorldY(sy))
            el.cachedPath = null
            lastPointTime = event.eventTime
            return
        }
        if (el.kind == ElementKind.COVER) {
            // 덮개(흰 사각형)도 한 번에 크게 그을 수 있다 — 튄 좌표로 긴 획이 남지 않으므로
            // 범위 밖 좌표만 버리고 그대로 따라간다.
            val sx = event.getX(index)
            val sy = event.getY(index)
            if (!isUsablePoint(sx, sy)) return
            val wx = screenToWorldX(sx)
            val wy = screenToWorldY(sy)
            val lastCover = el.points.last()
            val dxc = wx - lastCover.x
            val dyc = wy - lastCover.y
            if (sqrt(dxc * dxc + dyc * dyc) < MIN_STROKE_STEP) return
            el.points.add(Pt(wx, wy))
            el.cachedPath = null
            lastPointTime = event.eventTime
            return
        }
        val maxPressure = maxPressureOf(event)
        for (h in 0 until event.historySize) {
            val historicalPressure = try {
                (event.getHistoricalPressure(index, h) / maxPressure).coerceIn(0f, 1f)
            } catch (e: Exception) {
                pressureOf(event, index)
            }
            addPointChecked(
                el,
                event.getHistoricalX(index, h),
                event.getHistoricalY(index, h),
                historicalPressure,
                event.eventTime
            )
        }
        addPointChecked(
            el,
            event.getX(index),
            event.getY(index),
            pressureOf(event, index),
            event.eventTime
        )
    }

    /**
     * 좌표를 걸러 획에 점을 넣는다.
     * - 범위를 벗어난 좌표는 넣지 않고, 다음에 받아들인 점에서 획을 새로 시작한다.
     * - 마지막 점에서 갑자기 멀리 튄 점도 이어 붙이지 않고 획을 끊는다.
     *   (예전에는 그대로 이어 그려 화면 위아래로 긴 세로줄이 그어졌다.)
     */
    private fun addPointChecked(
        el: DrawElement,
        screenX: Float,
        screenY: Float,
        pressure: Float,
        timeMs: Long
    ) {
        if (!isUsablePoint(screenX, screenY)) {
            reanchorPending = true
            return
        }
        val last = el.points.lastOrNull() ?: return
        val wx = screenToWorldX(screenX)
        val wy = screenToWorldY(screenY)
        val dt = if (timeMs >= lastPointTime) timeMs - lastPointTime else 0L
        val dx = wx - last.x
        val dy = wy - last.y
        val moved = sqrt(dx * dx + dy * dy)
        if (stepTooFar(last, wx, wy, dt)) {
            // 튄 좌표는 획에 넣지 않는다(화면 끝까지 이어지는 세로줄 방지).
            reanchorPending = true
            return
        }
        if (reanchorPending) {
            reanchorPending = false
            // 시간상 가능한 이동인데도 한참 멀리 옮겨졌으면 긴 선을 긋지 않고 새 획으로 시작한다.
            if (moved * scale > jumpLimit(0L)) {
                splitStrokeAt(el, wx, wy, pressure, timeMs)
                return
            }
        }
        if (moved < MIN_STROKE_STEP) return
        appendPoint(el, wx, wy, pressure)
        lastPointTime = timeMs
    }

    private fun appendPoint(el: DrawElement, wx: Float, wy: Float, pressure: Float) {
        val trackPressure = el.pressures.isNotEmpty() && el.pressures.size == el.points.size
        el.points.add(Pt(wx, wy))
        if (trackPressure) el.pressures.add(pressure)
        el.cachedPath = null
    }

    /** 좌표가 크게 튀었을 때: 지금까지 그린 획은 남기고 이 점에서 새 획을 시작한다. */
    private fun splitStrokeAt(
        el: DrawElement,
        wx: Float,
        wy: Float,
        pressure: Float,
        timeMs: Long
    ) {
        val trackPressure = el.pressures.isNotEmpty() && el.pressures.size == el.points.size
        finishCurrent()
        val next = DrawElement(el.kind, el.color, el.width, ArrayList<Pt>())
        next.points.add(Pt(wx, wy))
        if (trackPressure) next.pressures.add(pressure)
        current = next
        mode = MODE_DRAW
        lastPointTime = timeMs
        reanchorPending = false
        invalidate()
        onStrokeStarted?.invoke()
    }

    private fun finishCurrent() {
        val el = current ?: return
        current = null
        val p = page ?: return
        if (PagePainter.isAtomic(el.kind)) {
            val a = el.points.firstOrNull() ?: return
            val b = el.points.lastOrNull() ?: return
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

    /**
     * 두 손가락 제스처의 기준점을 지금 이벤트의 좌표로 다시 잡는다.
     * [skipIndex] 는 방금 올라간 포인터다(없으면 -1). 다시 잡지 않으면 인덱스가 밀리는 순간
     * 화면이 갑자기 튄다.
     */
    private fun anchorGesture(event: MotionEvent, skipIndex: Int) {
        val picked = ArrayList<Int>(2)
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            picked.add(i)
            if (picked.size == 2) break
        }
        if (picked.size < 2) {
            lastDistance = 0f
            return
        }
        val ax = event.getX(picked[0])
        val ay = event.getY(picked[0])
        val bx = event.getX(picked[1])
        val by = event.getY(picked[1])
        lastMidX = (ax + bx) / 2f
        lastMidY = (ay + by) / 2f
        lastDistance = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
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
