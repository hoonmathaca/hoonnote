package com.hunsu.note

import android.graphics.Color
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.NoteLayout
import com.hunsu.note.model.StrokeWidths
import com.hunsu.note.rec.RecordQuality

/**
 * 사용자 설정 저장소. 모든 값은 SharedPreferences 에 즉시 반영된다.
 */
object Prefs {

    /** 팜리젝션: 스타일러스만 인식 */
    const val PALM_PEN_ONLY = 0

    /** 손가락도 판서 허용 (스타일러스 없는 기기에서 테스트용) */
    const val PALM_FINGER = 1

    private const val KEY_PEN_WIDTH = "pen_width"
    private const val KEY_PEN_COLOR = "pen_color"
    private const val KEY_HL_WIDTH = "highlighter_width"
    private const val KEY_HL_COLOR = "highlighter_color"
    private const val KEY_PRESSURE = "pressure_enabled"
    private const val KEY_PALM_MODE = "palm_mode"
    private const val KEY_REC_HEIGHT = "rec_height"
    private const val KEY_REC_FPS = "rec_fps"

    /** 3.1 이하: Mbps 정수. 3.2 부터는 [KEY_REC_BITRATE_KBPS] 로 옮겨 읽는다. */
    private const val KEY_REC_BITRATE = "rec_bitrate"
    private const val KEY_REC_BITRATE_KBPS = "rec_bitrate_kbps"
    private const val KEY_REC_MIC = "rec_mic"

    /** 비트레이트 안전 범위(kbps) */
    private const val MIN_BITRATE_KBPS = 300
    private const val MAX_BITRATE_KBPS = 40_000
    private const val KEY_START_FULLSCREEN = "start_fullscreen"
    private const val KEY_AUTO_HIDE_TOOLBAR = "auto_hide_toolbar"
    private const val KEY_NOTE_LAYOUT = "note_layout"
    private const val KEY_NOTE_ALIGN = "note_align"
    private const val KEY_NOTE_MARGIN = "note_margin"

    /** 노트 여백 기본값 (노트 크기의 2%) */
    private const val DEFAULT_NOTE_MARGIN = 0.02f

    /** 형광펜 기본색 (노랑) */
    const val DEFAULT_HIGHLIGHTER_COLOR = 0xFFFFEB3B.toInt()

    private fun p() = HunsuApp.prefs()

    var penWidth: Float
        get() = p().getFloat(KEY_PEN_WIDTH, 6f)
            .coerceIn(StrokeWidths.MIN_WIDTH, StrokeWidths.MAX_WIDTH)
        set(value) = p().edit().putFloat(
            KEY_PEN_WIDTH, value.coerceIn(StrokeWidths.MIN_WIDTH, StrokeWidths.MAX_WIDTH)
        ).apply()

    var penColor: Int
        get() = p().getInt(KEY_PEN_COLOR, 0xFF111111.toInt())
        set(value) = p().edit().putInt(KEY_PEN_COLOR, value).apply()

    /** 형광펜 굵기 (펜과 따로 기억한다) */
    var highlighterWidth: Float
        get() = p().getFloat(KEY_HL_WIDTH, 20f).coerceIn(4f, 60f)
        set(value) = p().edit().putFloat(KEY_HL_WIDTH, value.coerceIn(4f, 60f)).apply()

    /** 형광펜 색 (펜과 따로 기억한다) */
    var highlighterColor: Int
        get() = p().getInt(KEY_HL_COLOR, DEFAULT_HIGHLIGHTER_COLOR)
        set(value) = p().edit().putInt(KEY_HL_COLOR, value).apply()

    /** 필압 감지 (스타일러스 압력으로 선 굵기 변화) */
    var pressureEnabled: Boolean
        get() = p().getBoolean(KEY_PRESSURE, true)
        set(value) = p().edit().putBoolean(KEY_PRESSURE, value).apply()

    var palmMode: Int
        get() = p().getInt(KEY_PALM_MODE, PALM_FINGER)
        set(value) = p().edit().putInt(KEY_PALM_MODE, value).apply()

    var recordHeight: Int
        get() = p().getInt(KEY_REC_HEIGHT, RecordQuality.DEFAULT.height)
        set(value) = p().edit().putInt(KEY_REC_HEIGHT, value).apply()

