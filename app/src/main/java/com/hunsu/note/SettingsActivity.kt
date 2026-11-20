package com.hunsu.note

import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hunsu.note.databinding.ActivitySettingsBinding
import com.hunsu.note.rec.RecordQuality

/**
 * 녹화 / 판서 / 화면 설정 화면. 값은 즉시 SharedPreferences 에 저장된다.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var selectedColor = Prefs.defaultPenColor

    /** 프리셋 라디오를 코드로 맞추는 중에는 리스너를 건너뛴다 */
    private var syncingRecordUi = false

    companion object {
        private val PALETTE = intArrayOf(
            0xFF111111.toInt(),
            0xFFE53935.toInt(),
            0xFF1E88E5.toInt(),
            0xFF43A047.toInt(),
            0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFFFD54F.toInt()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadValues()
        wireListeners()

        binding.btnClose.setOnClickListener { finish() }
    }

    private fun loadValues() {
        syncingRecordUi = true
        binding.rb720.isChecked = Prefs.recordHeight <= 720
        binding.rb1080.isChecked = Prefs.recordHeight > 720

        binding.rbFps24.isChecked = Prefs.recordFps <= 24
        binding.rbFps30.isChecked = Prefs.recordFps > 24

        // 3.1 에서 고른 값(예: 6Mbps)은 선택지에 없을 수 있다. 그때는 억지로 맞추지 않고 비워 둔다.
        when (Prefs.recordBitrateKbps) {
            1_500 -> binding.rbBitrate15.isChecked = true
            4_000 -> binding.rbBitrate4.isChecked = true
            8_000 -> binding.rbBitrate8.isChecked = true
            12_000 -> binding.rbBitrate12.isChecked = true
            else -> binding.rgBitrate.clearCheck()
        }

        // 둘은 늘 하나만 골라진 상태로 둔다(코드로 바꿀 때 RadioGroup 이 알아서 풀지 않는다).
        val quality = Prefs.recordQuality
        binding.rbQualityNormal.isChecked = quality == RecordQuality.NORMAL
        binding.rbQualityEconomy.isChecked = quality == RecordQuality.ECONOMY
        syncingRecordUi = false

        updateQualityText()
        binding.swMic.isChecked = Prefs.recordMic

        binding.seekPenWidth.max = 95
        binding.seekPenWidth.progress = penProgressOf(Prefs.penWidth)
        updateWidthLabel()

        binding.seekHighlighterWidth.max = 56
        binding.seekHighlighterWidth.progress =
            (Prefs.highlighterWidth.toInt() - 4).coerceIn(0, 56)
        updateHighlighterLabel()

        binding.swPressure.isChecked = Prefs.pressureEnabled

        binding.rbPenOnly.isChecked = Prefs.palmMode == Prefs.PALM_PEN_ONLY
        binding.rbFinger.isChecked = Prefs.palmMode != Prefs.PALM_PEN_ONLY

        binding.swStartFullscreen.isChecked = Prefs.startFullscreen
        binding.swAutoHideToolbar.isChecked = Prefs.autoHideToolbar

        binding.rgNoteSize.check(
            when (Prefs.noteLayout) {
                1 -> R.id.rbNoteFhd
                2 -> R.id.rbNote43
                3 -> R.id.rbNoteA4
                else -> R.id.rbNoteWide
            }
        )
        binding.rgNoteAlign.check(
            if (Prefs.noteAlign == 1) R.id.rbAlignCenter else R.id.rbAlignLeft
        )
        binding.seekNoteMargin.progress =
            kotlin.math.round(Prefs.noteMargin * 100f).toInt().coerceIn(0, 12)
        updateNoteMarginLabel()

        selectedColor = Prefs.penColor
        refreshColors()
    }

    private fun wireListeners() {
        binding.rgQuality.setOnCheckedChangeListener { _, checkedId ->
            if (syncingRecordUi) return@setOnCheckedChangeListener
            val quality = when (checkedId) {
                binding.rbQualityNormal.id -> RecordQuality.NORMAL
                binding.rbQualityEconomy.id -> RecordQuality.ECONOMY
                else -> null
            } ?: return@setOnCheckedChangeListener
            Prefs.applyRecordQuality(quality)
            loadValues()
        }

        binding.rgResolution.setOnCheckedChangeListener { _, checkedId ->
            if (syncingRecordUi) return@setOnCheckedChangeListener
            Prefs.recordHeight = if (checkedId == binding.rb720.id) 720 else 1080
            refreshRecordQuality()
        }
        binding.rgFps.setOnCheckedChangeListener { _, checkedId ->
            if (syncingRecordUi) return@setOnCheckedChangeListener
            Prefs.recordFps = if (checkedId == binding.rbFps24.id) 24 else 30
            refreshRecordQuality()
        }
        binding.rgBitrate.setOnCheckedChangeListener { _, checkedId ->
            if (syncingRecordUi) return@setOnCheckedChangeListener
            Prefs.recordBitrateKbps = when (checkedId) {
                binding.rbBitrate15.id -> 1_500
                binding.rbBitrate4.id -> 4_000
                binding.rbBitrate8.id -> 8_000
                else -> 12_000
            }
            refreshRecordQuality()
        }
        binding.swMic.setOnCheckedChangeListener { _, checked ->
            Prefs.recordMic = checked
            refreshRecordQuality()
        }

        binding.rgPalm.setOnCheckedChangeListener { _, checkedId ->
            Prefs.palmMode = if (checkedId == binding.rbPenOnly.id) {
                Prefs.PALM_PEN_ONLY
            } else {
                Prefs.PALM_FINGER
            }
        }

        binding.seekPenWidth.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 0.5 ~ 48 을 0.5 단위로
                Prefs.penWidth = (progress + 1) / 2f
                updateWidthLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.seekHighlighterWidth.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.highlighterWidth = (progress + 4).toFloat()
                updateHighlighterLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.swPressure.setOnCheckedChangeListener { _, checked ->
            Prefs.pressureEnabled = checked
        }

        binding.swStartFullscreen.setOnCheckedChangeListener { _, checked ->
            Prefs.startFullscreen = checked
        }
        binding.swAutoHideToolbar.setOnCheckedChangeListener { _, checked ->
            Prefs.autoHideToolbar = checked
        }

        binding.rgNoteSize.setOnCheckedChangeListener { _, checkedId ->
            Prefs.noteLayout = when (checkedId) {
                R.id.rbNoteFhd -> 1
                R.id.rbNote43 -> 2
                R.id.rbNoteA4 -> 3
                else -> 0
            }
        }
        binding.rgNoteAlign.setOnCheckedChangeListener { _, checkedId ->
            Prefs.noteAlign = if (checkedId == R.id.rbAlignCenter) 1 else 0
        }
        binding.seekNoteMargin.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.noteMargin = progress / 100f
                updateNoteMarginLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnReset.setOnClickListener { confirmReset() }
    }

    private fun updateNoteMarginLabel() {
        binding.tvNoteMargin.text =
            getString(R.string.note_margin) + " : " + binding.seekNoteMargin.progress + "%"
    }

    /** 화질 프리셋 라디오·설명을 지금 저장된 값에 맞춘다. */
    private fun refreshRecordQuality() {
        syncingRecordUi = true
        // 둘은 늘 하나만 골라진 상태로 둔다(코드로 바꿀 때 RadioGroup 이 알아서 풀지 않는다).
        val quality = Prefs.recordQuality
        binding.rbQualityNormal.isChecked = quality == RecordQuality.NORMAL
        binding.rbQualityEconomy.isChecked = quality == RecordQuality.ECONOMY
        syncingRecordUi = false
        updateQualityText()
    }

    private fun updateQualityText() {
        val quality = Prefs.recordQuality
        val withMic = Prefs.recordMic
        val mb = if (quality != null) {
            quality.estimatedMbPerMinute(withMic)
        } else {
            RecordQuality.estimatedMbPerMinute(Prefs.recordBitrateKbps, withMic)
        }
        binding.tvQualityDesc.text = if (quality != null) {
            var desc = getString(
                R.string.record_quality_desc,
                "${quality.height}p",
                quality.fps,
                formatMbps(quality.bitrateKbps),
                mb
            )
            if (quality == RecordQuality.ECONOMY) {
                desc += "\n" + getString(R.string.record_quality_hint_economy)
            }
            desc
        } else {
            getString(R.string.record_quality_custom, mb)
        }
    }

    /** 1500 → "1.5", 12000 → "12" */
    private fun formatMbps(kbps: Int): String =
        if (kbps % 1000 == 0) (kbps / 1000).toString()
        else String.format(java.util.Locale.US, "%.1f", kbps / 1000f)

    private fun penProgressOf(width: Float): Int =
        ((width * 2f).toInt() - 1).coerceIn(0, 95)

    private fun updateWidthLabel() {
        binding.tvPenWidthValue.text =
            getString(R.string.pen_default_width) + " : " + formatWidth(Prefs.penWidth)
    }

    private fun updateHighlighterLabel() {
        binding.tvHighlighterWidthValue.text =
            getString(R.string.highlighter_width) + " : " + formatWidth(Prefs.highlighterWidth)
    }

    private fun formatWidth(width: Float): String =
        if (width == width.toInt().toFloat()) width.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", width)

    private fun refreshColors() {
        val container: LinearLayout = binding.colorRow
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (32 * density).toInt()
        val margin = (3 * density).toInt()

        for (color in PALETTE) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(margin, margin, margin, margin)
            dot.layoutParams = lp
            val selected = color == selectedColor
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    if (selected) (2.5f * density).toInt() else (1f * density).toInt(),
                    if (selected) ContextCompat.getColor(this@SettingsActivity, R.color.brand)
                    else 0x66FFFFFF
                )
            }
            dot.background = drawable
            dot.contentDescription = "기본 펜 색"
            dot.setOnClickListener {
                selectedColor = color
                Prefs.penColor = color
                refreshColors()
            }
            container.addView(dot)
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.reset_confirm)
            .setNegativeButton(R.string.discard_cancel, null)
            .setPositiveButton(R.string.reset_all) { _, _ ->
                Prefs.reset()
                loadValues()
                Toast.makeText(this, R.string.reset_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
