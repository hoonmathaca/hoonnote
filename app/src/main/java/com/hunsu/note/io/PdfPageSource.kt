package com.hunsu.note.io

import android.graphics.Bitmap
import java.io.Closeable

/**
 * PDF 페이지를 그려 주는 쪽의 공통 규약.
 *
 * 실제 구현은 [PdfPageProvider] 이고, 테스트에서는 같은 규약의 가짜 구현을 넣어
 * "1페이지짜리 PDF를 열었을 때 실제로 배경이 그려지는지" 를 픽셀 단위로 확인한다.
 *
 * [render] 의 pageIndex 는 0부터 시작하는 인덱스다(PDF 파일의 '1페이지' = index 0).
 */
interface PdfPageSource : Closeable {

    val isOpen: Boolean

    val pageCount: Int

    /** 회전 전 원본의 가로/세로 비율 (가로 ÷ 세로) */
    fun aspectOf(index: Int): Float

    /** index 페이지를 targetWidth 픽셀 폭(비율 유지)으로 그린 비트맵. 실패하면 null. */
    fun render(index: Int, targetWidth: Int): Bitmap?
}
