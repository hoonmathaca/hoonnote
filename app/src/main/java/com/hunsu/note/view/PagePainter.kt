package com.hunsu.note.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.hunsu.note.model.DrawElement
import com.hunsu.note.model.ElementKind
import com.hunsu.note.model.Page
import com.hunsu.note.model.Pt
import com.hunsu.note.model.StickyNote
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 페이지(배경 + 판서 + 포스트잇)를 캔버스에 그리는 공통 로직.
 * 화면 표시(InkCanvasView)와 PNG/PDF 내보내기가 같은 결과를 갖도록 여기서만 그린다.
 */
object PagePainter {

    const val GRID_STEP = 40f
    private const val HIGHLIGHTER_ALPHA = 100

    fun isShape(kind: ElementKind): Boolean =
        kind == ElementKind.LINE || kind == ElementKind.ARROW ||
            kind == ElementKind.RECT || kind == ElementKind.OVAL

    fun rectOf(a: Pt, b: Pt): RectF = RectF(
        min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)
    )

    fun buildPath(el: DrawElement, path: Path) {
        path.reset()
        val pts = el.points
        if (pts.isEmpty()) return
        when (el.kind) {
            ElementKind.LINE, ElementKind.ARROW -> {
                path.moveTo(pts.first().x, pts.first().y)
                path.lineTo(pts.last().x, pts.last().y)
            }

            ElementKind.RECT -> path.addRect(rectOf(pts.first(), pts.last()), Path.Direction.CW)
            ElementKind.OVAL -> path.addOval(rectOf(pts.first(), pts.last()), Path.Direction.CW)
            else -> {
                path.moveTo(pts[0].x, pts[0].y)
                when (pts.size) {
                    1 -> path.lineTo(pts[0].x + 0.6f, pts[0].y + 0.6f)
                    2 -> path.lineTo(pts[1].x, pts[1].y)
                    else -> {
                        for (i in 1 until pts.size - 1) {
                            val midX = (pts[i].x + pts[i + 1].x) / 2f
                            val midY = (pts[i].y + pts[i + 1].y) / 2f
                            path.quadTo(pts[i].x, pts[i].y, midX, midY)
                        }
                        path.lineTo(pts.last().x, pts.last().y)
                    }
                }
            }
        }
    }

    fun drawPage(canvas: Canvas, page: Page, background: Bitmap?, paint: Paint) {
        // 페이지 바탕
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = if (page.isBlank) page.style.bgColor else Color.WHITE
        canvas.drawRect(0f, 0f, page.worldWidth, page.worldHeight, paint)

        if (background != null) {
            paint.color = Color.WHITE
            paint.isFilterBitmap = true
            canvas.drawBitmap(
                background, null,
                RectF(0f, 0f, page.worldWidth, page.worldHeight), paint
            )
        }

        if (page.isBlank && page.style.withGrid) drawGrid(canvas, page, paint)

        for (el in page.elements) drawElement(canvas, el, paint)
        drawNotes(canvas, page.notes, paint)
    }

    private fun drawGrid(canvas: Canvas, page: Page, paint: Paint) {
        paint.reset()
        paint.isAntiAlias = false
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x22000000
        var x = GRID_STEP
        while (x < page.worldWidth) {
            canvas.drawLine(x, 0f, x, page.worldHeight, paint)
            x += GRID_STEP
        }
        var y = GRID_STEP
        while (y < page.worldHeight) {
            canvas.drawLine(0f, y, page.worldWidth, y, paint)
            y += GRID_STEP
        }
    }

    fun drawElement(canvas: Canvas, el: DrawElement, paint: Paint) {
        if (el.points.isEmpty()) return
        val path = el.cachedPath ?: Path().also {
            buildPath(el, it)
            el.cachedPath = it
        }

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = el.width
        paint.color = el.color
        if (el.kind == ElementKind.HIGHLIGHTER) paint.alpha = HIGHLIGHTER_ALPHA
        canvas.drawPath(path, paint)

        if (el.kind == ElementKind.ARROW) {
            drawArrowHead(canvas, el, paint)
        }
    }

    private fun drawArrowHead(canvas: Canvas, el: DrawElement, paint: Paint) {
        val pts = el.points
        if (pts.size < 2) return
        val end = pts.last()
        val prev = pts[pts.size - 2]
        val dx = end.x - prev.x
        val dy = end.y - prev.y
        if (dx == 0f && dy == 0f) return
        val angle = kotlin.math.atan2(dy, dx)
        val headLength = max(el.width * 3.6f, 14f)
        val spread = 0.5f
        val a1 = angle - spread
        val a2 = angle + spread
        val head = Path()
        head.moveTo(end.x, end.y)
        head.lineTo(end.x - headLength * cos(a1), end.y - headLength * sin(a1))
        head.moveTo(end.x, end.y)
        head.lineTo(end.x - headLength * cos(a2), end.y - headLength * sin(a2))
        canvas.drawPath(head, paint)
    }

    fun drawNotes(canvas: Canvas, notes: List<StickyNote>, paint: Paint) {
        if (notes.isEmpty()) return
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = 0xFF1A1A1A.toInt()
        textPaint.textSize = 32f

        for (note in notes) {
            val left = note.x
            val top = note.y
            val right = note.x + note.width
            val bottom = note.y + note.height

            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFF3A6.toInt()
            val body = RectF(left, top, right, bottom)
            canvas.drawRoundRect(body, 10f, 10f, paint)

            paint.color = 0xFFFFE066.toInt()
            canvas.drawRoundRect(RectF(left, top, right, top + 40f), 10f, 10f, paint)

            val maxWidth = (note.width - 32f).toInt().coerceAtLeast(1)
            var y = top + 78f
            for (line in wrapText(note.text, textPaint, maxWidth)) {
                if (y > bottom - 12f) break
                canvas.drawText(line, left + 16f, y, textPaint)
                y += textPaint.textSize * 1.4f
            }
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }
            var rest = paragraph
            while (rest.isNotEmpty()) {
                val count = paint.breakText(rest, true, maxWidth.toFloat(), null)
                if (count <= 0) break
                lines.add(rest.substring(0, count))
                rest = rest.substring(count)
            }
        }
        return lines
    }
}
