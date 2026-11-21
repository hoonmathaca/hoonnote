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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 페이지(노트 배경 + PDF/이미지 + 판서 + 포스트잇)를 캔버스에 그리는 공통 로직.
 * 화면 표시(InkCanvasView)와 PNG/PDF 내보내기, 썸네일이 같은 결과를 갖도록 여기서만 그린다.
 */
object PagePainter {

    const val GRID_STEP = 40f

    /** 덮개 떡메모지 색 — 배경을 완전히 가리는 불투명 흰색 */
    const val COVER_COLOR = 0xFFFFFFFF.toInt()

    /** 형광펜은 같은 색을 옅게 덮어쓴다(약 43%). */
    const val HIGHLIGHTER_ALPHA = 110
    private const val GUIDE_STEP = 96f

    fun isShape(kind: ElementKind): Boolean =
        kind == ElementKind.LINE || kind == ElementKind.DASHED_LINE ||
            kind == ElementKind.RECT || kind == ElementKind.OVAL ||
            kind == ElementKind.TRIANGLE

    /** 부분 지우개로 조각내지 않고 통째로 지우는 요소 (도형·글자·덮개) */
    fun isAtomic(kind: ElementKind): Boolean =
        isShape(kind) || kind == ElementKind.TEXT || kind == ElementKind.COVER

    fun rectOf(a: Pt, b: Pt): RectF = RectF(
        min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)
    )

    /** 드래그한 사각형에 꼭 맞는 삼각형(위 꼭대기 - 아래 양 모서리) 경로 */
    fun buildTriangle(r: RectF, path: Path) {
        path.moveTo(r.centerX(), r.top)
        path.lineTo(r.right, r.bottom)
        path.lineTo(r.left, r.bottom)
        path.close()
    }

    /**
     * 글자 요소의 줄 목록. 줄바꿈(\n)을 그대로 유지한다.
     */
    fun textLines(text: String): List<String> = text.split("\n")

    /** 글자 한 줄의 기준선 간격 */
    const val TEXT_LINE_SPACING = 1.3f

    /**
     * 글자 요소가 차지하는 영역. points[0] 이 글자의 왼쪽 위 모서리다.
     * 지우개 판정과 편집 상자에 같은 값을 쓴다.
     */
    fun textBounds(el: DrawElement, paint: Paint): RectF {
        val lines = textLines(el.text)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = el.width
        var widest = 0f
        for (line in lines) widest = max(widest, paint.measureText(line))
        val lineHeight = el.width * TEXT_LINE_SPACING
        val anchor = el.points.firstOrNull() ?: Pt(0f, 0f)
        return RectF(
            anchor.x,
            anchor.y,
            anchor.x + max(widest, el.width * 0.4f),
            anchor.y + max(lines.size, 1) * lineHeight
        )
    }

    /** 글자 한 줄씩의 영역 (지우개가 빈 여백을 건드리지 않게) */
    fun textLineRects(el: DrawElement, paint: Paint): List<RectF> {
        val lines = textLines(el.text)
        paint.reset()
        paint.isAntiAlias = true
        paint.textSize = el.width
        val lineHeight = el.width * TEXT_LINE_SPACING
        val anchor = el.points.firstOrNull() ?: Pt(0f, 0f)
        return lines.mapIndexed { i, line ->
            RectF(
                anchor.x,
                anchor.y + i * lineHeight,
                anchor.x + max(paint.measureText(line), el.width * 0.3f),
                anchor.y + (i + 1) * lineHeight
            )
        }
    }

    fun buildPath(el: DrawElement, path: Path) {
        path.reset()
        val pts = el.points
        if (pts.isEmpty()) return
        when (el.kind) {
            ElementKind.LINE, ElementKind.DASHED_LINE -> {
                path.moveTo(pts.first().x, pts.first().y)
                path.lineTo(pts.last().x, pts.last().y)
            }

            ElementKind.RECT -> path.addRect(rectOf(pts.first(), pts.last()), Path.Direction.CW)
            ElementKind.OVAL -> path.addOval(rectOf(pts.first(), pts.last()), Path.Direction.CW)
            ElementKind.TRIANGLE -> buildTriangle(rectOf(pts.first(), pts.last()), path)
            ElementKind.COVER -> path.addRect(rectOf(pts.first(), pts.last()), Path.Direction.CW)
            ElementKind.TEXT -> Unit
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
        when (el.kind) {
            ElementKind.COVER -> {
                drawCover(canvas, el, paint)
                return
            }

            ElementKind.TEXT -> {
                drawText(canvas, el, paint)
                return
            }

            else -> Unit
        }
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
        if (el.kind == ElementKind.DASHED_LINE) {
            paint.pathEffect = DashPathEffect(
                floatArrayOf(max(el.width * 2.6f, 9f), max(el.width * 2f, 7f)),
                0f
            )
        }
        canvas.drawPath(path, paint)
        paint.pathEffect = null
    }

    /**
     * 덮개 떡메모지: 아래(배경·먼저 쓴 필기)를 완전히 가리는 불투명 흰 사각형.
     * 그래서 이 요소 뒤에 그려진 것은 가려지고, 이 뒤에 새로 쓴 필기는 위에 보인다.
     */
    private fun drawCover(canvas: Canvas, el: DrawElement, paint: Paint) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = COVER_COLOR
        canvas.drawRect(rectOf(el.points.first(), el.points.last()), paint)
    }

    /** 글자: 배경·테두리 없이 선택한 색과 크기로 글자만 그린다. */
    private fun drawText(canvas: Canvas, el: DrawElement, paint: Paint) {
        if (el.text.isEmpty()) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = el.color
        paint.textSize = el.width
        val anchor = el.points.first()
        val lineHeight = el.width * TEXT_LINE_SPACING
        var baseline = anchor.y + el.width * 0.92f
        for (line in textLines(el.text)) {
            canvas.drawText(line, anchor.x, baseline, paint)
            baseline += lineHeight
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
