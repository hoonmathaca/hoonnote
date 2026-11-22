package com.hunsu.note.io

import android.graphics.Bitmap

/**
 * 3.6: 교사용 교재의 **하늘색(시안) 계열 답안·해설 글자를 흰색으로 지워** 보여 주는 필터.
 *
 * 아이캔노트처럼 교사용 PDF 위에 판서하는데, 학생에게 보여 줄 때는 교사용에만 인쇄된
 * 하늘색 답·해설이 화면에 나오면 안 된다. 그래서 '답안 가리기'를 켜면 지금 보는 배경에서
 * 하늘색 픽셀만 흰색(#FFFFFF)으로 바꾼 **사본**을 화면에 올린다.
 *
 * 원본은 건드리지 않는다 — 배경 캐시 파일과 메모리의 원본 비트맵은 그대로 두고 사본만 만들기 때문에,
 * 버튼을 다시 끄면 원래 답안이 그대로 돌아온다.
 *
 * 색 판정 기준은 실제 교사용 교재 3장(2026-11-22 사용자 제공)으로 확인한 값이다:
 * - 색상(Hue) 168°~235° — 하늘색·시안·옅은 파랑
 * - 채도(Saturation) 0.04 이상 — 종이처럼 거의 회색인 픽셀은 남긴다
 * - 밝기(Value) 0.30 이상 — 어두운 남색·검정 글씨는 남긴다
 * - B-R 또는 G-R 이 6 이상 — 붉은/분홍/보라 계열 문제 번호와 초록 번호를 남긴다
 * 안티앨리어싱으로 옅게 번진 테두리까지 지워지면서, 검은 본문·도형·회색 상자와
 * 분홍·초록·보라 표시는 그대로 남는다.
 */
object AnswerHider {

    /** 하늘색으로 보는 색상 범위(도) */
    const val HUE_MIN = 168f
    const val HUE_MAX = 235f

    /** 이보다 채도가 낮으면(회색·종이 바탕) 지우지 않는다 */
    const val SATURATION_MIN = 0.04f

    /** 이보다 어두우면(검정·남색 글씨) 지우지 않는다 */
    const val VALUE_MIN = 0.30f

    /** 파랑·초록이 빨강보다 이만큼 밝아야 하늘색으로 본다 (분홍·보라·초록 번호 보호) */
    const val RGB_GAP_MIN = 6

    /** 지운 자리를 채우는 종이색 */
    const val PAPER_COLOR = 0xFFFFFFFF.toInt()

    /** ARGB 색이 지워야 할 하늘색 답안 색인지 */
    fun isAnswerColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return isAnswerRgb(r, g, b)
    }

    /** RGB(0~255)가 지워야 할 하늘색 답안 색인지 */
    fun isAnswerRgb(r: Int, g: Int, b: Int): Boolean {
        // 빠른 검사: 검정·회색·순백·노랑·분홍은 여기서 걸러진다.
        if (b - r < RGB_GAP_MIN && g - r < RGB_GAP_MIN) return false
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < VALUE_MIN * 255f) return false
        if (max <= 0) return false
        val saturation = (max - min) / max.toFloat()
        if (saturation < SATURATION_MIN) return false
        val hue = hueOf(r, g, b, max, min)
        return hue >= HUE_MIN && hue <= HUE_MAX
    }

    /** 색상(Hue)을 0~360도로 구한다. */
    private fun hueOf(r: Int, g: Int, b: Int, max: Int, min: Int): Float {
        val delta = (max - min).toFloat()
        if (delta <= 0f) return 0f
        val hue = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        val degrees = hue * 60f
        return if (degrees < 0f) degrees + 360f else degrees
    }

    /**
     * 하늘색 답안을 흰색으로 바꾼 새 비트맵을 만든다. [source] 는 그대로 둔다.
     * 사본은 언제나 수정 가능한 ARGB_8888 이다(하드웨어 비트맵이어도 안전).
     */
    fun hide(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("배경 비트맵을 복사할 수 없습니다")
        hideInPlace(out)
        return out
    }

    /**
     * 비트맵의 하늘색 픽셀을 그 자리에서 흰색으로 바꾼다.
     * 큰 배경(수백만 픽셀)에서도 메모리를 더 쓰지 않도록 한 줄씩 처리한다.
     * 원본 캐시에는 절대 쓰지 말고, [hide] 가 만든 사본에만 쓴다.
     */
    fun hideInPlace(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var changed = false
            for (x in 0 until w) {
                if (isAnswerColor(row[x])) {
                    row[x] = PAPER_COLOR
                    changed = true
                }
            }
            if (changed) bitmap.setPixels(row, 0, w, 0, y, w, 1)
        }
    }
}
