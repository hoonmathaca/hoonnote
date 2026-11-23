package com.hunsu.note.model

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 현재 선택된 판서 도구.
 *
 * 3.4 에서 '이동/줌(HAND)' 도구를 없앴다 — 화면 이동·확대는 손가락 두 개(핀치·팬)로만 한다.
 * 화살표(반직선) 도구 자리에는 점선 직선(DASHED_LINE)이 들어갔고,
 * 도형에 삼각형(TRIANGLE), 새 도구로 덮개 떡메모지(COVER)와 글자(TEXT)가 붙었다.
 * NOTE 는 포스트잇 — 캔버스에서 사각형을 그려 놓는다.
 */
enum class Tool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    ERASER_PARTIAL,
    LINE,
    DASHED_LINE,
    RECT,
    OVAL,
    TRIANGLE,
    COVER,
    TEXT,
    NOTE
}

/** 페이지에 실제로 저장되는 요소의 종류 */
enum class ElementKind { STROKE, HIGHLIGHTER, LINE, DASHED_LINE, RECT, OVAL, TRIANGLE, COVER, TEXT }

data class Pt(val x: Float, val y: Float)

/**
 * 페이지에 그려진 하나의 판서 요소.
 * 자유 곡선/형광펜은 points 전체를, 도형은 points[0](시작) 과 points[last](끝) 만 사용한다.
 * pressures 는 points 와 같은 길이이며 필압(0~1)을 담는다. 비어 있으면 굵기가 일정한 선이다.
 */
class DrawElement(
    val kind: ElementKind,
    val color: Int,
    val width: Float,
    val points: MutableList<Pt>,
    val pressures: MutableList<Float> = ArrayList(),
    /** 글자(TEXT) 요소의 내용. 다른 요소에서는 빈 문자열이다. */
    var text: String = "",
    /**
     * 덮개 떡메모지(COVER)에 얇은 테두리를 그릴지.
     * 3.5 부터 떡메모지를 놓을 때 '테두리 있음 / 없음'을 고를 수 있고, 그 선택이 요소에 남는다.
     * 다른 요소에서는 쓰지 않는다.
     */
    var border: Boolean = false
) {
    /** 계산된 Path 캐시 (지우개/이동으로 목록이 바뀌면 무효화) */
    var cachedPath: Path? = null

    /** 필압으로 굵기가 달라지는 선인지 */
    val hasPressure: Boolean
        get() = (kind == ElementKind.STROKE || kind == ElementKind.HIGHLIGHTER) &&
            pressures.size == points.size && points.size > 1

    fun deepCopy(): DrawElement = DrawElement(
        kind,
        color,
        width,
        points.map { Pt(it.x, it.y) }.toMutableList(),
        ArrayList(pressures),
        text,
        border
    )

    /** 필압은 가장 얇을 때 기본 굵기의 35% 까지만 줄어든다. */
    fun widthAt(index: Int): Float {
        if (index < 0 || index >= pressures.size) return width
        val p = pressures[index].coerceIn(0f, 1f)
        return width * (0.35f + 0.65f * p)
    }
}

/** 노트 배경 스타일 */
enum class BackgroundStyle(val bgColor: Int, val withGrid: Boolean) {
    WHITE(0xFFFFFFFF.toInt(), false),
    CREAM(0xFFFFFDF4.toInt(), false),
    GREEN(0xFF20402E.toInt(), false),
    NAVY(0xFF12203A.toInt(), false),
    GRID(0xFFFFFFFF.toInt(), true)
}

/**
 * 노트 캔버스 크기(해상도). PDF/이미지는 이 노트 위에 얹힌다.
 * 설정 화면에서 고르며 새 강의·새 페이지부터 적용된다.
 */
enum class NoteLayout(val width: Float, val height: Float) {
    WIDE_16_9(2560f, 1440f),
    FHD_16_9(1920f, 1080f),
    CLASSIC_4_3(2048f, 1536f),
    A4_LANDSCAPE(2970f, 2100f);

    val aspect: Float get() = width / height

    companion object {
        const val DEFAULT_INDEX = 0

        fun of(index: Int): NoteLayout = values().getOrElse(index) { WIDE_16_9 }
    }
}

/** 노트 위에서 PDF/이미지를 놓는 위치 */
enum class NoteAlign {
    LEFT,
    CENTER;

    companion object {
        fun of(index: Int): NoteAlign = values().getOrElse(index) { LEFT }
    }
}

/**
 * 노트 캔버스 안에서 PDF/이미지가 차지할 영역을 계산한다.
 * 노트 높이의 (1 - 2*margin) 배에 맞추고, 좌측 정렬이면 오른쪽에 판서 여백이 남는다.
 */
object NoteGeometry {

    fun contentRect(
        noteWidth: Float,
        noteHeight: Float,
        contentAspect: Float,
        align: NoteAlign,
        margin: Float
    ): RectF {
        if (contentAspect <= 0f || noteWidth <= 0f || noteHeight <= 0f) return RectF()
        val m = margin.coerceIn(0f, 0.25f)
        val maxW = noteWidth * (1f - 2f * m)
        val maxH = noteHeight * (1f - 2f * m)
        var h = maxH
        var w = h * contentAspect
        if (w > maxW) {
            w = maxW
            h = w / contentAspect
        }
        val left = if (align == NoteAlign.LEFT) noteWidth * m else (noteWidth - w) / 2f
        val top = (noteHeight - h) / 2f
        return RectF(left, top, left + w, top + h)
    }
}

