package com.hunsu.note.io

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 3.6 '답안 가리기' 색 필터 테스트.
 * 색 기준은 실제 교사용 교재 3장(2026-11-22 사용자 제공 사진)에서 뽑은 값이다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnswerHiderTest {

    private fun argb(r: Int, g: Int, b: Int): Int = Color.rgb(r, g, b)

    // ------------------------------------------------------------ 색 판정

    @Test
    fun `교재의 하늘색 답안 글자를 알아본다`() {
        // 사진에서 실제로 잰 하늘색 글자 핵심 색
        assertTrue("진한 하늘색", AnswerHider.isAnswerColor(argb(36, 162, 200)))
        assertTrue("조금 옅은 하늘색", AnswerHider.isAnswerColor(argb(119, 184, 222)))
        assertTrue("청록에 가까운 하늘색", AnswerHider.isAnswerColor(argb(80, 173, 204)))
        // 안티앨리어싱으로 하얗게 번진 테두리도 지워져야 한다.
        assertTrue("옅게 번진 테두리", AnswerHider.isAnswerColor(argb(231, 255, 255)))
        assertTrue("옅게 번진 테두리 2", AnswerHider.isAnswerColor(argb(241, 255, 255)))
    }

    @Test
    fun `검은 본문과 회색 상자와 흰 바탕은 건드리지 않는다`() {
        assertFalse("검은 글씨", AnswerHider.isAnswerColor(argb(17, 17, 17)))
        assertFalse("본문 검정", AnswerHider.isAnswerColor(argb(34, 34, 34)))
        assertFalse("회색 테두리", AnswerHider.isAnswerColor(argb(120, 120, 120)))
        assertFalse("옅은 회색", AnswerHider.isAnswerColor(argb(200, 200, 200)))
        assertFalse("흰 바탕", AnswerHider.isAnswerColor(argb(255, 255, 255)))
        assertFalse("살짝 누런 종이", AnswerHider.isAnswerColor(argb(252, 250, 244)))
    }

    @Test
    fun `분홍 초록 보라 문제 번호는 남긴다`() {
        assertFalse("분홍 번호", AnswerHider.isAnswerColor(argb(249, 129, 139)))
        assertFalse("초록 번호", AnswerHider.isAnswerColor(argb(0, 154, 91)))
        assertFalse("보라 난이도 표시", AnswerHider.isAnswerColor(argb(142, 127, 208)))
        assertFalse("주황 표시", AnswerHider.isAnswerColor(argb(255, 152, 0)))
    }

    @Test
    fun `너무 어두운 남색 글씨는 지우지 않는다`() {
        // 밝기 기준(0.30)보다 어두우면 본문으로 보고 남긴다.
        assertFalse(AnswerHider.isAnswerColor(argb(10, 40, 70)))
    }

    // ------------------------------------------------------------ 비트맵 변환

    @Test
    fun `하늘색만 흰색으로 바꾸고 원본 비트맵은 그대로 둔다`() {
        val source = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        val blue = argb(36, 162, 200)
        val black = argb(17, 17, 17)
        val pink = argb(249, 129, 139)
        val paleCyan = argb(231, 255, 255)
        source.setPixel(0, 0, blue)
        source.setPixel(1, 0, black)
        source.setPixel(2, 0, Color.WHITE)
        source.setPixel(3, 0, pink)
        source.setPixel(0, 1, paleCyan)
        source.setPixel(1, 1, argb(120, 120, 120))
        source.setPixel(2, 1, argb(80, 173, 204))
        source.setPixel(3, 1, argb(0, 154, 91))

        val hidden = AnswerHider.hide(source)

        assertEquals("하늘색 글자", Color.WHITE, hidden.getPixel(0, 0))
        assertEquals("옅은 하늘색 테두리", Color.WHITE, hidden.getPixel(0, 1))
        assertEquals("두 번째 하늘색 글자", Color.WHITE, hidden.getPixel(2, 1))
        assertEquals("검은 본문", black, hidden.getPixel(1, 0))
        assertEquals("흰 바탕", Color.WHITE, hidden.getPixel(2, 0))
        assertEquals("분홍 번호", pink, hidden.getPixel(3, 0))
        assertEquals("회색", argb(120, 120, 120), hidden.getPixel(1, 1))
        assertEquals("초록 번호", argb(0, 154, 91), hidden.getPixel(3, 1))

        // 원본은 건드리지 않는다 — 버튼을 끄면 그대로 돌아와야 한다.
        assertEquals("원본이 바뀌었습니다", blue, source.getPixel(0, 0))
        assertEquals("원본이 바뀌었습니다", paleCyan, source.getPixel(0, 1))
        assertFalse("사본이 원본과 같은 객체입니다", source === hidden)
    }

    @Test
    fun `여러 줄 비트맵에서 줄마다 빠짐없이 지운다`() {
        val w = 37
        val h = 11
        val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val blue = argb(36, 162, 200)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 한 줄 걸러 하늘색을 깔고 나머지는 검정 글씨로 둔다.
                source.setPixel(x, y, if (y % 2 == 0) blue else argb(20, 20, 20))
            }
        }

        AnswerHider.hideInPlace(source)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val expected = if (y % 2 == 0) Color.WHITE else argb(20, 20, 20)
                assertEquals("($x, $y)", expected, source.getPixel(x, y))
            }
        }
    }

    @Test
    fun `답안이 없는 페이지는 한 픽셀도 바뀌지 않는다`() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                source.setPixel(x, y, if ((x + y) % 2 == 0) Color.WHITE else argb(30, 30, 30))
            }
        }
        val hidden = AnswerHider.hide(source)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                assertEquals(source.getPixel(x, y), hidden.getPixel(x, y))
            }
        }
    }
}
