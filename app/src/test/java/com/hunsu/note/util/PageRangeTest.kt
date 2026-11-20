package com.hunsu.note.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PDF 열기 전 페이지 범위 입력 파싱 테스트 */
class PageRangeTest {

    @Test
    fun `전체는 모든 페이지`() {
        assertEquals((1..12).toList(), PageRange.parse("전체", 12))
        assertEquals((1..12).toList(), PageRange.parse("all", 12))
    }

    @Test
    fun `범위 표기`() {
        assertEquals((1..5).toList(), PageRange.parse("1-5", 10))
        assertEquals((2..4).toList(), PageRange.parse(" 2 ~ 4 ", 10))
    }

    @Test
    fun `한 장만`() {
        assertEquals(listOf(3), PageRange.parse("3", 10))
    }

    @Test
    fun `시작만 지정하면 끝까지`() {
        assertEquals((7..10).toList(), PageRange.parse("7-", 10))
    }

    @Test
    fun `끝만 지정하면 처음부터`() {
        assertEquals((1..4).toList(), PageRange.parse("-4", 10))
    }

    @Test
    fun `여러 범위는 합쳐서 정렬`() {
        assertEquals(listOf(1, 2, 3, 7, 9, 10), PageRange.parse("9-10, 1-3, 7", 10))
    }

    @Test
    fun `문서 범위를 넘는 값은 잘라낸다`() {
        assertEquals((8..10).toList(), PageRange.parse("8-40", 10))
        assertNull(PageRange.parse("20", 10))
    }

    @Test
    fun `거꾸로 쓴 범위도 인정`() {
        assertEquals((3..5).toList(), PageRange.parse("5-3", 10))
    }

    @Test
    fun `잘못된 입력은 null`() {
        assertNull(PageRange.parse("", 10))
        assertNull(PageRange.parse(null, 10))
        assertNull(PageRange.parse("abc", 10))
        assertNull(PageRange.parse("1-a", 10))
        assertNull(PageRange.parse("0", 10))
        assertNull(PageRange.parse("1-3", 0))
    }
}