/**
 * 펜·형광펜 굵이 단계.
 * 세필(1.5)부터 아주 굵은 선(22)까지 6단계를 기본으로 두고,
 * 설정 화면의 슬라이더로 0.5~48 사이를 세밀하게 조절할 수 있다.
 */
object StrokeWidths {

    val PEN = floatArrayOf(1.5f, 3f, 5f, 8f, 13f, 22f)
    val HIGHLIGHTER = floatArrayOf(8f, 14f, 20f, 28f, 38f, 52f)

    /** 글자 크기 단계 (노트 world 좌표 기준) */
    val TEXT = floatArrayOf(26f, 36f, 50f, 68f, 90f, 120f)

    const val MIN_WIDTH = 0.5f
    const val MAX_WIDTH = 48f

    fun of(tool: Tool): FloatArray = when (tool) {
        Tool.HIGHLIGHTER -> HIGHLIGHTER
        Tool.TEXT -> TEXT
        else -> PEN
    }

    /** 버튼에 표시할 짧은 이름 */
    fun label(width: Float): String =
        if (width == width.toInt().toFloat()) width.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", width)

    /** 현재 굵이와 가장 가까운 단계의 위치 */
    fun nearestIndex(values: FloatArray, width: Float): Int {
        var best = 0
        var diff = Float.MAX_VALUE
        for (i in values.indices) {
            val d = kotlin.math.abs(values[i] - width)
            if (d < diff) {
                diff = d
                best = i
            }
        }
        return best
    }
}

/** 포스트잇 (좌표는 페이지 world 좌표) */
class StickyNote(
    var text: String,
    var x: Float,
    var y: Float,
    var width: Float = StickyGeometry.DEFAULT_WIDTH,
    var height: Float = StickyGeometry.DEFAULT_HEIGHT
)

/**
 * 포스트잇을 캔버스에서 사각형을 그려 놓을 때의 계산.
 * 드래그한 사각형을 그대로 쓰고, 너무 작게(탭만) 그렸으면 기본 크기로 놓는다.
 */
object StickyGeometry {

    const val DEFAULT_WIDTH = 340f
    const val DEFAULT_HEIGHT = 280f
    const val MIN_WIDTH = 140f
    const val MIN_HEIGHT = 110f

    /** 놓을 사각형의 폭·높이가 이보다 작으면 탭으로 본다. */
    const val TAP_SLOP = 24f

    fun fromDrag(x0: Float, y0: Float, x1: Float, y1: Float): StickyNote {
        val left = min(x0, x1)
        val top = min(y0, y1)
        val w = abs(x1 - x0)
        val h = abs(y1 - y0)
        if (w < TAP_SLOP && h < TAP_SLOP) {
            // 탭만 했으면 누른 자리를 가운데로 기본 크기 포스트잇을 놓는다.
            return StickyNote(
                "",
                left - DEFAULT_WIDTH / 2f,
                top - DEFAULT_HEIGHT / 2f,
                DEFAULT_WIDTH,
                DEFAULT_HEIGHT
            )
        }
        return StickyNote("", left, top, max(w, MIN_WIDTH), max(h, MIN_HEIGHT))
    }
}

/**
 * 한 페이지. 넓은 노트 캔버스(worldWidth x worldHeight)와 그 위에 얹힌
 * PDF 페이지(pdfPageIndex) 또는 이미지(image)로 이루어진다.
 * PDF/이미지는 contentRect 안에만 그려지고, 판서는 노트 전체에 남길 수 있다.
 *
 * [rotation] 은 PDF/이미지를 노트 위에서 0/90/180/270 도로 돌려 보여준다.
 * 90/270 도에서는 가로·세로 비율이 뒤집혀 contentRect 도 함께 바뀐다.
 */
