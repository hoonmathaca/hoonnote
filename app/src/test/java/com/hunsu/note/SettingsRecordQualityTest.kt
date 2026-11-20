package com.hunsu.note

import android.os.Build
import android.widget.RadioButton
import android.widget.TextView
import com.hunsu.note.rec.RecordQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 설정 화면의 '녹화 화질(용량)' 프리셋이 해상도·프레임·비트레이트에 반영되는지 본다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
class SettingsRecordQualityTest {

    @Before
    fun resetPrefs() {
        Prefs.reset()
    }

    @After
    fun clearPrefs() {
        Prefs.reset()
    }

    @Test
    fun `처음 열면 절약 화질이 기본으로 골라져 있다`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        assertTrue(activity.findViewById<RadioButton>(R.id.rbQualityEconomy).isChecked)
        assertFalse(activity.findViewById<RadioButton>(R.id.rbQualityNormal).isChecked)
        assertEquals(RecordQuality.ECONOMY, Prefs.recordQuality)

        val desc = activity.findViewById<TextView>(R.id.tvQualityDesc).text.toString()
        assertTrue("용량 안내가 보여야 한다: $desc", desc.contains("약 12MB"))
        assertTrue("화질 값이 보여야 한다: $desc", desc.contains("720p"))
    }

    @Test
    fun `일반 고화질을 고르면 해상도와 비트레이트가 같이 바뀐다`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.findViewById<RadioButton>(R.id.rbQualityNormal).performClick()

        assertEquals(RecordQuality.NORMAL, Prefs.recordQuality)
        assertEquals(1080, Prefs.recordHeight)
        assertEquals(30, Prefs.recordFps)
        assertEquals(12_000, Prefs.recordBitrateKbps)

        // 세부 설정 라디오도 같은 값을 가리킨다.
        assertTrue(activity.findViewById<RadioButton>(R.id.rb1080).isChecked)
        assertTrue(activity.findViewById<RadioButton>(R.id.rbFps30).isChecked)
        assertTrue(activity.findViewById<RadioButton>(R.id.rbBitrate12).isChecked)

        // 프리셋은 한쪽만 골라진 상태여야 한다.
        assertFalse(activity.findViewById<RadioButton>(R.id.rbQualityEconomy).isChecked)
        assertEquals(
            R.id.rbQualityNormal,
            activity.findViewById<android.widget.RadioGroup>(R.id.rgQuality).checkedRadioButtonId
        )

        val desc = activity.findViewById<TextView>(R.id.tvQualityDesc).text.toString()
        assertTrue("일반 화질 용량 안내: $desc", desc.contains("1080p") && desc.contains("약 89MB"))
    }

    @Test
    fun `절약으로 돌아오면 720p 저용량 값으로 저장된다`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.findViewById<RadioButton>(R.id.rbQualityNormal).performClick()
        activity.findViewById<RadioButton>(R.id.rbQualityEconomy).performClick()

        assertEquals(RecordQuality.ECONOMY, Prefs.recordQuality)
        assertEquals(720, Prefs.recordHeight)
        assertEquals(24, Prefs.recordFps)
        assertEquals(1_500, Prefs.recordBitrateKbps)
        assertTrue(activity.findViewById<RadioButton>(R.id.rbBitrate15).isChecked)
    }

    @Test
    fun `세부 값을 직접 바꾸면 프리셋 선택이 풀린다`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        // 절약(720p/24fps/1.5Mbps) 상태에서 비트레이트만 4Mbps 로 올린다
        activity.findViewById<RadioButton>(R.id.rbBitrate4).performClick()

        assertEquals(4_000, Prefs.recordBitrateKbps)
        assertNull(Prefs.recordQuality)
        assertFalse(activity.findViewById<RadioButton>(R.id.rbQualityEconomy).isChecked)
        assertFalse(activity.findViewById<RadioButton>(R.id.rbQualityNormal).isChecked)

        val desc = activity.findViewById<TextView>(R.id.tvQualityDesc).text.toString()
        assertTrue("직접 설정 안내: $desc", desc.contains("직접 설정"))
        assertTrue("직접 설정 용량: $desc", desc.contains("약 31MB"))
    }
}
