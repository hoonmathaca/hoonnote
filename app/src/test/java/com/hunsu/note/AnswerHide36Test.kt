package com.hunsu.note

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.hunsu.note.model.NoteAlign
import com.hunsu.note.model.Page
import com.hunsu.note.view.InkCanvasView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 3.6 '답안 가리기' 버튼: 상단 바에서 켜고 끄면 배경의 하늘색 답안만 지운 사본이
 * 화면에 올라가고, 끄면 원본으로 돌아온다(원본·캐시 파일은 그대로).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnswerHide36Test {

    private fun activity(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun pageWithContent(): Page {
        val page = Page(baseAspect = 1.6f)
        page.applyContentLayout(NoteAlign.LEFT, 0.02f)
        return page
    }

    /** 하늘색 답안 한 줄 + 검은 본문이 섞인 교사용 페이지 배경 */
    private fun teacherBackground(): Bitmap {
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(
            4f, 6f, 56f, 12f, android.graphics.Paint().apply { color = Color.rgb(36, 162, 200) }
        )
        canvas.drawRect(
            4f, 20f, 56f, 26f, android.graphics.Paint().apply { color = Color.rgb(20, 20, 20) }
        )
        return bitmap
    }

    @Test
    fun `상단 바의 답안 가리기 버튼을 누르면 배경의 하늘색 답안이 지워진다`() {
        val activity = activity()
        assertNotNull("상단 바에 답안 가리기 버튼이 없습니다", activity.findViewById<View>(R.id.btnAnswerHide))
        assertFalse(activity.answerHiddenForTest())

        val page = pageWithContent()
        val background = teacherBackground()
        val button = activity.findViewById<View>(R.id.btnAnswerHide)

        button.performClick()

        assertTrue("버튼을 눌렀는데 켜지지 않았습니다", activity.answerHiddenForTest())
        val shown = activity.displayBackgroundForTest(page, background)
        assertNotNull(shown)
        assertEquals("하늘색 답안이 지워지지 않았습니다", Color.WHITE, shown!!.getPixel(30, 9))
        assertEquals("검은 본문이 지워졌습니다", Color.rgb(20, 20, 20), shown.getPixel(30, 23))
        // 원본은 그대로 — 다시 끄면 답안이 돌아온다.
        assertEquals("원본 배경이 바뀌었습니다", Color.rgb(36, 162, 200), background.getPixel(30, 9))

        button.performClick()
        assertFalse(activity.answerHiddenForTest())
        assertSame("끄면 원본 배경이 그대로 올라와야 합니다", background, activity.displayBackgroundForTest(page, background))
    }

    @Test
    fun `답안 가리기 상태는 앱을 다시 열어도 남는다`() {
        val first = activity()
        first.findViewById<View>(R.id.btnAnswerHide).performClick()
        assertTrue(first.answerHiddenForTest())

        val second = activity()
        assertTrue("앱을 다시 열었더니 답안 가리기가 꺼졌습니다", second.answerHiddenForTest())
    }

    @Test
    fun `답안 가리기를 켜면 지금 페이지 캔버스에도 사본이 올라간다`() {
        val activity = activity()
        val canvas = activity.findViewById<InkCanvasView>(R.id.canvasView)
        val page = canvas.page!!
        val background = teacherBackground()
        // 지금 페이지의 원본 배경을 캐시 메모리에 넣어 둔다(실제 앱이 배경을 올려 두는 자리).
        activity.backgroundCacheForTest().putMemory(page.backgroundKey, background)
        canvas.setPage(page, background)

        activity.findViewById<View>(R.id.btnAnswerHide).performClick()

        assertTrue(activity.answerHiddenForTest())
        val shown = canvas.backgroundBitmap
        assertNotNull("캔버스에 배경이 올라오지 않았습니다", shown)
        assertEquals("캔버스 배경에 답안이 남아 있습니다", Color.WHITE, shown!!.getPixel(30, 9))
        assertEquals("캔버스 배경의 본문이 지워졌습니다", Color.rgb(20, 20, 20), shown.getPixel(30, 23))
    }

    @Test
    fun `답안 가리기는 기본적으로 꺼져 있다`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.reset()
        assertFalse(Prefs.answerHidden)
        assertNotNull(context)
    }
}
