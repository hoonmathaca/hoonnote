package com.hunsu.note.io

import com.hunsu.note.model.BackgroundStyle
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.NoteLayout
import com.hunsu.note.model.Page

/**
 * PDF 열기에서 "사용자가 입력한 페이지 번호" 를 실제 노트 페이지로 바꾼다.
 *
 * 사용자는 1페이지부터 세지만 [PdfPageSource.render] 는 0부터 세는 인덱스를 받는다.
 * 이 변환을 한 곳에서만 해 두어, 1페이지짜리 PDF 처럼 페이지가 하나뿐인 문서에서도
 * 모든 페이지가 정상적으로 그려지게 한다.
 */
object PdfPages {

    /** 1부터 시작하는 페이지 번호 목록 → 0부터 시작하는 인덱스(문서 순서, 중복 제거). */
    fun toIndices(pageNumbers: List<Int>, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val result = LinkedHashSet<Int>()
        for (number in pageNumbers) {
            val index = number - 1
            if (index in 0 until pageCount) result.add(index)
        }
        return result.sorted()
    }

    /**
     * 고른 페이지들로 강의 페이지를 만든다.
     * PDF는 항상 노트 안쪽 영역에 비율을 유지한 채 최대한 크게(fit-inside) 들어가고,
     * 90/180/270 도 회전도 같은 규칙으로 노트 안에 맞춰진다.
     *
     * [sourceId] 는 이 PDF 문서의 이름표다. 3.3 부터 PDF를 이어서 쌓으므로,
     * 나중에 페이지 배경을 다시 그릴 때 어느 문서에서 왔는지 알아야 한다.
     */
    fun buildPages(
        source: PdfPageSource,
        pageNumbers: List<Int>,
        rotation: Int,
        layout: NoteLayout,
        align: NoteAlign,
        margin: Float,
        style: BackgroundStyle = BackgroundStyle.WHITE,
        sourceId: String = ""
    ): List<Page> {
        val pages = ArrayList<Page>()
        for (index in toIndices(pageNumbers, source.pageCount)) {
            val aspect = source.aspectOf(index).coerceIn(0.05f, 20f)
            val page = Page(layout, style, index, null, aspect, sourceId)
            page.setRotation(rotation)
            page.applyContentLayout(align, margin)
            pages.add(page)
        }
        return pages
    }
}
