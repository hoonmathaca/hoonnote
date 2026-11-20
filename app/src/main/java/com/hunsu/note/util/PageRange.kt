package com.hunsu.note.util

/**
 * PDF를 열기 전에 입력한 페이지 범위 문자열을 실제 페이지 번호 목록으로 바꾼다.
 *
 * 지원하는 표기
 *  - "전체", "all"            : 1 ~ pageCount
 *  - "5"                      : 5페이지 하나
 *  - "1-5", "1~5"             : 1 ~ 5
 *  - "3-"                     : 3페이지부터 끝까지
 *  - "-4"                     : 처음부터 4페이지까지
 *  - "1-3, 7, 9-10"           : 여러 범위 (쉼표·공백·세미콜론 구분)
 *
 * 범위를 벗어난 값은 문서 범위로 잘라내고, 순서는 문서 순서로 정렬한다.
 * 알아볼 수 없는 입력이면 null 을 돌려준다.
 */
object PageRange {

    private const val MAX_PAGES = 2000

    fun parse(text: String?, pageCount: Int): List<Int>? {
        if (pageCount <= 0) return null
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (isAll(raw)) return (1..minOf(pageCount, MAX_PAGES)).toList()

        val result = LinkedHashSet<Int>()
        // '1 - 5', '1~5' 같은 표기를 '1-5' 로 맞춘 뒤 쉼표·공백으로 나눈다.
        val normalized = raw.replace(Regex("\\s*[-~–—]\\s*"), "-").trim()
        val tokens = normalized
            .split(Regex("[,\\s;]+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return null

        for (token in tokens) {
            val cleaned = token.trim().removeSuffix("p").removeSuffix("P").removeSuffix("페이지")
            if (cleaned.isEmpty()) return null
            val dash = cleaned.indexOfFirst { it == '-' }
            if (dash < 0) {
                val page = cleaned.toIntOrNull() ?: return null
                if (page < 1) return null
                if (page <= pageCount) result.add(page)
                continue
            }
            val startText = cleaned.substring(0, dash).trim()
            val endText = cleaned.substring(dash + 1).trim()
            val start = if (startText.isEmpty()) 1 else (startText.toIntOrNull() ?: return null)
            val end = if (endText.isEmpty()) pageCount else (endText.toIntOrNull() ?: return null)
            if (start < 1 || end < 1) return null
            val from = minOf(start, end).coerceAtMost(pageCount)
            val to = maxOf(start, end).coerceAtMost(pageCount)
            for (p in from..to) {
                result.add(p)
                if (result.size >= MAX_PAGES) break
            }
        }
        if (result.isEmpty()) return null
        return result.sorted()
    }

    private fun isAll(text: String): Boolean {
        val compact = text.replace(" ", "").lowercase()
        return compact == "전체" || compact == "전체페이지" || compact == "all" ||
            compact == "allpages" || compact == "*"
    }
}
