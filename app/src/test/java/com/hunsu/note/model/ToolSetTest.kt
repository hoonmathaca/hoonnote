package com.hunsu.note.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.4 에서 바뀐 도형·떡메모지·글자·포스트잇 도구 구성과 기하 테스트 */
class ToolSetTest {

    @Test
    fun `이동줌과 반직선 대신 점선 직선이 들어간다`() {
        val names = Tool.values().map { it.name }
        assertEquals(false, names.contains("HAND"))
        assertEquals(false, names.contains("ARROW"))
        assertTrue(names.contains("DASHED_LINE"))
        assertEquals(false, ElementKind.values().map { it.name }.contains("ARROW"))
    }

    @Test
    fun `삼각형 덮개 글자 도구가 추가된다`() {
        assertTrue(Tool.values().contains(Tool.TRIANGLE))
        assertTrue(Tool.values().contains(Tool.COVER))
        assertTrue(Tool.values().contains(Tool.TEXT))
        assertEquals(ElementKind.TRIANGLE, ElementKind.valueOf("TRIANGLE"))
        assertEquals(ElementKind.COVER, ElementKind.valueOf("COVER"))
        assertEquals(ElementKind.TEXT, ElementKind.valueOf("TEXT"))
    }

    @Test
    fun `글자 크기 단계는 펜 굵기보다 크고 여섯 단계다`() {
        val values = StrokeWidths.of(Tool.TEXT)
        assertEquals(6, values.size)
        assertEquals(StrokeWidths.TEXT.toList(), values.toList())
        assertTrue(values.first() > StrokeWidths.PEN.last())
        for (i in 1 until values.size) assertTrue(values[i] > values[i - 1])
    }

    @Test
    fun `형광펜과 펜 단계는 그대로다`() {
        assertEquals(StrokeWidths.HIGHLIGHTER.toList(), StrokeWidths.of(Tool.HIGHLIGHTER).toList())
        assertEquals(StrokeWidths.PEN.toList(), StrokeWidths.of(Tool.PEN).toList())
        assertEquals(StrokeWidths.PEN.toList(), StrokeWidths.of(Tool.TRIANGLE).toList())
    }

    @Test
    fun `포스트잇은 그린 사각형 자리에 놓인다`() {
        val note = StickyGeometry.fromDrag(600f, 500f, 900f, 700f)
        assertEquals(600f, note.x, 0.01f)
        assertEquals(500f, note.y, 0.01f)
        assertEquals(300f, note.width, 0.01f)
        assertEquals(200f, note.height, 0.01f)
    }

    @Test
    fun `거꾸로 그려도 포스트잇은 올바른 사각형이 된다`() {
        val note = StickyGeometry.fromDrag(900f, 700f, 600f, 500f)
        assertEquals(600f, note.x, 0.01f)
        assertEquals(500f, note.y, 0.01f)
        assertEquals(300f, note.width, 0.01f)
        assertEquals(200f, note.height, 0.01f)
    }

    @Test
    fun `탭만 하면 누른 자리에 기본 크기 포스트잇이 생긴다`() {
        val note = StickyGeometry.fromDrag(1000f, 800f, 1003f, 801f)
        assertEquals(StickyGeometry.DEFAULT_WIDTH, note.width, 0.01f)
        assertEquals(StickyGeometry.DEFAULT_HEIGHT, note.height, 0.01f)
        assertEquals(1000f - StickyGeometry.DEFAULT_WIDTH / 2f, note.x, 0.01f)
        assertEquals(800f - StickyGeometry.DEFAULT_HEIGHT / 2f, note.y, 0.01f)
    }

    @Test
    fun `너무 얇게 그린 포스트잇은 최소 크기로 늘어난다`() {
        val note = StickyGeometry.fromDrag(100f, 100f, 700f, 104f)
        assertEquals(600f, note.width, 0.01f)
        assertEquals(StickyGeometry.MIN_HEIGHT, note.height, 0.01f)
    }
}
