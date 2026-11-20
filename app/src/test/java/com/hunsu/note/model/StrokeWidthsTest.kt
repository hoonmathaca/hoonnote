package com.hunsu.note.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 펜·형광펜 굵기 단계 테스트 */
class StrokeWidthsTest {

    @Test
    fun `펜 단계는 세필부터 아주 굵은 선까지 이어진다`() {
        val values = StrokeWidths.PEN
        assertEquals(6, values.size)
        assertEquals(1.5f, values.first(), 0.001f)
        assertEquals(22f, values.last(), 0.001f)
        for (i in 1 until values.size) {
            assertEquals(true, values[i] > values[i - 1])
        }
    }

    @Test
    fun `형광펜은 펜보다 굵은 단계를 쓴다`() {
        assertEquals(true, StrokeWidths.HIGHLIGHTER.last() > StrokeWidths.PEN.last())
    }

    @Test
    fun `현재 굵기와 가까운 단계를 고른다`() {
        assertEquals(0, StrokeWidths.nearestIndex(StrokeWidths.PEN, 1.6f))
        assertEquals(3, StrokeWidths.nearestIndex(StrokeWidths.PEN, 7.7f))
        assertEquals(5, StrokeWidths.nearestIndex(StrokeWidths.PEN, 30f))
    }

    @Test
    fun `단계 이름은 소수점을 정리해 보여준다`() {
        assertEquals("1.5", StrokeWidths.label(1.5f))
        assertEquals("8", StrokeWidths.label(8f))
    }
}