    var recordFps: Int
        get() = p().getInt(KEY_REC_FPS, RecordQuality.DEFAULT.fps)
        set(value) = p().edit().putInt(KEY_REC_FPS, value).apply()

    /** 영상 비트레이트(kbps). 3.1 의 Mbps 정수 값은 처음 읽을 때 한 번만 환산해 옮긴다. */
    var recordBitrateKbps: Int
        get() {
            val prefs = p()
            if (!prefs.contains(KEY_REC_BITRATE_KBPS)) {
                val oldMbps = prefs.getInt(KEY_REC_BITRATE, -1)
                if (oldMbps > 0) {
                    val migrated = (oldMbps * 1000).coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)
                    prefs.edit().putInt(KEY_REC_BITRATE_KBPS, migrated).apply()
                    return migrated
                }
            }
            return prefs.getInt(KEY_REC_BITRATE_KBPS, RecordQuality.DEFAULT.bitrateKbps)
                .coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS)
        }
        set(value) = p().edit()
            .putInt(KEY_REC_BITRATE_KBPS, value.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS))
            .apply()

    /** 지금 해상도·프레임·비트레이트가 어느 화질 프리셋과 같은지(직접 고른 값이면 null) */
    val recordQuality: RecordQuality?
        get() = RecordQuality.matchOf(recordHeight, recordFps, recordBitrateKbps)

    /** 화질 프리셋 하나를 해상도·프레임·비트레이트에 한 번에 적용한다. */
    fun applyRecordQuality(quality: RecordQuality) {
        p().edit()
            .putInt(KEY_REC_HEIGHT, quality.height)
            .putInt(KEY_REC_FPS, quality.fps)
            .putInt(KEY_REC_BITRATE_KBPS, quality.bitrateKbps)
            .apply()
    }

    var recordMic: Boolean
        get() = p().getBoolean(KEY_REC_MIC, true)
        set(value) = p().edit().putBoolean(KEY_REC_MIC, value).apply()

    /** 시작할 때 전체화면 */
    var startFullscreen: Boolean
        get() = p().getBoolean(KEY_START_FULLSCREEN, true)
        set(value) = p().edit().putBoolean(KEY_START_FULLSCREEN, value).apply()

    /** 녹화 시작 시 도구 막대 자동 숨김 (기본은 꺼짐 — 녹화해도 화면이 바뀌지 않는다) */
    var autoHideToolbar: Boolean
        get() = p().getBoolean(KEY_AUTO_HIDE_TOOLBAR, false)
        set(value) = p().edit().putBoolean(KEY_AUTO_HIDE_TOOLBAR, value).apply()

    /** 노트 캔버스 크기 (NoteLayout 순서) */
    var noteLayout: Int
        get() = p().getInt(KEY_NOTE_LAYOUT, NoteLayout.DEFAULT_INDEX)
        set(value) = p().edit().putInt(KEY_NOTE_LAYOUT, value).apply()

    /** PDF/이미지 정렬 (NoteAlign 순서: 0=좌측, 1=가운데) */
    var noteAlign: Int
        get() = p().getInt(KEY_NOTE_ALIGN, 0)
        set(value) = p().edit().putInt(KEY_NOTE_ALIGN, value).apply()

    /** 노트 여백 비율 (0 ~ 0.1) */
    var noteMargin: Float
        get() = p().getFloat(KEY_NOTE_MARGIN, DEFAULT_NOTE_MARGIN).coerceIn(0f, 0.12f)
        set(value) = p().edit().putFloat(KEY_NOTE_MARGIN, value).apply()

    fun noteLayoutEnum(): NoteLayout = NoteLayout.of(noteLayout)

    fun noteAlignEnum(): NoteAlign = NoteAlign.of(noteAlign)

    /** 모든 설정 초기화 */
    fun reset() {
        p().edit().clear().apply()
    }

    val defaultPenColor: Int = 0xFF111111.toInt()
    val defaultPenWidth: Float = 6f
    val defaultPalmMode: Int = PALM_FINGER
    val defaultColors: IntArray = intArrayOf(Color.BLACK, Color.WHITE)
}
