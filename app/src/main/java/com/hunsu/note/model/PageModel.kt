package com.hunsu.note.model

import android.graphics.Bitmap
import android.graphics.Path

/** 현재 선택된 판서 도구 */
enum class Tool { PEN, HIGHLIGHTER, ERASER, LINE, ARROW, RECT, OVAL }

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

/** 강의 페이지 배경 */
enum class BackgroundStyle(val bgColor: Int, val withGrid: Boolean) {
    WHITE(0xFFFFFFFF.toInt(), false),
    GREEN(0xFF20402E.toInt(), false),
    NAVY(0xFF12203A.toInt(), false),
    GRID(0xFFFFFFFF.toInt(), true)
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
 * 한 페이지. 빈 페이지/PDF 페이지/이미지 페이지를 모두 표현한다.
 * PDF 페이지는 pdfPageIndex >= 0, 이미지 페이지는 image != null 로 구분한다.
 */
class Page(
    val worldWidth: Float,
    val worldHeight: Float,
    val style: BackgroundStyle = BackgroundStyle.WHITE,
    val pdfPageIndex: Int = -1,
    val image: Bitmap? = null
) {
    val elements = ArrayList<DrawElement>()
    val notes = ArrayList<StickyNote>()

    /** 화면 표시용으로 미리 렌더링해 둔 배경(PDF/이미지) */
    var renderedBackground: Bitmap? = null

    val isBlank: Boolean get() = pdfPageIndex < 0 && image == null

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
}
