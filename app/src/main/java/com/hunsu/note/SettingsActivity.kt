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

/**
 * 녹화 / 판서 / 화면 설정 화면. 값은 즉시 SharedPreferences 에 저장된다.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var selectedColor = Prefs.defaultPenColor

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
        binding.rb720.isChecked = Prefs.recordHeight <= 720
        binding.rb1080.isChecked = Prefs.recordHeight > 720

        binding.rbFps24.isChecked = Prefs.recordFps <= 24
        binding.rbFps30.isChecked = Prefs.recordFps > 24

        val bitrate = Prefs.recordBitrateMbps
        binding.rb4.isChecked = bitrate <= 4
        binding.rb6.isChecked = bitrate in 5..6
        binding.rb8.isChecked = bitrate in 7..8
        binding.rb12.isChecked = bitrate > 8

        binding.swMic.isChecked = Prefs.recordMic

        binding.seekPenWidth.progress = Prefs.penWidth.toInt().coerceIn(1, 40)
        updateWidthLabel()

        binding.rbPenOnly.isChecked = Prefs.palmMode == Prefs.PALM_PEN_ONLY
        binding.rbFinger.isChecked = Prefs.palmMode != Prefs.PALM_PEN_ONLY

        binding.swStartFullscreen.isChecked = Prefs.startFullscreen
        binding.swAutoHideToolbar.isChecked = Prefs.autoHideToolbar

        selectedColor = Prefs.penColor
        refreshColors()
    }

    private fun wireListeners() {
        binding.rgResolution.setOnCheckedChangeListener { _, checkedId ->
            Prefs.recordHeight = if (checkedId == binding.rb720.id) 720 else 1080
        }
        binding.rgFps.setOnCheckedChangeListener { _, checkedId ->
            Prefs.recordFps = if (checkedId == binding.rbFps24.id) 24 else 30
        }
        binding.rgBitrate.setOnCheckedChangeListener { _, checkedId ->
            Prefs.recordBitrateMbps = when (checkedId) {
                binding.rb4.id -> 4
                binding.rb6.id -> 6
                binding.rb8.id -> 8
                else -> 12
            }
        }
        binding.swMic.setOnCheckedChangeListener { _, checked ->
            Prefs.recordMic = checked
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
                Prefs.penWidth = progress.toFloat().coerceAtLeast(1f)
                updateWidthLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.swStartFullscreen.setOnCheckedChangeListener { _, checked ->
            Prefs.startFullscreen = checked
        }
        binding.swAutoHideToolbar.setOnCheckedChangeListener { _, checked ->
            Prefs.autoHideToolbar = checked
        }

        binding.btnReset.setOnClickListener { confirmReset() }
    }

    private fun updateWidthLabel() {
        binding.tvPenWidthValue.text =
            getString(R.string.pen_default_width) + " : " + binding.seekPenWidth.progress
    }

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
