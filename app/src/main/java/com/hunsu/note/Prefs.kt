package com.hunsu.note

import android.graphics.Color
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.NoteLayout

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
    private const val KEY_PALM_MODE = "palm_mode"
    private const val KEY_REC_HEIGHT = "rec_height"
    private const val KEY_REC_FPS = "rec_fps"
    private const val KEY_REC_BITRATE = "rec_bitrate"
    private const val KEY_REC_MIC = "rec_mic"
    private const val KEY_START_FULLSCREEN = "start_fullscreen"
    private const val KEY_AUTO_HIDE_TOOLBAR = "auto_hide_toolbar"
    private const val KEY_NOTE_LAYOUT = "note_layout"
    private const val KEY_NOTE_ALIGN = "note_align"
    private const val KEY_NOTE_MARGIN = "note_margin"

    /** 노트 여백 기본값 (노트 크기의 2%) */
    private const val DEFAULT_NOTE_MARGIN = 0.02f

    private fun p() = HunsuApp.prefs()

    var penWidth: Float
        get() = p().getFloat(KEY_PEN_WIDTH, 6f).coerceIn(1f, 40f)
        set(value) = p().edit().putFloat(KEY_PEN_WIDTH, value).apply()

    var penColor: Int
        get() = p().getInt(KEY_PEN_COLOR, 0xFF111111.toInt())
        set(value) = p().edit().putInt(KEY_PEN_COLOR, value).apply()

    var palmMode: Int
        get() = p().getInt(KEY_PALM_MODE, PALM_FINGER)
        set(value) = p().edit().putInt(KEY_PALM_MODE, value).apply()

    var recordHeight: Int
        get() = p().getInt(KEY_REC_HEIGHT, 1080)
        set(value) = p().edit().putInt(KEY_REC_HEIGHT, value).apply()

    var recordFps: Int
        get() = p().getInt(KEY_REC_FPS, 30)
        set(value) = p().edit().putInt(KEY_REC_FPS, value).apply()

    var recordBitrateMbps: Int
        get() = p().getInt(KEY_REC_BITRATE, 12)
        set(value) = p().edit().putInt(KEY_REC_BITRATE, value).apply()

    var recordMic: Boolean
        get() = p().getBoolean(KEY_REC_MIC, true)
        set(value) = p().edit().putBoolean(KEY_REC_MIC, value).apply()

    var startFullscreen: Boolean
        get() = p().getBoolean(KEY_START_FULLSCREEN, true)
        set(value) = p().edit().putBoolean(KEY_START_FULLSCREEN, value).apply()

    var autoHideToolbar: Boolean
        get() = p().getBoolean(KEY_AUTO_HIDE_TOOLBAR, true)
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
