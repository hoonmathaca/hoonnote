package com.hunsu.note.rec

/**
 * 녹화 화질(용량) 프리셋.
 *
 * 녹화는 3.1 처럼 전체화면 한 가지 방식만 쓰고, 이 프리셋이 해상도·프레임·비트레이트를
 * 한 번에 정한다. 해상도/프레임/비트레이트를 직접 고른 값은 [matchOf] 로 어느 프리셋과
 * 같은지 판단한다(어느 것도 아니면 `null` = 직접 설정).
 *
 * 용량 계산은 초당 (영상 + 소리) 비트레이트를 1분으로 늘려 MB(1024 기준)로 올림한 값이다.
 * 실제 MP4 는 헤더·가변 비트레이트 때문에 조금 작거나 클 수 있으므로 "약" 값으로만 쓴다.
 */
enum class RecordQuality(
    /** 녹화 세로 해상도(px). 화면이 더 작으면 화면 높이를 따른다. */
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int
) {
    /** 일반 (고화질) — 판서가 가장 선명하다. 1080p 30fps 12Mbps. */
    NORMAL(1080, 30, 12_000),

    /** 절약 (720p 저용량 · 강의 공유용) — 선오공 Q&A 답변 영상처럼 짧게 올릴 때. */
    ECONOMY(720, 24, 1_500);

    /** 1분 녹화했을 때의 예상 용량(MB, 올림). */
    fun estimatedMbPerMinute(withMic: Boolean): Int =
        estimatedMbPerMinute(bitrateKbps, withMic)

    /** [limitMb] 이하로 담을 수 있는 최대 길이(분, 내림). 1분 미만이면 0. */
    fun maxMinutesWithin(limitMb: Int, withMic: Boolean): Int {
        val kbps = bitrateKbps + if (withMic) AUDIO_KBPS else 0
        val seconds = limitMb.toLong() * 1024L * 8L / kbps
        return (seconds / 60L).toInt()
    }

    companion object {
        /** 영상과 함께 저장하는 소리 비트레이트(kbps). */
        const val AUDIO_KBPS = 128

        /** 처음 설치·설정 초기화 때의 화질. 선오공 Q&A 에 올리기 좋은 저용량 쪽이 기본이다. */
        val DEFAULT = ECONOMY

        /** 지금 값이 어느 프리셋과 똑같은지. 직접 고른 값이면 null. */
        fun matchOf(height: Int, fps: Int, bitrateKbps: Int): RecordQuality? =
            entries.firstOrNull {
                it.height == height && it.fps == fps && it.bitrateKbps == bitrateKbps
            }

        /** 지금 값에 해당하는 프리셋. 직접 고른 값이면 [DEFAULT]. */
        fun of(height: Int, fps: Int, bitrateKbps: Int): RecordQuality =
            matchOf(height, fps, bitrateKbps) ?: DEFAULT

        /** 임의의 비트레이트로 1분 녹화했을 때의 예상 용량(MB, 올림). */
        fun estimatedMbPerMinute(bitrateKbps: Int, withMic: Boolean): Int {
            val kbps = bitrateKbps + if (withMic) AUDIO_KBPS else 0
            val kb = kbps.toLong() * 60L / 8L
            return ((kb + 1023L) / 1024L).toInt()
        }
    }
}
