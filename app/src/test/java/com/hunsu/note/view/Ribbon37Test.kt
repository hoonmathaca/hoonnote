package com.hunsu.note.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.hunsu.note.model.DrawElement
import com.hunsu.note.model.ElementKind
import com.hunsu.note.model.Page
import com.hunsu.note.model.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 3.7: 필압 획의 시작·끝에 동그란 구멍이 뚫리던 문제.
 *
 * 원인 — 리본 외곽선을 만든 뒤 시작·끝에 `addCircle(…, Path.Direction.CW)` 로 원을 더했는데,
 * 리본 외곽선의 감김 방향이 그 반대라 겹친 부분의 감김이 0이 되어(nonzero 채움)
 * 획 끝 안쪽이 뚫렸다. 화면에서는 '획 시작·끝마다 작은 동그란 구멍/테두리'로 보였다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ribbon37Test {

    private fun render(el: DrawElement, w: Int = 360, h: Int = 260): Bitmap {
        val page = Page()
        page.elements.add(el)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        PagePainter.drawElement(canvas, el, Paint())
        return bmp
    }

    private fun pressureStroke(vararg points: Pt, width: Float = 20f): DrawElement =
        DrawElement(
            ElementKind.STROKE,
            Color.BLACK,
            width,
            points.toMutableList(),
            MutableList(points.size) { 0.6f }
        )

    private fun isDark(bitmap: Bitmap, x: Int, y: Int): Boolean {
        val c = Color.red(bitmap.getPixel(x, y))
        return c < 60
    }

    /** 가로로 그은 필압 획: 시작·끝 안쪽이 뚫리지 않고 가운데까지 꽉 차 있어야 한다. */
    @Test
    fun pressureStrokeIsSolidFromStartToEnd() {
        val bmp = render(pressureStroke(Pt(100f, 130f), Pt(240f, 130f)))
        for (x in 100..240) {
            assertTrue("x=$x 자리가 뚫려 있다", isDark(bmp, x, 130))
        }
        // 획 바깥은 흰색 그대로
        assertTrue(!isDark(bmp, 240 + 20, 130))
        assertTrue(!isDark(bmp, 100 - 20, 130))
    }

    /** 끝은 둥글게 마감되어야 한다 — 획 끝 바로 너머(반지름 안)는 검고, 모서리 밖은 흰색. */
    @Test
    fun pressureStrokeEndIsRound() {
        val bmp = render(pressureStroke(Pt(100f, 130f), Pt(240f, 130f)))
        // 끝점(240)에서 8px 더 간 곳은 반지름(10) 안쪽 → 검다
        assertTrue("둥근 마감이 없다", isDark(bmp, 246, 130))
        // 반지름 밖 대각선은 흰색
        assertTrue(!isDark(bmp, 248, 141))
    }

    /** 휘어진 획도 시작·끝에 구멍이 없어야 한다. */
    @Test
    fun curvedPressureStrokeHasNoHoleAtEnds() {
        val pts = ArrayList<Pt>()
        for (i in 0..60) {
            val t = i / 60f
            pts.add(Pt(60f + 240f * t, 120f + 60f * kotlin.math.sin(t * Math.PI * 2).toFloat()))
        }
        val el = DrawElement(
            ElementKind.STROKE,
            Color.BLACK,
            18f,
            pts,
            MutableList(pts.size) { 0.5f + 0.4f * (it % 3) / 2f }
        )
        val bmp = render(el, 380, 260)
        for (p in listOf(pts.first(), pts.last())) {
            val cx = p.x.toInt()
            val cy = p.y.toInt()
            assertTrue("획 끝($cx,$cy)이 뚫려 있다", isDark(bmp, cx, cy))
        }
    }

    /** 필압이 없는 획(STROKE 방식)은 원래부터 문제가 없었다 — 그대로 유지되는지 확인한다. */
    @Test
    fun plainStrokeIsStillSolid() {
        val el = DrawElement(
            ElementKind.STROKE,
            Color.BLACK,
            20f,
            arrayListOf(Pt(100f, 130f), Pt(240f, 130f))
        )
        val bmp = render(el)
        for (x in 106..234) {
            assertEquals(0, Color.red(bmp.getPixel(x, 130)))
        }
    }

    /** 필압이 한 점에서만 아주 얇아도 구멍이 생기지 않는다. */
    @Test
    fun veryLowPressureStillFilled() {
        val el = DrawElement(
            ElementKind.STROKE,
            Color.BLACK,
            40f,
            arrayListOf(Pt(100f, 130f), Pt(180f, 130f), Pt(250f, 130f)),
            arrayListOf(0.01f, 0.01f, 0.01f)
        )
        val bmp = render(el, 360, 260)
        for (x in 100..250) {
            assertTrue("x=$x", isDark(bmp, x, 130))
        }
    }
}
