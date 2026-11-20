package com.hunsu.note.model

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.RectF

/** 현재 선택된 판서 도구 */
enum class Tool { PEN, HIGHLIGHTER, ERASER, LINE, ARROW, RECT, OVAL, HAND }

/** 페이지에 실제로 저장되는 요소의 종류 */
enum class ElementKind { STROKE, HIGHLIGHTER, LINE, ARROW, RECT, OVAL }

data class Pt(val x: Float, val y: Float)

/**
 * 페이지에 그려진 하나의 판서 요소.
 * 자유 곡선/형광펜은 points 전체를, 도형은 points[0](시작) 과 points[last](끝) 만 사용한다.
 */
class DrawElement(
    val kind: ElementKind,
    val color: Int,
    val width: Float,
    val points: MutableList<Pt>
) {
    /** 계산된 Path 캐시 (지우개/이동으로 목록이 바뀌면 무효화) */
    var cachedPath: Path? = null

    fun deepCopy(): DrawElement =
        DrawElement(kind, color, width, points.map { Pt(it.x, it.y) }.toMutableList())
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

/** 포스트잇 (좌표는 페이지 world 좌표) */
class StickyNote(
    var text: String,
    var x: Float,
    var y: Float,
    var width: Float = 340f,
    var height: Float = 280f
)

/**
 * 한 페이지. 넓은 노트 캔버스(worldWidth x worldHeight)와 그 위에 얹힌
 * PDF 페이지(pdfPageIndex) 또는 이미지(image)로 이루어진다.
 * PDF/이미지는 contentRect 안에만 그려지고, 판서는 노트 전체에 남길 수 있다.
 */
class Page(
    val layout: NoteLayout = NoteLayout.WIDE_16_9,
    var style: BackgroundStyle = BackgroundStyle.WHITE,
    val pdfPageIndex: Int = -1,
    val image: Bitmap? = null,
    val contentAspect: Float = 0f
) {
    val worldWidth: Float get() = layout.width
    val worldHeight: Float get() = layout.height

    /** PDF/이미지가 그려질 노트 안 영역 (내용이 없으면 빈 Rect) */
    val contentRect: RectF = RectF()

    val elements = ArrayList<DrawElement>()
    val notes = ArrayList<StickyNote>()

    /** 화면 표시용으로 미리 렌더링해 둔 배경(PDF/이미지) */
    var renderedBackground: Bitmap? = null

    val hasContent: Boolean get() = pdfPageIndex >= 0 || image != null

    /** 설정이 바뀌면(정렬/여백) 내용 영역만 다시 계산한다. */
    fun applyContentLayout(align: NoteAlign, margin: Float) {
        val r = NoteGeometry.contentRect(worldWidth, worldHeight, contentAspect, align, margin)
        contentRect.set(r)
    }

    private val undoStack = ArrayDeque<ArrayList<DrawElement>>()
    private val redoStack = ArrayDeque<ArrayList<DrawElement>>()

    companion object {
        private const val MAX_UNDO = 60
    }

    /** 요소를 변경하기 직전에 호출한다. */
    fun pushUndo() {
        undoStack.addLast(ArrayList(elements.map { it.deepCopy() }))
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(ArrayList(elements.map { it.deepCopy() }))
        elements.clear()
        elements.addAll(previous)
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(ArrayList(elements.map { it.deepCopy() }))
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
    var title: String = "훈수노트"

    val currentPage: Page? get() = pages.getOrNull(index)

    /** 새 페이지는 지금 강의의 노트 크기를 따른다. */
    val layout: NoteLayout get() = pages.firstOrNull()?.layout ?: NoteLayout.WIDE_16_9
}
