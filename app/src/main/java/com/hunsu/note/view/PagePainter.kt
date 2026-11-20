package com.hunsu.note.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
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
import kotlin.math.sqrt

/**
 * 페이지(노트 배경 + PDF/이미지 + 판서 + 포스트잇)를 캔버스에 그리는 공통 로직.
 * 화면 표시(InkCanvasView)와 PNG/PDF 내보내기, 썸네일이 같은 결과를 갖도록 여기서만 그린다.
 */
object PagePainter {

    const val GRID_STEP = 40f

    /** 형광펜은 같은 색을 옅게 덮어쓴다(약 43%). */
    const val HIGHLIGHTER_ALPHA = 110
    private const val GUIDE_STEP = 96f

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

    /**
     * 필압으로 굵기가 변하는 선은 획 폭이 점마다 달라지므로,
     * 양쪽 외곽선을 이어 붙인 하나의 채움 경로(리본)로 만든다.
     */
    fun buildRibbon(el: DrawElement, path: Path) {
        path.reset()
        val pts = el.points
        val n = pts.size
        if (n == 0) return
        if (n == 1) {
            path.addCircle(pts[0].x, pts[0].y, max(el.widthAt(0) / 2f, 0.5f), Path.Direction.CW)
            return
        }

        val leftX = FloatArray(n)
        val leftY = FloatArray(n)
        val rightX = FloatArray(n)
        val rightY = FloatArray(n)

        for (i in 0 until n) {
            val prev = pts[if (i > 0) i - 1 else i]
            val next = pts[if (i < n - 1) i + 1 else i]
            var dx = next.x - prev.x
            var dy = next.y - prev.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.0001f) {
                dx = 1f
                dy = 0f
            }
            val nx = -dy / max(len, 0.0001f)
            val ny = dx / max(len, 0.0001f)
            val half = max(el.widthAt(i) / 2f, 0.3f)
            leftX[i] = pts[i].x + nx * half
            leftY[i] = pts[i].y + ny * half
            rightX[i] = pts[i].x - nx * half
            rightY[i] = pts[i].y - ny * half
        }

        path.moveTo(leftX[0], leftY[0])
        for (i in 1 until n) path.lineTo(leftX[i], leftY[i])
        for (i in n - 1 downTo 0) path.lineTo(rightX[i], rightY[i])
        path.close()
        // 시작·끝을 둥글게 마감한다.
        path.addCircle(pts[0].x, pts[0].y, max(el.widthAt(0) / 2f, 0.3f), Path.Direction.CW)
        path.addCircle(
            pts[n - 1].x, pts[n - 1].y, max(el.widthAt(n - 1) / 2f, 0.3f), Path.Direction.CW
        )
    }

    /**
     * 노트 전체를 그린다.
     * @param background PDF/이미지 배경. page.contentRect 안에만, page.rotation 만큼 돌려 그려진다.
     */
    fun drawPage(canvas: Canvas, page: Page, background: Bitmap?, paint: Paint) {
        // 노트 바탕 (PDF가 있어도 여백은 노트 배경색으로 남는다)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = page.style.bgColor
        canvas.drawRect(0f, 0f, page.worldWidth, page.worldHeight, paint)

        if (page.style.withGrid && !page.hasContent) drawGrid(canvas, page, paint)

        val content = RectF(page.contentRect)
        if (background != null && !content.isEmpty) {
            drawContent(canvas, page, background, content, paint)
        }

        if (!content.isEmpty) drawMarginGuides(canvas, page, content, paint)

        for (el in page.elements) drawElement(canvas, el, paint)
        drawNotes(canvas, page.notes, paint)
    }

    /** PDF/이미지를 회전까지 반영해 contentRect 안에 그린다. */
    private fun drawContent(
        canvas: Canvas,
        page: Page,
        background: Bitmap,
        content: RectF,
        paint: Paint
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.color = Color.WHITE

        val rotation = page.rotation % 360
        if (rotation == 0) {
            canvas.drawBitmap(background, null, content, paint)
            return
        }
        val dst = RectF(content)
        if (rotation % 180 == 90) {
            val w = content.height()
            val h = content.width()
            dst.set(
                content.centerX() - w / 2f,
                content.centerY() - h / 2f,
                content.centerX() + w / 2f,
                content.centerY() + h / 2f
            )
        }
        canvas.save()
        canvas.rotate(rotation.toFloat(), content.centerX(), content.centerY())
        canvas.drawBitmap(background, null, dst, paint)
        canvas.restore()
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

    /**
     * PDF 오른쪽(또는 양옆) 여백에 옅은 점선 안내선을 그린다.
     * 아이캔노트처럼 풀이 공간이 어디인지 눈에 보이게 한다.
     */
    private fun drawMarginGuides(canvas: Canvas, page: Page, content: RectF, paint: Paint) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = 0x1A000000
        paint.pathEffect = DashPathEffect(floatArrayOf(10f, 12f), 0f)

        val rightStart = content.right + 32f
        val rightEnd = page.worldWidth - 32f
        if (rightEnd - rightStart > 80f) {
            var y = content.top + GUIDE_STEP
            while (y < content.bottom) {
                canvas.drawLine(rightStart, y, rightEnd, y, paint)
                y += GUIDE_STEP
            }
        }

        val leftEnd = content.left - 32f
        if (leftEnd > 32f) {
            var y = content.top + GUIDE_STEP
            while (y < content.bottom) {
                canvas.drawLine(32f, y, leftEnd, y, paint)
                y += GUIDE_STEP
            }
        }
        paint.pathEffect = null
    }

    fun drawElement(canvas: Canvas, el: DrawElement, paint: Paint) {
        if (el.points.isEmpty()) return
        if (el.hasPressure) {
            drawPressureElement(canvas, el, paint)
            return
        }
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

    private fun drawPressureElement(canvas: Canvas, el: DrawElement, paint: Paint) {
        val path = el.cachedPath ?: Path().also {
            buildRibbon(el, it)
            el.cachedPath = it
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = el.color
        if (el.kind == ElementKind.HIGHLIGHTER) paint.alpha = HIGHLIGHTER_ALPHA
        canvas.drawPath(path, paint)
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