class Page(
    val layout: NoteLayout = NoteLayout.WIDE_16_9,
    var style: BackgroundStyle = BackgroundStyle.WHITE,
    val pdfPageIndex: Int = -1,
    val image: Bitmap? = null,
    val baseAspect: Float = 0f,
    /** 어느 PDF·이미지 문서에서 왔는지 (여러 PDF를 이어 쌓을 때 구분한다) */
    val sourceId: String = "",
    /** 페이지마다 고유한 번호 — 배경 캐시 파일 이름으로 쓴다 */
    val id: Long = nextPageId()
) {
    val worldWidth: Float get() = layout.width
    val worldHeight: Float get() = layout.height

    /** 원본 PDF/이미지의 가로/세로 비율 (회전 전) */
    val sourceAspect: Float get() = baseAspect

    /** 회전이 반영된 표시 비율 */
    var contentAspect: Float = baseAspect
        private set

    var rotation: Int = 0
        private set

    init {
        applyAspect()
    }

    /** 90 도 단위로만 회전한다. */
    fun setRotation(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        rotation = if (normalized % 90 == 0) normalized else 0
        applyAspect()
    }

    fun rotateBy(delta: Int) = setRotation(rotation + delta)

    private fun applyAspect() {
        contentAspect = when {
            baseAspect <= 0f -> baseAspect
            rotation % 180 == 90 -> 1f / baseAspect
            else -> baseAspect
        }
    }

    /** PDF/이미지가 그려질 노트 안 영역 (내용이 없으면 빈 Rect) */
    val contentRect: RectF = RectF()

    val elements = ArrayList<DrawElement>()
    val notes = ArrayList<StickyNote>()

    /** 지금 화면에 올려 둔 배경(PDF/이미지). 메모리 예산을 넘으면 놓아준다. */
    var renderedBackground: Bitmap? = null

    /** 이 페이지 배경이 저장된 캐시 파일의 키 */
    val backgroundKey: String get() = backgroundKeyFor(id)

    /**
     * PDF 페이지이거나 이미지 페이지인지.
     * 3.3 부터 이미지도 비트맵을 들고 있지 않고 캐시 파일에서 읽으므로,
     * 비율(baseAspect)이 있으면 내용이 있는 페이지로 본다.
     */
    val hasContent: Boolean get() = pdfPageIndex >= 0 || image != null || baseAspect > 0f

    /** 회전 때문에 배경을 다시 그려야 하는지 (렌더 캐시 무효화) */
    fun invalidateBackground() {
        if (!hasContent) return
        renderedBackground = null
    }

    /** 설정이 바뀌면(정렬/여백/회전) 내용 영역만 다시 계산한다. */
    fun applyContentLayout(align: NoteAlign, margin: Float) {
        val r = NoteGeometry.contentRect(worldWidth, worldHeight, contentAspect, align, margin)
        contentRect.set(r)
    }

    /** PDF/이미지를 그릴 때 필요한 원본 픽셀 폭 (회전 90/270 이면 세로 길이가 폭이 된다) */
    fun backgroundPixelWidth(): Int {
        val w = if (rotation % 180 == 90) contentRect.height() else contentRect.width()
        if (w <= 1f) return RENDER_FALLBACK_WIDTH
        val aspect = sourceAspect.coerceIn(0.05f, 20f)
        // 노트 world 픽셀보다 촘촘하게 그려 화면에서 확대해도 선명하게 유지한다.
        var width = w * RENDER_QUALITY
        val budget = MAX_RENDER_PIXELS
        if (width * (width / aspect) > budget) width = sqrt(budget * aspect)
        if (width > MAX_RENDER_SIDE) width = MAX_RENDER_SIDE.toFloat()
        return width.toInt().coerceIn(320, MAX_RENDER_SIDE)
    }

    private val undoStack = ArrayDeque<ArrayList<DrawElement>>()
    private val redoStack = ArrayDeque<ArrayList<DrawElement>>()

    companion object {
        private const val MAX_UNDO = 60

        private val pageIds = java.util.concurrent.atomic.AtomicLong(0L)

        /** 새 페이지 번호 (앱을 켜 둔 동안 겹치지 않는다) */
        fun nextPageId(): Long = pageIds.incrementAndGet()

        fun backgroundKeyFor(id: Long): String = "page_$id"

        /** 노트 world 픽셀 대비 배경 렌더 배율 — 확대해도 또렷하게 보이도록 1.75배로 그린다. */
        const val RENDER_QUALITY = 1.75f

        /** 한 장에 쓸 수 있는 픽셀 예산과 최대 변 길이 (메모리 보호) */
        const val MAX_RENDER_PIXELS = 5_000_000f
        const val MAX_RENDER_SIDE = 4096
        const val RENDER_FALLBACK_WIDTH = 1024
    }

    /**
     * 요소를 변경하기 직전에 호출한다.
     *
     * 3.7: 완성된 요소(점이 다 들어간 획·도형·글자)는 목록에 들어간 뒤에는 절대 바뀌지 않고,
     * 바뀌는 것은 지금 그리는 중인 요소뿐이다. 그래서 되돌리기 기록은 **요소 목록만 복사**한다.
     * 예전에는 획을 하나 마칠 때마다 페이지의 모든 획과 점을 깊은 복사해(최대 60단계),
     * 필기할수록 점 객체가 수십만 개씩 쌓여 GC 가 자주 멈췄다 — 버벅임의 한 원인이었다.
     */
    fun pushUndo() {
        undoStack.addLast(ArrayList(elements))
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(ArrayList(elements))
        elements.clear()
        elements.addAll(previous)
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(ArrayList(elements))
        elements.clear()
        elements.addAll(next)
        return true
    }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
}

/** 한 개의 강의(문서) */
class Lecture {
    val pages = ArrayList<Page>()
    var index = 0
    var style: BackgroundStyle = BackgroundStyle.WHITE
    var title: String = "훈노트"

    val currentPage: Page? get() = pages.getOrNull(index)

    /** 새 페이지는 지금 강의의 노트 크기를 따른다. */
    val layout: NoteLayout get() = pages.firstOrNull()?.layout ?: NoteLayout.WIDE_16_9
}
