package com.hunsu.note.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 녹화 화질(용량) 프리셋 계산 검증.
 * 설정 화면과 녹화 서비스가 같은 값을 쓰는지(해상도·프레임·비트레이트·예상 용량) 확인한다.
 */
class RecordQualityTest {

    @Test
    fun `프리셋 값이 요구한 화질과 용량을 만든다`() {
        assertEquals(1080, RecordQuality.NORMAL.height)
        assertEquals(30, RecordQuality.NORMAL.fps)
        assertEquals(12_000, RecordQuality.NORMAL.bitrateKbps)

        assertEquals(720, RecordQuality.ECONOMY.height)
        assertEquals(24, RecordQuality.ECONOMY.fps)
        assertEquals(1_500, RecordQuality.ECONOMY.bitrateKbps)

        // 기본값은 선오공 Q&A 에 올리기 좋은 저용량 쪽
        assertEquals(RecordQuality.ECONOMY, RecordQuality.DEFAULT)
    }

    @Test
    fun `1분 예상 용량 - 절약은 10MB대, 일반은 90MB 안팎`() {
        // 720p 1.5Mbps + 128kbps 소리 = 약 12MB/분
        assertEquals(12, RecordQuality.ECONOMY.estimatedMbPerMinute(withMic = true))
        assertEquals(11, RecordQuality.ECONOMY.estimatedMbPerMinute(withMic = false))
        // 1080p 12Mbps + 128kbps 소리 = 약 89MB/분
        assertEquals(89, RecordQuality.NORMAL.estimatedMbPerMinute(withMic = true))
        assertEquals(88, RecordQuality.NORMAL.estimatedMbPerMinute(withMic = false))
    }

    @Test
    fun `절약 화질은 선오공 QnA 50MB 에 4분까지 들어간다`() {
        assertEquals(4, RecordQuality.ECONOMY.maxMinutesWithin(50, withMic = true))
        // 일반 화질은 30초 남짓이라 짧은 답변도 50MB 를 넘는다
        assertEquals(0, RecordQuality.NORMAL.maxMinutesWithin(50, withMic = true))
    }

    @Test
    fun `임의 비트레이트의 예상 용량도 같은 식으로 계산한다`() {
        // 4Mbps + 128kbps = 약 31MB/분
        assertEquals(31, RecordQuality.estimatedMbPerMinute(4_000, withMic = true))
        assertEquals(30, RecordQuality.estimatedMbPerMinute(4_000, withMic = false))
    }

    @Test
    fun `저장된 값이 프리셋과 같으면 그 프리셋으로 인식한다`() {
        assertEquals(
            RecordQuality.ECONOMY,
            RecordQuality.matchOf(720, 24, 1_500)
        )
        assertEquals(
            RecordQuality.NORMAL,
            RecordQuality.matchOf(1080, 30, 12_000)
        )
    }

    @Test
    fun `프리셋과 다르게 직접 고른 값은 직접 설정으로 남는다`() {
        assertNull(RecordQuality.matchOf(720, 30, 1_500))
        assertNull(RecordQuality.matchOf(1080, 24, 1_500))
        assertNull(RecordQuality.matchOf(720, 24, 4_000))
        // 인식되지 않아도 of() 는 기본 프리셋을 돌려줘 크래시 없이 동작한다
        assertEquals(RecordQuality.DEFAULT, RecordQuality.of(720, 24, 4_000))
    }

    @Test
    fun `화질을 낮추면 용량이 확실히 줄어든다`() {
        val normal = RecordQuality.NORMAL.estimatedMbPerMinute(withMic = true)
        val economy = RecordQuality.ECONOMY.estimatedMbPerMinute(withMic = true)
        assertTrue("절약 화질이 일반보다 작아야 한다", economy < normal)
        assertTrue("절약은 일반의 20% 미만", economy * 5 < normal)
    }
}
