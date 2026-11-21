package com.hunsu.note.view

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * 3.5 글자 도구의 인라인 입력기.
 *
 * 입력 팝업(AlertDialog)을 띄우지 않고, 캔버스에서 누른 지점(world 좌표) 위에
 * **투명 배경**의 [EditText] 를 그대로 얹는다. 그래서 소프트 키보드가 올라오는 순간부터
 * 치는 글자가 캔버스 그 자리에서 바로 보이고, 확정하면 그 자리에 글자 요소로 남는다.
 *
 * - 글자 크기·색은 캔버스에 그려지는 글자와 같은 값(현재 글자 크기·펜 색)을 쓴다.
 * - 입력 중에 굵기/크기 칩이나 색을 바꾸면 [setStyle] 로 즉시 반영된다.
 * - 바깥(캔버스·다른 도구)을 누르면 [commit] — 빈 문자열이면 아무것도 넣지 않는다.
 */
class InlineTextEditor(
    private val host: ViewGroup,
    private val canvas: InkCanvasView
) {

    /** 입력이 확정됐을 때 (world x, world y, 내용) */
    var onCommit: ((Float, Float, String) -> Unit)? = null

    var anchorX: Float = 0f
        private set
    var anchorY: Float = 0f
        private set

    private var editText: EditText? = null
    private var sizeWorld = 50f
    private var colorValue = Color.BLACK

    /** 화면 크기가 바뀐 뒤(키보드가 올라와 캔버스가 줄어든 뒤) 한 번 자리를 맞출지 */
    private var needsVisible = false

    val isActive: Boolean get() = editText != null

    val editor: EditText? get() = editText

    private val density: Float get() = host.resources.displayMetrics.density

    /** 캔버스 크기가 바뀌면(키보드가 올라와 줄어들면) 입력창 자리를 다시 맞춘다. */
    private val canvasLayoutWatcher = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (editText != null) {
            updateTransform()
            keepInView()
        }
    }

    /** 누른 지점에 투명 입력창을 띄우고 키보드를 올린다. */
    fun start(worldX: Float, worldY: Float, color: Int, textSizeWorld: Float) {
        discard()
        anchorX = worldX
        anchorY = worldY
        colorValue = color
        sizeWorld = textSizeWorld

        val edit = EditText(host.context).apply {
            // 배경을 없애 아래 캔버스(배경·PDF·필기)가 그대로 비쳐 보이게 한다.
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.TOP or Gravity.START
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            hint = null
            isFocusable = true
            isFocusableInTouchMode = true
            setTextColor(colorValue)
            minWidth = (24 * density).toInt()
            minHeight = (sizeWorld * canvas.scale).toInt().coerceAtLeast((14 * density).toInt())
        }
        host.addView(
            edit,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        editText = edit
        applyStyle()
        needsVisible = true
        canvas.addOnLayoutChangeListener(canvasLayoutWatcher)
        // 포스트잇 등 다른 입력창으로 포커스가 넘어가도 입력하던 글자는 잃지 않고 확정한다.
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && editText === edit) {
                edit.post { if (editText === edit) commit() }
            }
        }
        edit.requestFocus()
        edit.post {
            if (editText !== edit) return@post
            updateTransform()
            showKeyboard(edit)
            keepInView()
        }
        // 키보드가 다 올라와 캔버스 높이가 줄어든 뒤 한 번 더 자리를 맞춘다.
        edit.postDelayed({
            if (editText === edit) keepInView()
        }, 320L)
    }

    /** 입력 중 글자 크기·색을 바꾸면 그 자리에서 바로 반영한다. */
    fun setStyle(color: Int, textSizeWorld: Float) {
        if (editText == null) return
        colorValue = color
        sizeWorld = textSizeWorld
        applyStyle()
    }

    /** 확대·이동·화면 크기 변화에 맞춰 입력창을 캔버스의 같은 지점에 둔다. */
    fun updateTransform() {
        val edit = editText ?: return
        val scale = canvas.scale.coerceAtLeast(0.0001f)
        // 화면 배율이 바뀌면 글자도 캔버스에 그려질 크기 그대로 따라간다.
        val px = (sizeWorld * scale).coerceAtLeast(1f)
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        edit.minHeight = px.toInt().coerceAtLeast((14 * density).toInt())
        edit.x = canvas.offsetX + anchorX * scale
        // 캔버스는 기준선을 (y + 크기*0.92) 에 그리므로, 입력창의 첫 줄 기준선을 같은 자리에 둔다.
        edit.y = canvas.offsetY +
            (anchorY + sizeWorld * PagePainter.TEXT_BASELINE_FACTOR) * scale +
            edit.paint.fontMetrics.ascent
        val room = host.width - edit.x - 8f * density
        edit.maxWidth = room.toInt().coerceAtLeast((60f * density).toInt())
        if (needsVisible && keepInView()) needsVisible = false
    }

    /** 내용이 있으면 확정하고, 비어 있으면 그냥 닫는다. */
    fun commit() {
        val edit = editText ?: return
        val value = edit.text?.toString()?.trim().orEmpty()
        val x = anchorX
        val y = anchorY
        dismiss()
        if (value.isNotEmpty()) onCommit?.invoke(x, y, value)
    }

    /** 입력을 버리고 닫는다(확정 콜백 없음). */
    fun discard() {
        if (editText != null) dismiss()
    }

    private fun dismiss() {
        val edit = editText ?: return
        editText = null
        needsVisible = false
        canvas.removeOnLayoutChangeListener(canvasLayoutWatcher)
        hideKeyboard(edit)
        edit.clearFocus()
        host.removeView(edit)
    }

    private fun applyStyle() {
        val edit = editText ?: return
        edit.setTextColor(colorValue)
        updateTransform()
    }

    /**
     * 입력창이 캔버스 아래로 넘치면(키보드에 가리면) 캔버스를 위로 밀어 보이게 한다.
     * 지점 자체는 world 좌표로 고정되어 있으므로 확정 위치는 움직이지 않는다.
     * @return 아직 크기가 잡히지 않아 판단하지 못했으면 false
     */
    private fun keepInView(): Boolean {
        val edit = editText ?: return false
        if (edit.height <= 0 || host.height <= 0) return false
        val limit = host.height - 8f * density
        val overflow = edit.y + edit.height - limit
        if (overflow > 0f) canvas.panBy(0f, -overflow)
        return true
    }

    private fun showKeyboard(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
