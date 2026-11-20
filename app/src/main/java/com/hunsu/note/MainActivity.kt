package com.hunsu.note

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hunsu.note.databinding.ActivityMainBinding
import com.hunsu.note.io.PageExporter
import com.hunsu.note.io.PdfPageProvider
import com.hunsu.note.model.BackgroundStyle
import com.hunsu.note.model.Lecture
import com.hunsu.note.model.Page
import com.hunsu.note.model.StickyNote
import com.hunsu.note.model.Tool
import com.hunsu.note.rec.RecSettings
import com.hunsu.note.rec.ScreenRecordService
import java.util.concurrent.Executors

/**
 * 훈수노트 메인 화면: 판서 캔버스 + 도구 막대 + 페이지 관리 + 녹화.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val lecture = Lecture()
    private var pdfProvider: PdfPageProvider? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val noteViews = HashMap<StickyNote, View>()

    private var penColor = Prefs.defaultPenColor
    private var penWidth = Prefs.defaultPenWidth
    private var penTouchedByUser = false
    private var immersive = false
    private var pendingMic = true

    companion object {
        private const val MENU_PNG = 1
        private const val MENU_PDF = 2
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

    // ---------------------------------------------------------------- 생명주기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        penColor = Prefs.penColor
        penWidth = Prefs.penWidth

        setupCanvas()
        setupToolbar()
        setupColors()

        startBlankLecture(BackgroundStyle.WHITE)

        immersive = Prefs.startFullscreen
        applyImmersive()
        binding.toolbarContainer.post { updateBoardInsets() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenRecordService.ACTION_STATE)
        ContextCompat.registerReceiver(
            this, recordReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateRecordUi(ScreenRecordService.isRecording)
    }

    override fun onStop() {
        try {
            unregisterReceiver(recordReceiver)
        } catch (e: Exception) {
            // 이미 해제된 경우
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        binding.canvasView.fingerDrawingAllowed = Prefs.palmMode == Prefs.PALM_FINGER
        if (!penTouchedByUser) {
            penColor = Prefs.penColor
            penWidth = Prefs.penWidth
            binding.canvasView.penColor = penColor
            binding.canvasView.penWidth = penWidth
            binding.widthSeek.progress = penWidth.toInt().coerceIn(1, 40)
            refreshColorDots()
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        closePdf()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 초기 설정

    private fun setupCanvas() {
        binding.canvasView.apply {
            penColor = this@MainActivity.penColor
            penWidth = this@MainActivity.penWidth
            fingerDrawingAllowed = Prefs.palmMode == Prefs.PALM_FINGER
            onPageEdited = { updateUndoRedo() }
            onTransformChanged = { layoutNotes() }
        }
    }

    private fun setupToolbar() {
        binding.btnNewLecture.setOnClickListener { confirmNewLecture() }
        binding.btnOpenPdf.setOnClickListener { pdfPicker.launch(arrayOf("application/pdf")) }
        binding.btnOpenImage.setOnClickListener { imagePicker.launch(arrayOf("image/*")) }
        binding.btnAddPage.setOnClickListener { addPage() }
        binding.btnPrevPage.setOnClickListener { goToPage(lecture.index - 1) }
        binding.btnNextPage.setOnClickListener { goToPage(lecture.index + 1) }
        binding.btnPrevCompact.setOnClickListener { goToPage(lecture.index - 1) }
        binding.btnNextCompact.setOnClickListener { goToPage(lecture.index + 1) }
        binding.btnSave.setOnClickListener { showSaveMenu(it) }
        binding.btnRecord.setOnClickListener { onRecordClicked() }
        binding.btnRecordCompact.setOnClickListener { onRecordClicked() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnFullscreen.setOnClickListener {
            immersive = !immersive
            applyImmersive()
        }
        binding.btnCollapse.setOnClickListener { collapseToolbar() }
        binding.btnExpand.setOnClickListener { expandToolbar() }

        binding.btnPen.setOnClickListener { selectTool(Tool.PEN) }
        binding.btnHighlighter.setOnClickListener { selectTool(Tool.HIGHLIGHTER) }
        binding.btnEraser.setOnClickListener { selectTool(Tool.ERASER) }
        binding.btnLine.setOnClickListener { selectTool(Tool.LINE) }
        binding.btnArrow.setOnClickListener { selectTool(Tool.ARROW) }
        binding.btnRect.setOnClickListener { selectTool(Tool.RECT) }
        binding.btnOval.setOnClickListener { selectTool(Tool.OVAL) }
        binding.btnNote.setOnClickListener { addStickyNote() }
        binding.btnUndo.setOnClickListener {
            if (binding.canvasView.undo()) updateUndoRedo()
        }
        binding.btnRedo.setOnClickListener {
            if (binding.canvasView.redo()) updateUndoRedo()
        }

        binding.widthSeek.progress = penWidth.toInt().coerceIn(1, 40)
        binding.widthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                penWidth = progress.toFloat().coerceAtLeast(1f)
                binding.canvasView.penWidth = penWidth
                if (fromUser) {
                    penTouchedByUser = true
                    Prefs.penWidth = penWidth
                }
                binding.canvasView.invalidate()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        selectTool(Tool.PEN)
    }

    private fun setupColors() {
        refreshColorDots()
    }

    private fun refreshColorDots() {
        val container: LinearLayout = binding.colorRow
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (30 * density).toInt()
        val margin = (3 * density).toInt()

        for (color in PALETTE) {
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(margin, margin, margin, margin)
            dot.layoutParams = lp
            val selected = color == penColor
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    if (selected) (2.5f * density).toInt() else (1f * density).toInt(),
                    if (selected) ContextCompat.getColor(this@MainActivity, R.color.brand)
                    else 0x66FFFFFF
                )
            }
            dot.background = drawable
            dot.contentDescription = "펜 색상"
            dot.setOnClickListener {
                penColor = color
                penTouchedByUser = true
                binding.canvasView.penColor = color
                Prefs.penColor = color
                refreshColorDots()
            }
            container.addView(dot)
        }
    }

    private fun selectTool(tool: Tool) {
        binding.canvasView.tool = tool
        val buttons = mapOf<Tool, MaterialButton>(
            Tool.PEN to binding.btnPen,
            Tool.HIGHLIGHTER to binding.btnHighlighter,
            Tool.ERASER to binding.btnEraser,
            Tool.LINE to binding.btnLine,
            Tool.ARROW to binding.btnArrow,
            Tool.RECT to binding.btnRect,
            Tool.OVAL to binding.btnOval
        )
        val selectedColor = ContextCompat.getColor(this, R.color.tool_text_selected)
        val normalColor = ContextCompat.getColor(this, R.color.tool_text)
        for ((t, button) in buttons) {
            val isSelected = t == tool
            button.setTextColor(if (isSelected) selectedColor else normalColor)
            button.backgroundTintList =
                if (isSelected) ColorStateList.valueOf(0x33FFFFFF) else null
        }
    }

    // ---------------------------------------------------------------- 페이지

    private fun startBlankLecture(style: BackgroundStyle) {
        closePdf()
        lecture.pages.clear()
        lecture.pages.add(Page(1600f, 1000f, style))
        lecture.index = 0
        lecture.style = style
        showPage()
    }

    private fun confirmNewLecture() {
        val hasContent = lecture.pages.any { it.elements.isNotEmpty() || it.notes.isNotEmpty() }
        if (!hasContent) {
            showBackgroundDialog()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setNegativeButton(R.string.discard_cancel, null)
            .setPositiveButton(R.string.discard_ok) { _, _ -> showBackgroundDialog() }
            .show()
    }

    private fun showBackgroundDialog() {
        val labels = arrayOf(
            getString(R.string.bg_white),
            getString(R.string.bg_green),
            getString(R.string.bg_navy),
            getString(R.string.bg_grid)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_background)
            .setItems(labels) { _, which ->
                val style = when (which) {
                    1 -> BackgroundStyle.GREEN
                    2 -> BackgroundStyle.NAVY
                    3 -> BackgroundStyle.GRID
                    else -> BackgroundStyle.WHITE
                }
                startBlankLecture(style)
            }
            .show()
    }

    private fun showPage() {
        val page = lecture.currentPage ?: return
        binding.canvasView.setPage(page, page.renderedBackground)
        bindNotes(page)
        updatePageInfo()
        updateUndoRedo()
        loadBackground(page)
    }

    private fun goToPage(index: Int) {
        if (index !in lecture.pages.indices) return
        lecture.index = index
        showPage()
    }

    private fun addPage() {
        if (lecture.pages.isEmpty()) return
        val page = Page(1600f, 1000f, lecture.style)
        val at = (lecture.index + 1).coerceIn(0, lecture.pages.size)
        lecture.pages.add(at, page)
        lecture.index = at
        showPage()
    }

    private fun updatePageInfo() {
        val total = lecture.pages.size
        val current = if (total == 0) 0 else lecture.index + 1
        binding.tvPageInfo.text = getString(R.string.page_info, current, total)
        binding.tvPageInfoCompact.text = getString(R.string.page_info, current, total)
    }

    private fun updateUndoRedo() {
        val page = lecture.currentPage
        binding.btnUndo.isEnabled = page?.canUndo == true
        binding.btnRedo.isEnabled = page?.canRedo == true
        binding.btnUndo.alpha = if (binding.btnUndo.isEnabled) 1f else 0.35f
        binding.btnRedo.alpha = if (binding.btnRedo.isEnabled) 1f else 0.35f
    }

    private fun loadBackground(page: Page) {
        val image = page.image
        if (image != null) {
            page.renderedBackground = image
            binding.canvasView.setPageBackground(image)
            return
        }
        if (page.pdfPageIndex < 0) return
        val existing = page.renderedBackground
        if (existing != null) {
            binding.canvasView.setPageBackground(existing)
            return
        }
        val provider = pdfProvider ?: return
        val index = page.pdfPageIndex
        val targetWidth = page.worldWidth.toInt()
        io.execute {
            val bitmap = provider.render(index, targetWidth)
            ui.post {
                if (bitmap != null) {
                    page.renderedBackground = bitmap
                    if (lecture.currentPage === page) {
                        binding.canvasView.setPageBackground(bitmap)
                    }
                }
            }
        }
    }

    private fun closePdf() {
        try {
            pdfProvider?.close()
        } catch (e: Exception) {
            // 무시
        }
        pdfProvider = null
    }

    // ---------------------------------------------------------------- PDF / 이미지

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openPdf(uri)
    }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openImage(uri)
        }

    private fun openPdf(uri: Uri) {
        val provider = PdfPageProvider(this, uri)
        if (!provider.isOpen || provider.pageCount <= 0) {
            provider.close()
            toast(getString(R.string.pdf_open_failed))
            return
        }
        closePdf()
        pdfProvider = provider
        lecture.pages.clear()
        for (i in 0 until provider.pageCount) {
            val aspect = provider.aspectOf(i).coerceIn(0.3f, 3.5f)
            val width = 1600f
            val height = (width / aspect).coerceAtLeast(200f)
            lecture.pages.add(Page(width, height, BackgroundStyle.WHITE, i))
        }
        lecture.index = 0
        lecture.style = BackgroundStyle.WHITE
        showPage()
    }

    private fun openImage(uri: Uri) {
        val bitmap = decodeBitmap(uri)
        if (bitmap == null) {
            toast(getString(R.string.image_open_failed))
            return
        }
        closePdf()
        val aspect = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        val width = 1600f
        val height = width / aspect.coerceIn(0.2f, 5f)
        lecture.pages.clear()
        lecture.pages.add(Page(width, height, BackgroundStyle.WHITE, -1, bitmap))
        lecture.index = 0
        showPage()
    }

    private fun decodeBitmap(uri: Uri, maxSize: Int = 2400): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = maxOf(w, h).coerceAtLeast(1)
                    if (longest > maxSize) {
                        val scale = maxSize.toFloat() / longest
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // ---------------------------------------------------------------- 저장

    private fun showSaveMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_PNG, 0, getString(R.string.save_png))
        popup.menu.add(0, MENU_PDF, 1, getString(R.string.save_pdf))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PNG -> {
                    saveCurrentPagePng()
                    true
                }

                MENU_PDF -> {
                    exportAllPagesPdf()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun saveCurrentPagePng() {
        val page = lecture.currentPage ?: return
        val pageNumber = lecture.index + 1
        toast(getString(R.string.saving))
        io.execute {
            val bitmap = PageExporter.renderPage(page, backgroundForExport(page))
            val saved = PageExporter.savePng(
                this,
                bitmap,
                "HunsuNote_${PageExporter.timestamp()}_p$pageNumber"
            )
            bitmap.recycle()
            ui.post {
                if (saved != null) toast(getString(R.string.saved_to_gallery, saved))
                else toast(getString(R.string.save_failed))
            }
        }
    }

    private fun exportAllPagesPdf() {
        val pages = ArrayList(lecture.pages)
        if (pages.isEmpty()) return
        toast(getString(R.string.saving))
        io.execute {
            val saved = PageExporter.exportPdf(this, pages, { page -> backgroundForExport(page) })
            ui.post {
                if (saved != null) toast(getString(R.string.saved_to_downloads, saved))
                else toast(getString(R.string.save_failed))
            }
        }
    }

    private fun backgroundForExport(page: Page): Bitmap? {
        page.image?.let { return it }
        if (page.pdfPageIndex < 0) return null
        page.renderedBackground?.let { return it }
        val provider = pdfProvider ?: return null
        return provider.render(page.pdfPageIndex, page.worldWidth.toInt())
    }

    // ---------------------------------------------------------------- 포스트잇

    private fun addStickyNote() {
        val page = lecture.currentPage ?: return
        val cx = binding.canvasView.screenToWorldX(binding.stickyLayer.width / 2f)
        val cy = binding.canvasView.screenToWorldY(binding.stickyLayer.height / 2f)
        val note = StickyNote("", cx - 170f, cy - 140f, 340f, 280f)
        page.notes.add(note)
        addNoteView(page, note)
        toast(getString(R.string.sticky_added))
    }

    private fun bindNotes(page: Page) {
        binding.stickyLayer.removeAllViews()
        noteViews.clear()
        for (note in page.notes) addNoteView(page, note)
    }

    private fun addNoteView(page: Page, note: StickyNote) {
        val view = layoutInflater.inflate(R.layout.view_sticky_note, binding.stickyLayer, false)
        val edit = view.findViewById<EditText>(R.id.stickyText)
        edit.setText(note.text)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                Unit

            override fun afterTextChanged(s: Editable?) {
                note.text = s?.toString() ?: ""
            }
        })
        view.findViewById<View>(R.id.stickyDelete).setOnClickListener {
            page.notes.remove(note)
            noteViews.remove(note)
            binding.stickyLayer.removeView(view)
        }
        attachDrag(view.findViewById(R.id.stickyHeader), view, note)
        binding.stickyLayer.addView(view)
        noteViews[note] = view
        view.post { layoutNote(view, note) }
    }

    private fun attachDrag(handle: View, noteView: View, note: StickyNote) {
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0f
        var startY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = note.x
                    startY = note.y
                }

                MotionEvent.ACTION_MOVE -> {
                    val scale = binding.canvasView.scale.coerceAtLeast(0.0001f)
                    note.x = startX + (event.rawX - startRawX) / scale
                    note.y = startY + (event.rawY - startRawY) / scale
                    layoutNote(noteView, note)
                }
            }
            true
        }
    }

    private fun layoutNotes() {
        for ((note, view) in noteViews) layoutNote(view, note)
    }

    private fun layoutNote(view: View, note: StickyNote) {
        val canvas = binding.canvasView
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            view.post { layoutNote(view, note) }
            return
        }
        val scale = canvas.scale
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = note.width * scale / w
        view.scaleY = note.height * scale / h
        view.translationX = canvas.offsetX + note.x * scale
        view.translationY = canvas.offsetY + note.y * scale
    }

    // ---------------------------------------------------------------- 화면 설정

    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, !immersive)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        binding.btnFullscreen.text =
            getString(if (immersive) R.string.exit_fullscreen else R.string.fullscreen)
    }

    private fun collapseToolbar() {
        binding.toolbarContainer.visibility = View.GONE
        binding.handleContainer.visibility = View.VISIBLE
        updateBoardInsets()
    }

    private fun expandToolbar() {
        binding.toolbarContainer.visibility = View.VISIBLE
        binding.handleContainer.visibility = View.GONE
        updateBoardInsets()
    }

    private fun updateBoardInsets() {
        val lp = binding.boardArea.layoutParams as FrameLayout.LayoutParams
        val top = if (binding.toolbarContainer.visibility == View.VISIBLE) {
            binding.toolbarContainer.height
        } else {
            0
        }
        if (lp.topMargin != top) {
            lp.topMargin = top
            binding.boardArea.layoutParams = lp
        }
    }

    // ---------------------------------------------------------------- 녹화

    private val recordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val running = intent.getBooleanExtra(
                ScreenRecordService.EXTRA_IS_RECORDING, false
            )
            val saved = intent.getStringExtra(ScreenRecordService.EXTRA_SAVED_PATH)
            updateRecordUi(running)
            if (!running && saved != null) {
                toast(getString(R.string.recording_saved, saved))
            }
        }
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val settings = RecSettings(
                    Prefs.recordHeight,
                    Prefs.recordFps,
                    Prefs.recordBitrateMbps,
                    pendingMic
                )
                ScreenRecordService.start(this, result.resultCode, data, settings)
                if (Prefs.autoHideToolbar) collapseToolbar()
            } else {
                toast(getString(R.string.recording_cancelled))
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            launchProjection()
        }

    private fun onRecordClicked() {
        if (ScreenRecordService.isRecording) {
            ScreenRecordService.stop(this)
            return
        }
        val needed = ArrayList<String>()
        if (Prefs.recordMic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isEmpty()) {
            launchProjection()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun launchProjection() {
        pendingMic = Prefs.recordMic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (Prefs.recordMic && !pendingMic) {
            toast(getString(R.string.permission_audio_needed))
        }
        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun updateRecordUi(recording: Boolean) {
        val label = getString(if (recording) R.string.record_stop else R.string.record)
        binding.btnRecord.text = label
        binding.btnRecordCompact.text = label
    }

    // ---------------------------------------------------------------- 기타

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
