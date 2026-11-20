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
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hunsu.note.databinding.ActivityMainBinding
import com.hunsu.note.io.PageExporter
import com.hunsu.note.io.PdfPageProvider
import com.hunsu.note.model.BackgroundStyle
import com.hunsu.note.model.Lecture
import com.hunsu.note.model.NoteLayout
import com.hunsu.note.model.Page
import com.hunsu.note.model.StickyNote
import com.hunsu.note.model.Tool
import com.hunsu.note.rec.RecSettings
import com.hunsu.note.rec.ScreenRecordService
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 훈수노트 메인 화면 (아이캔노트형 3영역 레이아웃).
 * 상단 메뉴 바 · 좌측 페이지 썸네일 바 · 가운데 노트 캔버스 · 우측 세로 판서 툴바.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val lecture = Lecture()
    private var pdfProvider: PdfPageProvider? = null
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val noteViews = HashMap<StickyNote, View>()

    private val thumbEntries = ArrayList<ThumbEntry>()
    private val thumbBitmaps = HashMap<Page, Bitmap>()
    private val dirtyPages = HashSet<Page>()
    private var thumbJob: Runnable? = null

    private var penColor = Prefs.defaultPenColor
    private var penWidth = Prefs.defaultPenWidth
    private var penTouchedByUser = false
    private var immersive = false
    private var pendingMic = true
    private var toolButtons: List<Pair<ImageButton, Tool>> = emptyList()

    private var topBarVisible = true
    private var thumbPanelVisible = true
    private var toolRailVisible = true

    private var recStartMs = 0L
    private var recTicker: Runnable? = null

    private var lastAlign = -1
    private var lastMargin = -1f

    private data class ThumbEntry(val page: Page, val root: View, val image: ImageView)

    companion object {
        private const val MENU_SAVE_PNG = 1
        private const val MENU_SAVE_PDF = 2

        private const val MENU_NEW_LECTURE = 11
        private const val MENU_OPEN_PDF = 12
        private const val MENU_OPEN_IMAGE = 13
        private const val MENU_QUIT = 14

        private const val MENU_PAGE_ADD = 21
        private const val MENU_PAGE_DELETE = 22

        private const val MENU_FIT = 31
        private const val MENU_BG = 32
        private const val MENU_FULLSCREEN = 33
        private const val MENU_HIDE_BARS = 34

        private const val MENU_REC_TOGGLE = 41
        private const val MENU_REC_SETTINGS = 42

        private const val THUMB_WIDTH = 200

        private const val WIDTH_THIN = 3f
        private const val WIDTH_NORMAL = 7f
        private const val WIDTH_THICK = 14f

        private val PALETTE = intArrayOf(
            0xFF111111.toInt(),
            0xFF1E88E5.toInt(),
            0xFFE53935.toInt(),
            0xFF43A047.toInt(),
            0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(),
            0xFFFFD54F.toInt(),
            0xFFFFFFFF.toInt()
        )

        private val BACKGROUNDS = arrayOf(
            BackgroundStyle.WHITE,
            BackgroundStyle.CREAM,
            BackgroundStyle.GREEN,
            BackgroundStyle.NAVY,
            BackgroundStyle.GRID
        )
    }

    // ---------------------------------------------------------------- 생명주기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        penColor = Prefs.penColor
        penWidth = Prefs.penWidth
        lastAlign = Prefs.noteAlign
        lastMargin = Prefs.noteMargin

        setupCanvas()
        setupMenus()
        setupToolRail()
        setupColorPalette()
        setupBars()
        refreshWidthButtons()

        startBlankLecture(BackgroundStyle.WHITE)

        immersive = Prefs.startFullscreen
        applyImmersive()
        applyBars()
        binding.root.post { updateBoardInsets() }
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
            refreshColorDots()
            refreshWidthButtons()
        }
        applyNoteSettings()
    }

    override fun onDestroy() {
        stopRecTicker()
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
            onPageEdited = { markCurrentPageDirty() }
            onTransformChanged = { layoutNotes() }
        }
    }

    private fun setupMenus() {
        binding.btnMenuLecture.setOnClickListener { showLectureMenu(it) }
        binding.btnMenuSave.setOnClickListener { showSaveMenu(it) }
        binding.btnMenuPage.setOnClickListener { showPageMenu(it) }
        binding.btnMenuScreen.setOnClickListener { showScreenMenu(it) }
        binding.btnMenuRecord.setOnClickListener { showRecordMenu(it) }
        binding.btnMenuSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupBars() {
        binding.btnToggleThumb.setOnClickListener { setThumbPanelVisible(!thumbPanelVisible) }
        binding.btnFoldRail.setOnClickListener { setToolRailVisible(false) }
        binding.btnFoldRailInner.setOnClickListener { setToolRailVisible(false) }
        binding.btnHideAll.setOnClickListener { setBarsVisible(false, false, false) }
        binding.btnExpand.setOnClickListener { setBarsVisible(true, true, true) }
        binding.btnAddPage.setOnClickListener { addPage() }
        binding.btnDeletePage.setOnClickListener { confirmDeletePage() }
    }

    private fun setupToolRail() {
        toolButtons = listOf(
            binding.btnPen to Tool.PEN,
            binding.btnHighlighter to Tool.HIGHLIGHTER,
            binding.btnLine to Tool.LINE,
            binding.btnArrow to Tool.ARROW,
            binding.btnRect to Tool.RECT,
            binding.btnOval to Tool.OVAL,
            binding.btnEraser to Tool.ERASER,
            binding.btnHand to Tool.HAND
        )
        for ((button, tool) in toolButtons) {
            button.setOnClickListener { selectTool(tool) }
        }
        binding.btnNote.setOnClickListener { addStickyNote() }
        binding.btnUndo.setOnClickListener {
            if (binding.canvasView.undo()) markCurrentPageDirty()
        }
        binding.btnRedo.setOnClickListener {
            if (binding.canvasView.redo()) markCurrentPageDirty()
        }

        binding.btnWidthThin.setOnClickListener { setPenWidth(WIDTH_THIN) }
        binding.btnWidthNormal.setOnClickListener { setPenWidth(WIDTH_NORMAL) }
        binding.btnWidthThick.setOnClickListener { setPenWidth(WIDTH_THICK) }

        binding.btnRecord.setOnClickListener { onRecordClicked() }
        binding.btnPrevPage.setOnClickListener { goToPage(lecture.index - 1) }
        binding.btnNextPage.setOnClickListener { goToPage(lecture.index + 1) }

        selectTool(Tool.PEN)
    }

    private fun setupColorPalette() {
        val grid: GridLayout = binding.colorGrid
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (17 * density).toInt()
        val margin = (2 * density).toInt()

        for (color in PALETTE) {
            val dot = View(this)
            val lp = GridLayout.LayoutParams()
            lp.width = size
            lp.height = size
            lp.setMargins(margin, margin, margin, margin)
            dot.layoutParams = lp
            dot.tag = color
            dot.contentDescription = getString(R.string.pen_default_color)
            dot.setOnClickListener { selectPenColor(color) }
            grid.addView(dot)
        }
        refreshColorDots()
    }

    private fun refreshColorDots() {
        val grid = binding.colorGrid
        val density = resources.displayMetrics.density
        for (i in 0 until grid.childCount) {
            val dot = grid.getChildAt(i)
            val color = dot.tag as? Int ?: continue
            val selected = color == penColor
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    ((if (selected) 2.5f else 1f) * density).toInt(),
                    if (selected) ContextCompat.getColor(this@MainActivity, R.color.brand)
                    else 0x66FFFFFF
                )
            }
        }
    }

    private fun selectPenColor(color: Int) {
        penColor = color
        penTouchedByUser = true
        binding.canvasView.penColor = color
        Prefs.penColor = color
        refreshColorDots()
        val current = binding.canvasView.tool
        if (current == Tool.ERASER || current == Tool.HAND) selectTool(Tool.PEN)
    }

    private fun setPenWidth(width: Float) {
        penWidth = width
        penTouchedByUser = true
        binding.canvasView.penWidth = width
        Prefs.penWidth = width
        binding.canvasView.invalidate()
        refreshWidthButtons()
    }

    private fun refreshWidthButtons() {
        val values = floatArrayOf(WIDTH_THIN, WIDTH_NORMAL, WIDTH_THICK)
        val buttons = arrayOf(binding.btnWidthThin, binding.btnWidthNormal, binding.btnWidthThick)
        var nearest = 0
        var best = Float.MAX_VALUE
        for (i in values.indices) {
            val diff = kotlin.math.abs(values[i] - penWidth)
            if (diff < best) {
                best = diff
                nearest = i
            }
        }
        val on = ContextCompat.getColor(this, R.color.tool_text_selected)
        val off = ContextCompat.getColor(this, R.color.tool_text)
        for (i in buttons.indices) {
            val selected = i == nearest && penWidth == values[i]
            buttons[i].imageTintList = ColorStateList.valueOf(if (selected) on else off)
            buttons[i].backgroundTintList =
                ColorStateList.valueOf(if (selected) 0x33FFFFFF else 0x00000000)
        }
    }

    private fun selectTool(tool: Tool) {
        binding.canvasView.tool = tool
        val on = ContextCompat.getColor(this, R.color.tool_text_selected)
        val off = ContextCompat.getColor(this, R.color.tool_text)
        for ((button, t) in toolButtons) {
            val selected = t == tool
            button.imageTintList = ColorStateList.valueOf(if (selected) on else off)
            button.backgroundTintList =
                ColorStateList.valueOf(if (selected) 0x33FFFFFF else 0x00000000)
        }
    }

    // ---------------------------------------------------------------- 상단 메뉴

    private fun showLectureMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_NEW_LECTURE, 0, getString(R.string.menu_new_lecture))
        popup.menu.add(0, MENU_OPEN_PDF, 1, getString(R.string.open_pdf))
        popup.menu.add(0, MENU_OPEN_IMAGE, 2, getString(R.string.open_image))
        popup.menu.add(0, MENU_QUIT, 3, getString(R.string.menu_quit))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_NEW_LECTURE -> confirmNewLecture()
                MENU_OPEN_PDF -> pdfPicker.launch(arrayOf("application/pdf"))
                MENU_OPEN_IMAGE -> imagePicker.launch(arrayOf("image/*"))
                MENU_QUIT -> finish()
            }
            true
        }
        popup.show()
    }

    private fun showSaveMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_SAVE_PNG, 0, getString(R.string.save_png))
        popup.menu.add(0, MENU_SAVE_PDF, 1, getString(R.string.save_pdf))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SAVE_PNG -> saveCurrentPagePng()
                MENU_SAVE_PDF -> exportAllPagesPdf()
            }
            true
        }
        popup.show()
    }

    private fun showPageMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_PAGE_ADD, 0, getString(R.string.menu_page_add))
        popup.menu.add(0, MENU_PAGE_DELETE, 1, getString(R.string.menu_page_delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PAGE_ADD -> addPage()
                MENU_PAGE_DELETE -> confirmDeletePage()
            }
            true
        }
        popup.show()
    }

    private fun showScreenMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_FIT, 0, getString(R.string.menu_fit))
        popup.menu.add(0, MENU_BG, 1, getString(R.string.menu_bg))
        popup.menu.add(
            0, MENU_FULLSCREEN, 2,
            getString(if (immersive) R.string.exit_fullscreen else R.string.fullscreen)
        )
        popup.menu.add(0, MENU_HIDE_BARS, 3, getString(R.string.menu_hide_bars))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FIT -> {
                    binding.canvasView.fitPage()
                    layoutNotes()
                }

                MENU_BG -> chooseBackground { applyBackground(it) }
                MENU_FULLSCREEN -> {
                    immersive = !immersive
                    applyImmersive()
                }

                MENU_HIDE_BARS -> setBarsVisible(false, false, false)
            }
            true
        }
        popup.show()
    }

    private fun showRecordMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(
            0, MENU_REC_TOGGLE, 0,
            getString(
                if (ScreenRecordService.isRecording) R.string.menu_record_stop
                else R.string.menu_record_start
            )
        )
        popup.menu.add(0, MENU_REC_SETTINGS, 1, getString(R.string.section_record))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REC_TOGGLE -> onRecordClicked()
                MENU_REC_SETTINGS ->
                    startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        popup.show()
    }

    private fun chooseBackground(onPick: (BackgroundStyle) -> Unit) {
        val labels = arrayOf(
            getString(R.string.bg_white),
            getString(R.string.bg_cream),
            getString(R.string.bg_green),
            getString(R.string.bg_navy),
            getString(R.string.bg_grid)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_background)
            .setItems(labels) { _, which ->
                onPick(BACKGROUNDS.getOrElse(which) { BackgroundStyle.WHITE })
            }
            .show()
    }

    /** 이미 열린 강의의 노트 배경색을 바꾼다. */
    private fun applyBackground(style: BackgroundStyle) {
        lecture.style = style
        if (lecture.pages.isEmpty()) {
            startBlankLecture(style)
            return
        }
        for (page in lecture.pages) {
            page.style = style
            dirtyPages.add(page)
        }
        binding.canvasView.invalidate()
        scheduleThumbRefresh(120)
    }

    // ---------------------------------------------------------------- 페이지

    private fun makePage(
        layout: NoteLayout,
        style: BackgroundStyle,
        pdfIndex: Int = -1,
        image: Bitmap? = null,
        aspect: Float = 0f
    ): Page {
        val page = Page(layout, style, pdfIndex, image, aspect)
        page.applyContentLayout(Prefs.noteAlignEnum(), Prefs.noteMargin)
        return page
    }

    private fun startBlankLecture(style: BackgroundStyle) {
        closePdf()
        lecture.pages.clear()
        thumbBitmaps.clear()
        dirtyPages.clear()
        lecture.style = style
        lecture.pages.add(makePage(Prefs.noteLayoutEnum(), style))
        lecture.index = 0
        rebuildThumbs()
        showPage()
    }

    private fun confirmNewLecture() {
        val hasContent = lecture.pages.any { it.elements.isNotEmpty() || it.notes.isNotEmpty() }
        if (!hasContent) {
            chooseBackground { startBlankLecture(it) }
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_title)
            .setMessage(R.string.discard_message)
            .setNegativeButton(R.string.discard_cancel, null)
            .setPositiveButton(R.string.discard_ok) { _, _ ->
                chooseBackground { startBlankLecture(it) }
            }
            .show()
    }

    private fun showPage() {
        val page = lecture.currentPage
        if (page == null) {
            binding.canvasView.setPage(null, null)
            binding.stickyLayer.removeAllViews()
            noteViews.clear()
            updatePageInfo()
            updateUndoRedo()
            return
        }
        binding.canvasView.setPage(page, page.renderedBackground)
        bindNotes(page)
        updatePageInfo()
        updateUndoRedo()
        loadBackground(page)
        updateThumbSelection()
        scheduleThumbRefresh(200)
    }

    private fun goToPage(index: Int) {
        if (index !in lecture.pages.indices) return
        if (index == lecture.index) return
        lecture.currentPage?.let { dirtyPages.add(it) }
        lecture.index = index
        showPage()
    }

    private fun addPage() {
        if (lecture.pages.isEmpty()) return
        val page = makePage(lecture.layout, lecture.style)
        val at = (lecture.index + 1).coerceIn(0, lecture.pages.size)
        lecture.pages.add(at, page)
        lecture.index = at
        dirtyPages.add(page)
        rebuildThumbs()
        showPage()
    }

    private fun confirmDeletePage() {
        if (lecture.pages.size <= 1) {
            toast(getString(R.string.last_page_cannot_delete))
            return
        }
        val page = lecture.currentPage ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_page_confirm)
            .setNegativeButton(R.string.discard_cancel, null)
            .setPositiveButton(R.string.delete_page_ok) { _, _ ->
                val index = lecture.index
                lecture.pages.removeAt(index)
                thumbBitmaps.remove(page)
                dirtyPages.remove(page)
                lecture.index = index.coerceIn(0, lecture.pages.size - 1)
                rebuildThumbs()
                showPage()
            }
            .show()
    }

    private fun updatePageInfo() {
        val total = lecture.pages.size
        val current = if (total == 0) 0 else lecture.index + 1
        binding.tvPageInfo.text = getString(R.string.page_info_format, current, total)
        binding.tvPageNav.text = current.toString()
    }

    private fun updateUndoRedo() {
        val page = lecture.currentPage
        val canUndo = page?.canUndo == true
        val canRedo = page?.canRedo == true
        binding.btnUndo.isEnabled = canUndo
        binding.btnRedo.isEnabled = canRedo
        binding.btnUndo.alpha = if (canUndo) 1f else 0.35f
        binding.btnRedo.alpha = if (canRedo) 1f else 0.35f
    }

    private fun markCurrentPageDirty() {
        lecture.currentPage?.let { dirtyPages.add(it) }
        updateUndoRedo()
        scheduleThumbRefresh(500)
    }

    private fun applyNoteSettings() {
        val align = Prefs.noteAlign
        val margin = Prefs.noteMargin
        if (align == lastAlign && margin == lastMargin) return
        lastAlign = align
        lastMargin = margin
        for (page in lecture.pages) {
            page.applyContentLayout(Prefs.noteAlignEnum(), margin)
            dirtyPages.add(page)
        }
        binding.canvasView.invalidate()
        scheduleThumbRefresh(120)
    }

    // ---------------------------------------------------------------- 썸네일

    private fun rebuildThumbs() {
        binding.thumbList.removeAllViews()
        thumbEntries.clear()
        for ((i, page) in lecture.pages.withIndex()) {
            val item = layoutInflater.inflate(R.layout.item_thumbnail, binding.thumbList, false)
            val number = item.findViewById<TextView>(R.id.thumbNumber)
            number.text = (i + 1).toString()
            val image = item.findViewById<ImageView>(R.id.thumbImage)
            val index = i
            item.setOnClickListener { goToPage(index) }
            binding.thumbList.addView(item)
            thumbEntries.add(ThumbEntry(page, item, image))
        }
        updateThumbSelection()
        scheduleThumbRefresh(80)
    }

    private fun updateThumbSelection() {
        for ((i, entry) in thumbEntries.withIndex()) {
            entry.root.isSelected = i == lecture.index
        }
        val selected = thumbEntries.getOrNull(lecture.index) ?: return
        binding.thumbScroll.post {
            val target = (selected.root.top - 140).coerceAtLeast(0)
            binding.thumbScroll.smoothScrollTo(0, target)
        }
    }

    private fun scheduleThumbRefresh(delayMs: Long) {
        thumbJob?.let { ui.removeCallbacks(it) }
        val job = Runnable { refreshThumbs() }
        thumbJob = job
        ui.postDelayed(job, delayMs)
    }

    private fun refreshThumbs() {
        for (entry in thumbEntries) {
            val page = entry.page
            if (!dirtyPages.contains(page)) {
                thumbBitmaps[page]?.let { entry.image.setImageBitmap(it) }
                continue
            }
            val width = THUMB_WIDTH
            val height = (width * page.worldHeight / page.worldWidth).toInt().coerceAtLeast(16)
            io.execute {
                val background = backgroundForThumb(page)
                val bitmap = try {
                    PageExporter.renderThumb(page, background, width, height)
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null) {
                    dirtyPages.remove(page)
                    ui.post {
                        thumbBitmaps[page] = bitmap
                        val index = thumbEntries.indexOfFirst { it.page === page }
                        if (index >= 0) thumbEntries[index].image.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 배경(PDF/이미지)

    private fun loadBackground(page: Page) {
        val image = page.image
        if (image != null) {
            page.renderedBackground = image
            if (lecture.currentPage === page) binding.canvasView.setPageBackground(image)
            return
        }
        if (page.pdfPageIndex < 0) {
            if (lecture.currentPage === page) binding.canvasView.setPageBackground(null)
            return
        }
        val existing = page.renderedBackground
        if (existing != null) {
            if (lecture.currentPage === page) binding.canvasView.setPageBackground(existing)
            return
        }
        val provider = pdfProvider ?: return
        val pdfIndex = page.pdfPageIndex
        val targetWidth = page.contentRect.width().toInt().coerceAtLeast(64)
        io.execute {
            val bitmap = provider.render(pdfIndex, targetWidth)
            ui.post {
                if (bitmap != null) {
                    page.renderedBackground = bitmap
                    if (lecture.currentPage === page) {
                        binding.canvasView.setPageBackground(bitmap)
                    }
                    dirtyPages.add(page)
                    scheduleThumbRefresh(120)
                }
            }
        }
    }

    private fun backgroundForThumb(page: Page): Bitmap? {
        page.image?.let { return it }
        if (page.pdfPageIndex < 0) return null
        page.renderedBackground?.let { return it }
        return try {
            pdfProvider?.render(page.pdfPageIndex, 360)
        } catch (e: Exception) {
            null
        }
    }

    private fun backgroundForExport(page: Page): Bitmap? {
        page.image?.let { return it }
        if (page.pdfPageIndex < 0) return null
        page.renderedBackground?.let { return it }
        return try {
            pdfProvider?.render(page.pdfPageIndex, page.contentRect.width().toInt().coerceAtLeast(64))
        } catch (e: Exception) {
            null
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

    // ---------------------------------------------------------------- PDF / 이미지 열기

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
        val layout = Prefs.noteLayoutEnum()
        lecture.pages.clear()
        thumbBitmaps.clear()
        dirtyPages.clear()
        for (i in 0 until provider.pageCount) {
            val aspect = provider.aspectOf(i).coerceIn(0.2f, 5f)
            val page = makePage(layout, BackgroundStyle.WHITE, i, null, aspect)
            lecture.pages.add(page)
            dirtyPages.add(page)
        }
        lecture.index = 0
        lecture.style = BackgroundStyle.WHITE
        rebuildThumbs()
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
        lecture.pages.clear()
        thumbBitmaps.clear()
        dirtyPages.clear()
        val page = makePage(
            Prefs.noteLayoutEnum(), BackgroundStyle.WHITE, -1, bitmap, aspect.coerceIn(0.2f, 5f)
        )
        lecture.pages.add(page)
        dirtyPages.add(page)
        lecture.index = 0
        lecture.style = BackgroundStyle.WHITE
        rebuildThumbs()
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

    // ---------------------------------------------------------------- 포스트잇

    private fun addStickyNote() {
        val page = lecture.currentPage ?: return
        val cx = binding.canvasView.screenToWorldX(binding.stickyLayer.width / 2f)
        val cy = binding.canvasView.screenToWorldY(binding.stickyLayer.height / 2f)
        val note = StickyNote("", cx - 170f, cy - 140f, 340f, 280f)
        page.notes.add(note)
        addNoteView(page, note)
        dirtyPages.add(page)
        scheduleThumbRefresh(600)
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
                dirtyPages.add(page)
                scheduleThumbRefresh(900)
            }
        })
        view.findViewById<View>(R.id.stickyDelete).setOnClickListener {
            page.notes.remove(note)
            noteViews.remove(note)
            binding.stickyLayer.removeView(view)
            dirtyPages.add(page)
            scheduleThumbRefresh(200)
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

    // ---------------------------------------------------------------- 화면 / 바

    private fun setThumbPanelVisible(visible: Boolean) {
        thumbPanelVisible = visible
        applyBars()
    }

    private fun setToolRailVisible(visible: Boolean) {
        toolRailVisible = visible
        applyBars()
    }

    private fun setBarsVisible(top: Boolean, left: Boolean, right: Boolean) {
        topBarVisible = top
        thumbPanelVisible = left
        toolRailVisible = right
        applyBars()
    }

    private fun applyBars() {
        binding.topBar.visibility = if (topBarVisible) View.VISIBLE else View.GONE
        binding.thumbPanel.visibility = if (thumbPanelVisible) View.VISIBLE else View.GONE
        binding.toolRail.visibility = if (toolRailVisible) View.VISIBLE else View.GONE
        // 상단 바가 없으면 좌우 바를 되살릴 길이 없으므로 작은 복귀 버튼을 띄운다.
        binding.handleContainer.visibility = if (topBarVisible) View.GONE else View.VISIBLE
        updateBoardInsets()
    }

    private fun updateBoardInsets() {
        binding.root.post {
            val top = if (binding.topBar.visibility == View.VISIBLE) binding.topBar.height else 0
            val start = if (binding.thumbPanel.visibility == View.VISIBLE) binding.thumbPanel.width else 0
            val end = if (binding.toolRail.visibility == View.VISIBLE) binding.toolRail.width else 0

            val boardLp = binding.boardArea.layoutParams as FrameLayout.LayoutParams
            if (boardLp.topMargin != top || boardLp.marginStart != start || boardLp.marginEnd != end) {
                boardLp.topMargin = top
                boardLp.marginStart = start
                boardLp.marginEnd = end
                binding.boardArea.layoutParams = boardLp
            }

            val thumbLp = binding.thumbPanel.layoutParams as FrameLayout.LayoutParams
            if (thumbLp.topMargin != top) {
                thumbLp.topMargin = top
                binding.thumbPanel.layoutParams = thumbLp
            }

            val railLp = binding.toolRail.layoutParams as FrameLayout.LayoutParams
            if (railLp.topMargin != top) {
                railLp.topMargin = top
                binding.toolRail.layoutParams = railLp
            }
        }
    }

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
                recStartMs = SystemClock.elapsedRealtime()
                ScreenRecordService.start(this, result.resultCode, data, settings)
                if (Prefs.autoHideToolbar) setBarsVisible(false, false, false)
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
        stopRecTicker()
        if (recording) {
            if (recStartMs == 0L) recStartMs = SystemClock.elapsedRealtime()
            binding.btnRecord.backgroundTintList =
                ColorStateList.valueOf(0xFF7E1D18.toInt())
            startRecTicker()
        } else {
            recStartMs = 0L
            binding.btnRecord.text = getString(R.string.record)
            binding.btnRecord.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.record_red))
        }
    }

    private fun startRecTicker() {
        val runnable = object : Runnable {
            override fun run() {
                val seconds = ((SystemClock.elapsedRealtime() - recStartMs) / 1000L).toInt()
                binding.btnRecord.text = String.format(
                    Locale.US, "■ %02d:%02d", seconds / 60, seconds % 60
                )
                ui.postDelayed(this, 1000L)
            }
        }
        recTicker = runnable
        ui.post(runnable)
    }

    private fun stopRecTicker() {
        recTicker?.let { ui.removeCallbacks(it) }
        recTicker = null
    }

    // ---------------------------------------------------------------- 기타

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
