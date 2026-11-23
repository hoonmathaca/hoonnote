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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hunsu.note.databinding.ActivityMainBinding
import com.hunsu.note.io.AnswerHider
import com.hunsu.note.io.PageBackgroundCache
import com.hunsu.note.io.PageExporter
import com.hunsu.note.io.PdfPageProvider
import com.hunsu.note.io.PdfPageSource
import com.hunsu.note.io.PdfPages
import com.hunsu.note.model.BackgroundStyle
import com.hunsu.note.model.DrawElement
import com.hunsu.note.model.ElementKind
import com.hunsu.note.model.Lecture
import com.hunsu.note.model.NoteLayout
import com.hunsu.note.model.Page
import com.hunsu.note.model.Pt
import com.hunsu.note.model.StickyGeometry
import com.hunsu.note.model.StickyNote
import com.hunsu.note.model.StrokeWidths
import com.hunsu.note.model.Tool
import com.hunsu.note.rec.RecSettings
import com.hunsu.note.rec.RecordQuality
import com.hunsu.note.rec.ScreenRecordService
import com.hunsu.note.util.PageRange
import com.hunsu.note.view.InlineTextEditor
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 훈노트 메인 화면 (아이캔노트형 3영역 레이아웃).
 * 상단 메뉴 바 · 좌측 페이지 썸네일 바 · 가운데 노트 캔버스 · 우측 세로 판서 툴바.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val lecture = Lecture()
    private val io = Executors.newSingleThreadExecutor()

    /** 페이지 배경(PDF·이미지)은 여기에 파일로 두고, 보는 페이지만 메모리에 올린다. */
    private lateinit var backgroundCache: PageBackgroundCache

    /** 이어 쌓은 PDF 문서들 — 지금 그려야 하는 문서만 열어 두고, 배경이 다 캐시되면 닫는다. */
    private val pdfDocs = LinkedHashMap<String, PdfPageSource>()
    private val pdfUris = HashMap<String, Uri>()

    /** 이미지 페이지의 원본 URI — 캐시 파일이 지워져도 다시 읽어 그릴 수 있게 남긴다. */
    private val imageUris = HashMap<String, Uri>()
    private var docSeq = 0
    private val pendingBackgrounds = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val noteViews = HashMap<StickyNote, View>()

    private val thumbEntries = ArrayList<ThumbEntry>()
    private val thumbBitmaps = HashMap<Page, Bitmap>()
    private val dirtyPages = HashSet<Page>()
    private var thumbJob: Runnable? = null

    private var penColor = Prefs.defaultPenColor
    private var penWidth = Prefs.defaultPenWidth
    private var highlighterColor = Prefs.DEFAULT_HIGHLIGHTER_COLOR
    private var highlighterWidth = 20f

    /** 글자 도구의 글자 크기 (노트 world 좌표) */
    private var textSize = StrokeWidths.TEXT[2]

    /** 3.5: 새로 그리는 덮개 떡메모지에 얇은 테두리를 둘지 (기본은 없음 = 순수 흰 면) */
    private var coverBordered = false

    /** 3.6: 교사용 교재의 하늘색 답안·해설을 가리고 보는지 */
    private var answerHidden = false

    /**
     * '답안 가리기'로 만든 화면용 사본. 페이지를 넘길 때마다 다시 만들지 않도록
     * 최근 두 장만 들고 있다(원본 배경·캐시 파일은 그대로 둔다).
     */
    private val answerBackgrounds = HashMap<String, Bitmap>()

    /** 3.5: 팝업 없이 캔버스 그 자리에서 받는 글자 입력기 */
    private var inlineText: InlineTextEditor? = null

    /** 인라인 입력을 시작한 페이지 (확정할 때 그 페이지에 요소를 넣는다) */
    private var inlineTextPage: Page? = null

    private var penTouchedByUser = false
    private var immersive = false
    private var pendingMic = true
    private var toolButtons: List<Pair<ImageButton, Tool>> = emptyList()
    private var widthChips: List<TextView> = emptyList()

    private var topBarVisible = true
    private var thumbPanelVisible = true
    private var toolRailVisible = true

    private var recStartMs = 0L
    private var recTicker: Runnable? = null
    private var barsHiddenByRecording = false

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
        private const val MENU_NEW_NOTE = 15

        private const val MENU_PAGE_ADD = 21
        private const val MENU_PAGE_DELETE = 22

        private const val MENU_FIT = 31
        private const val MENU_BG = 32
        private const val MENU_FULLSCREEN = 33
        private const val MENU_HIDE_BARS = 34
        private const val MENU_PDF_ROTATE = 35

        private const val MENU_REC_TOGGLE = 41
        private const val MENU_REC_SETTINGS = 42
        private const val MENU_REC_QUALITY = 43

        private const val THUMB_WIDTH = 200

        /** 동시에 열어 두는 PDF 렌더러 수 (배경이 다 캐시된 문서부터 닫는다) */
        private const val MAX_OPEN_DOCS = 8

        private val PEN_PALETTE = intArrayOf(
            0xFF111111.toInt(),
            0xFF1E88E5.toInt(),
            0xFFE53935.toInt(),
            0xFF43A047.toInt(),
            0xFFFB8C00.toInt(),
            0xFF8E24AA.toInt(),
            0xFFFFD54F.toInt(),
            0xFFFFFFFF.toInt()
        )

        /** 형광펜 색: 노랑·연두·분홍·하늘 + 주황·보라 */
        private val HIGHLIGHTER_PALETTE = intArrayOf(
            0xFFFFEB3B.toInt(),
            0xFF76FF03.toInt(),
            0xFFFF4081.toInt(),
            0xFF40C4FF.toInt(),
            0xFFFFAB40.toInt(),
            0xFFB388FF.toInt()
        )

        private val BACKGROUNDS = arrayOf(
            BackgroundStyle.WHITE,
            BackgroundStyle.CREAM,
            BackgroundStyle.GREEN,
            BackgroundStyle.NAVY,
            BackgroundStyle.GRID
        )

        private val ROTATIONS = intArrayOf(0, 90, 180, 270)
    }

    // ---------------------------------------------------------------- 생명주기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 상단 제목의 버전은 빌드된 버전을 그대로 보여 준다(문자열을 따로 고치지 않게).
        binding.tvTitle.text = getString(R.string.app_title, BuildConfig.VERSION_NAME)
        backgroundCache = PageBackgroundCache(File(cacheDir, "page-backgrounds"))
        // 노트 내용은 앱을 끄면 남지 않으므로, 지난 실행에서 남은 배경 캐시 파일을 비운다.
        backgroundCache.clear()

        // 상태바·내비바를 완전히 숨기기 위해 창을 시스템 바 뒤까지 확장한다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }

        penColor = Prefs.penColor
        penWidth = Prefs.penWidth
        highlighterColor = Prefs.highlighterColor
        highlighterWidth = Prefs.highlighterWidth
        coverBordered = Prefs.coverBorder
        answerHidden = Prefs.answerHidden
        lastAlign = Prefs.noteAlign
        lastMargin = Prefs.noteMargin

        setupCanvas()
        setupMenus()
        setupToolRail()
        setupBars()
        setupCoverBorderChips()
        refreshPalette()
        refreshWidthButtons()
        refreshAnswerHideButton()

        onBackPressedDispatcher.addCallback(this) { onBackPressedWithInputs() }

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

    override fun onPause() {
        // 화면을 벗어날 때 입력 중이던 글자는 잃지 않고 확정한다.
        commitInlineText()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.canvasView.fingerDrawingAllowed = Prefs.palmMode == Prefs.PALM_FINGER
        binding.canvasView.pressureEnabled = Prefs.pressureEnabled
        if (!penTouchedByUser) {
            penColor = Prefs.penColor
            penWidth = Prefs.penWidth
            highlighterColor = Prefs.highlighterColor
            highlighterWidth = Prefs.highlighterWidth
            applyCurrentToolStyle()
            refreshPalette()
            refreshWidthButtons()
        }
        applyNoteSettings()
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 다이얼로그나 알림 뒤로 상태바가 다시 나타나는 경우를 매번 되돌린다.
        if (hasFocus) applyImmersive()
    }

    override fun onDestroy() {
        stopRecTicker()
        io.shutdownNow()
        closeAllPdfs()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 초기 설정

    private fun setupCanvas() {
        inlineText = InlineTextEditor(binding.boardArea, binding.canvasView).apply {
            onCommit = { x, y, text ->
                val page = inlineTextPage ?: lecture.currentPage
                inlineTextPage = null
                if (page != null) addTextElement(page, x, y, text)
            }
        }
        binding.canvasView.apply {
            penColor = this@MainActivity.penColor
            penWidth = this@MainActivity.penWidth
            pressureEnabled = Prefs.pressureEnabled
            fingerDrawingAllowed = Prefs.palmMode == Prefs.PALM_FINGER
            coverBorder = coverBordered
            onPageEdited = { markCurrentPageDirty() }
            onTransformChanged = { layoutNotes() }
            onNoteRect = { x0, y0, x1, y1 -> addStickyNote(x0, y0, x1, y1) }
            onTextTap = { x, y -> startInlineText(x, y) }
            onCanvasTouchDown = {
                // 캔버스 바깥(다른 곳)을 누르면 글자 입력은 확정, 포스트잇 커서는 사라진다.
                commitInlineText()
                clearStickyFocus()
            }
        }
    }

    private fun setupMenus() {
        binding.btnMenuLecture.setOnClickListener {
            commitInputs()
            showLectureMenu(it)
        }
        binding.btnMenuSave.setOnClickListener {
            commitInputs()
            showSaveMenu(it)
        }
        binding.btnMenuPage.setOnClickListener {
            commitInputs()
            showPageMenu(it)
        }
        binding.btnMenuScreen.setOnClickListener {
            commitInputs()
            showScreenMenu(it)
        }
        binding.btnMenuRecord.setOnClickListener {
            commitInputs()
            showRecordMenu(it)
        }
        binding.btnMenuSettings.setOnClickListener {
            commitInputs()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnAnswerHide.setOnClickListener { setAnswerHidden(!answerHidden) }
    }

    /** 3.5: 메뉴·도구를 건드리기 전에 입력 중인 글자를 확정하고 포스트잇 커서를 닫는다. */
    private fun commitInputs() {
        commitInlineText()
        clearStickyFocus()
    }

    private fun setupBars() {
        binding.btnNewNote.setOnClickListener { confirmNewNote() }
        binding.btnToggleThumb.setOnClickListener { setThumbPanelVisible(!thumbPanelVisible) }
        binding.btnFoldRail.setOnClickListener { setToolRailVisible(false) }
        binding.btnFoldRailInner.setOnClickListener { setToolRailVisible(false) }
        binding.btnHideAll.setOnClickListener { setBarsVisible(false, false, false) }
        binding.btnExpand.setOnClickListener { setBarsVisible(true, true, true) }
        binding.btnAddPage.setOnClickListener { addPage() }
        binding.btnDeletePage.setOnClickListener { confirmDeletePage() }
    }

    private fun setupToolRail() {
        val buttons = arrayListOf(
            binding.btnPen to Tool.PEN,
            binding.btnHighlighter to Tool.HIGHLIGHTER,
            binding.btnLine to Tool.LINE,
            binding.btnDashed to Tool.DASHED_LINE,
            binding.btnRect to Tool.RECT,
            binding.btnOval to Tool.OVAL,
            binding.btnTriangle to Tool.TRIANGLE,
            binding.btnCover to Tool.COVER,
            binding.btnText to Tool.TEXT,
            binding.btnNote to Tool.NOTE,
            binding.btnEraser to Tool.ERASER,
            binding.btnEraserPartial to Tool.ERASER_PARTIAL
        )
        toolButtons = buttons
        for ((button, tool) in buttons) {
            button.setOnClickListener {
                selectTool(tool)
                if (tool == Tool.NOTE) toast(getString(R.string.sticky_draw_hint))
            }
        }
        binding.btnUndo.setOnClickListener {
            commitInputs()
            if (binding.canvasView.undo()) markCurrentPageDirty()
        }
        binding.btnRedo.setOnClickListener {
            commitInputs()
            if (binding.canvasView.redo()) markCurrentPageDirty()
        }

        binding.btnRecord.setOnClickListener { onRecordClicked() }
        binding.btnPrevPage.setOnClickListener { goToPage(lecture.index - 1) }
        binding.btnNextPage.setOnClickListener { goToPage(lecture.index + 1) }

        selectTool(Tool.PEN)
    }

    // ---------------------------------------------------------------- 색·굵기

    private fun currentTool(): Tool = binding.canvasView.tool

    private fun isHighlighterActive(): Boolean = currentTool() == Tool.HIGHLIGHTER

    private fun currentStrokeColor(): Int =
        if (isHighlighterActive()) highlighterColor else penColor

    private fun currentStrokeWidth(): Float = when {
        isHighlighterActive() -> highlighterWidth
        currentTool() == Tool.TEXT -> textSize
        else -> penWidth
    }

    /** 도구에 맞는 색을 캔버스에 적용한다. */
    private fun applyCurrentToolStyle() {
        binding.canvasView.penColor = currentStrokeColor()
        binding.canvasView.penWidth = currentStrokeWidth()
    }

    private fun refreshPalette() {
        val grid: GridLayout = binding.colorGrid
        grid.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (17 * density).toInt()
        val margin = (2 * density).toInt()
        val palette = if (isHighlighterActive()) HIGHLIGHTER_PALETTE else PEN_PALETTE

        for (color in palette) {
            val dot = View(this)
            val lp = GridLayout.LayoutParams()
            lp.width = size
            lp.height = size
            lp.setMargins(margin, margin, margin, margin)
            dot.layoutParams = lp
            dot.tag = color
            dot.contentDescription = getString(
                if (isHighlighterActive()) R.string.tool_highlighter else R.string.pen_default_color
            )
            dot.setOnClickListener { selectPenColor(color) }
            grid.addView(dot)
        }
        refreshColorDots()
    }

    private fun refreshColorDots() {
        val grid = binding.colorGrid
        val density = resources.displayMetrics.density
        val selectedColor = currentStrokeColor()
        for (i in 0 until grid.childCount) {
            val dot = grid.getChildAt(i)
            val color = dot.tag as? Int ?: continue
            val selected = color == selectedColor
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
        if (isHighlighterActive()) {
            highlighterColor = color
            Prefs.highlighterColor = color
        } else {
            penColor = color
            penTouchedByUser = true
            Prefs.penColor = color
        }
        binding.canvasView.penColor = color
        // 입력 중인 글자의 색도 바로 바뀌게 한다.
        inlineText?.setStyle(penColor, textSize)
        refreshColorDots()
        val current = currentTool()
        if (current == Tool.ERASER || current == Tool.ERASER_PARTIAL) {
            selectTool(Tool.PEN)
        }
    }

    private fun setStrokeWidth(width: Float) {
        if (isHighlighterActive()) {
            highlighterWidth = width
            Prefs.highlighterWidth = width
        } else if (currentTool() == Tool.TEXT) {
            textSize = width
        } else {
            penWidth = width
            penTouchedByUser = true
            Prefs.penWidth = width
        }
        binding.canvasView.penWidth = currentStrokeWidth()
        binding.canvasView.invalidate()
        // 입력 중인 글자의 크기도 그 자리에서 바로 바뀌게 한다.
        inlineText?.setStyle(penColor, textSize)
        refreshWidthButtons()
    }

    /** 굵기 단계 버튼을 지금 도구의 단계에 맞춰 다시 만든다. */
    private fun refreshWidthButtons() {
        val grid = binding.widthGrid ?: return
        // 글자 도구일 때는 같은 칩이 '글자 크기' 단계가 된다는 것을 알려 준다.
        binding.tvSizeLabel.text = when (currentTool()) {
            Tool.TEXT -> getString(R.string.text_size_label)
            Tool.HIGHLIGHTER -> getString(R.string.highlighter_width_label)
            else -> getString(R.string.stroke_width_label)
        }
        val values = StrokeWidths.of(currentTool())
        val active = currentStrokeWidth()
        val density = resources.displayMetrics.density

        if (widthChips.size != values.size) {
            grid.removeAllViews()
            val list = ArrayList<TextView>()
            for (value in values) {
                val chip = TextView(this)
                chip.text = StrokeWidths.label(value)
                chip.textSize = 9.5f
                chip.gravity = Gravity.CENTER
                chip.typeface = Typeface.DEFAULT_BOLD
                chip.isClickable = true
                val lp = GridLayout.LayoutParams()
                lp.width = (28 * density).toInt()
                lp.height = (22 * density).toInt()
                lp.setMargins((1 * density).toInt(), (1 * density).toInt(), (1 * density).toInt(), (1 * density).toInt())
                chip.layoutParams = lp
                chip.tag = value
                chip.setOnClickListener { v -> setStrokeWidth(v.tag as Float) }
                grid.addView(chip)
                list.add(chip)
            }
            widthChips = list
        } else {
            for ((i, chip) in widthChips.withIndex()) {
                chip.text = StrokeWidths.label(values[i])
                chip.tag = values[i]
            }
        }

        val selected = StrokeWidths.nearestIndex(values, active)
        val exact = values[selected] == active
        val on = ContextCompat.getColor(this, R.color.tool_text_selected)
        val off = ContextCompat.getColor(this, R.color.tool_text)
        for ((i, chip) in widthChips.withIndex()) {
            val isSelected = exact && i == selected
            chip.setTextColor(if (isSelected) on else off)
            chip.background = GradientDrawable().apply {
                cornerRadius = 5 * density
                setColor(if (isSelected) 0x33FFFFFF else 0x00000000)
            }
        }
    }

    private fun selectTool(tool: Tool) {
        commitInputs()
        binding.canvasView.tool = tool
        val on = ContextCompat.getColor(this, R.color.tool_text_selected)
        val off = ContextCompat.getColor(this, R.color.tool_text)
        for ((button, t) in toolButtons) {
            val selected = t == tool
            button.imageTintList = ColorStateList.valueOf(if (selected) on else off)
            button.backgroundTintList =
                ColorStateList.valueOf(if (selected) 0x33FFFFFF else 0x00000000)
        }
        // 떡메모지일 때는 '테두리 있음/없음' 선택을 보이고, 굵기 칩은 숨긴다.
        val isCover = tool == Tool.COVER
        binding.coverBorderRow.visibility = if (isCover) View.VISIBLE else View.GONE
        binding.widthGrid.visibility = if (isCover) View.GONE else View.VISIBLE
        binding.tvSizeLabel.visibility = if (isCover) View.GONE else View.VISIBLE
        applyCurrentToolStyle()
        refreshPalette()
        refreshWidthButtons()
        refreshCoverBorderChips()
    }

    // ---------------------------------------------------------------- 상단 메뉴

    private fun showLectureMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_NEW_LECTURE, 0, getString(R.string.menu_new_lecture))
        popup.menu.add(0, MENU_NEW_NOTE, 4, getString(R.string.menu_new_note))
        popup.menu.add(0, MENU_OPEN_PDF, 1, getString(R.string.open_pdf))
        popup.menu.add(0, MENU_OPEN_IMAGE, 2, getString(R.string.open_image))
        popup.menu.add(0, MENU_QUIT, 3, getString(R.string.menu_quit))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_NEW_LECTURE -> confirmNewLecture()
                MENU_NEW_NOTE -> confirmNewNote()
                MENU_OPEN_PDF -> pdfPicker.launch(arrayOf("application/pdf"))
                MENU_OPEN_IMAGE -> imagePicker.launch(arrayOf("image/*"))
                MENU_QUIT -> confirmExit()
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
        popup.menu.add(0, MENU_PDF_ROTATE, 2, getString(R.string.menu_pdf_rotate))
        popup.menu.add(
            0, MENU_FULLSCREEN, 3,
            getString(if (immersive) R.string.exit_fullscreen else R.string.fullscreen)
        )
        popup.menu.add(0, MENU_HIDE_BARS, 4, getString(R.string.menu_hide_bars))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FIT -> {
                    binding.canvasView.fitPage()
                    layoutNotes()
                }

                MENU_BG -> chooseBackground { applyBackground(it) }
                MENU_PDF_ROTATE -> showRotationDialog()
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
        popup.menu.add(
            0, MENU_REC_QUALITY, 1,
            getString(
                R.string.menu_record_quality,
                qualityShortLabel(Prefs.recordQuality ?: RecordQuality.DEFAULT)
            )
        )
        popup.menu.add(0, MENU_REC_SETTINGS, 2, getString(R.string.section_record))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REC_TOGGLE -> onRecordClicked()
                MENU_REC_QUALITY -> showRecordQualityDialog()
                MENU_REC_SETTINGS ->
                    startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        popup.show()
    }

    /** 화질 프리셋 이름 (메뉴·토스트용) */
    private fun qualityShortLabel(quality: RecordQuality): String = when (quality) {
        RecordQuality.NORMAL -> getString(R.string.record_quality_normal)
        RecordQuality.ECONOMY -> getString(R.string.record_quality_economy_short)
    }

    /**
     * 녹화 화질(용량)을 미리 고르는 팝업. 녹화를 시작하지는 않으므로
     * 3.1 의 '누르면 곧바로 녹화' 동작은 그대로다.
     */
    private fun showRecordQualityDialog() {
        val order = arrayOf(RecordQuality.ECONOMY, RecordQuality.NORMAL)
        val withMic = Prefs.recordMic
        val labels = order.map { quality ->
            if (quality == RecordQuality.NORMAL) {
                getString(
                    R.string.record_quality_item_normal,
                    quality.estimatedMbPerMinute(withMic)
                )
            } else {
                getString(
                    R.string.record_quality_item_economy,
                    quality.estimatedMbPerMinute(withMic)
                )
            }
        }.toTypedArray()
        val current = Prefs.recordQuality
        val checked = if (current == null) 0 else order.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.record_quality_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                applyRecordQuality(order[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun applyRecordQuality(quality: RecordQuality) {
        Prefs.applyRecordQuality(quality)
        val label = qualityShortLabel(quality)
        val mb = quality.estimatedMbPerMinute(Prefs.recordMic)
        val message = if (ScreenRecordService.isRecording) {
            getString(R.string.record_quality_changed_recording, label)
        } else {
            getString(R.string.record_quality_changed, label, mb)
        }
        toast(message)
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
        binding.canvasView.invalidateContent()
        scheduleThumbRefresh(120)
    }

    // ---------------------------------------------------------------- 종료 확인

    private fun confirmExit() {
        val recording = ScreenRecordService.isRecording
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_title)
            .setMessage(
                if (recording) getString(R.string.exit_message_recording)
                else getString(R.string.exit_message)
            )
            .setNegativeButton(R.string.exit_cancel, null)
            .setPositiveButton(R.string.exit_ok) { _, _ -> exitNow(recording) }
            .show()
    }

    private fun exitNow(recording: Boolean) {
        if (!recording) {
            finish()
            return
        }
        // 녹화 파일을 마무리할 시간을 준 뒤 앱을 닫는다.
        ScreenRecordService.stop(this)
        ui.postDelayed({ finish() }, 600L)
    }

    // ---------------------------------------------------------------- 페이지

    private fun makePage(
        layout: NoteLayout,
        style: BackgroundStyle,
        pdfIndex: Int = -1,
        image: Bitmap? = null,
        aspect: Float = 0f,
        sourceId: String = "",
        id: Long = Page.nextPageId()
    ): Page {
        val page = Page(layout, style, pdfIndex, image, aspect, sourceId, id)
        page.applyContentLayout(Prefs.noteAlignEnum(), Prefs.noteMargin)
        return page
    }

    /** 모든 페이지·필기·불러온 PDF를 버리고 빈 노트 한 장으로 시작한다. */
    private fun startBlankLecture(style: BackgroundStyle) {
        closeAllPdfs()
        backgroundCache.clear()
        lecture.pages.clear()
        thumbBitmaps.clear()
        dirtyPages.clear()
        lecture.style = style
        lecture.pages.add(makePage(Prefs.noteLayoutEnum(), style))
        lecture.index = 0
        rebuildThumbs()
        showPage()
    }

    /**
     * 새 노트(전체 비우기). 수업이 바뀌었을 때 한 번에 비운다.
     * 실수로 누를 수 있으니 확인 팝업을 거친다.
     */
    private fun confirmNewNote() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_note_title)
            .setMessage(getString(R.string.new_note_message, lecture.pages.size))
            .setNegativeButton(R.string.discard_cancel, null)
            .setPositiveButton(R.string.new_note_ok) { _, _ -> startNewNote() }
            .show()
    }

    /** 확인 팝업에서 '비우기'를 눌렀을 때 (테스트에서도 같은 경로를 부른다) */
    internal fun startNewNote() {
        startBlankLecture(BackgroundStyle.WHITE)
        toast(getString(R.string.new_note_done))
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
        // 페이지를 옮기기 전에 입력 중인 글자는 지금 페이지에 확정한다.
        commitInlineText()
        clearStickyFocus()
        val page = lecture.currentPage
        if (page == null) {
            binding.canvasView.setPage(null, null)
            binding.stickyLayer.removeAllViews()
            noteViews.clear()
            updatePageInfo()
            updateUndoRedo()
            return
        }
        val ready = backgroundCache.getMemory(page.backgroundKey)
            ?: page.renderedBackground?.takeIf { !it.isRecycled }
        binding.canvasView.setPage(page, displayBackground(page, ready))
        bindNotes(page)
        updatePageInfo()
        updateUndoRedo()
        loadBackground(page)
        prefetchAdjacent(lecture.index)
        releaseBackgrounds(page)
        updateThumbSelection()
        scheduleThumbRefresh(200)
    }

    /**
     * 메모리에는 지금 보는 페이지만 남긴다.
     * 캐시에서 밀려난 배경을 페이지가 계속 붙들고 있지 않도록 참조를 놓아준다.
     */
    private fun releaseBackgrounds(keep: Page) {
        val inMemory = backgroundCache.memoryKeys().toHashSet()
        for (p in lecture.pages) {
            if (p === keep) continue
            if (p.backgroundKey !in inMemory) p.renderedBackground = null
        }
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

    /**
     * 새 페이지들을 지금 노트 아래(뒤)에 이어 붙이고, 방금 붙인 첫 페이지로 이동한다.
     * 맨 처음 뜨는 빈 노트(내용·필기·포스트잇 없음)뿐이면 그 빈 장은 지우고 새 페이지로 시작한다.
     * @return 새 페이지가 시작된 위치(0부터)
     */
    private fun appendPages(pages: List<Page>): Int {
        if (pages.isEmpty()) return lecture.index
        if (isLectureEmpty()) {
            lecture.pages.clear()
            thumbBitmaps.clear()
            dirtyPages.clear()
        }
        val start = lecture.pages.size
        lecture.pages.addAll(pages)
        for (page in pages) dirtyPages.add(page)
        lecture.index = start
        rebuildThumbs()
        showPage()
        return start
    }

    /** 지금 노트가 아직 아무것도 안 쓴 빈 상태인지 */
    private fun isLectureEmpty(): Boolean =
        lecture.pages.all { !it.hasContent && it.elements.isEmpty() && it.notes.isEmpty() }

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
                backgroundCache.evict(page.backgroundKey)
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
        binding.canvasView.invalidateContent()
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

    // ---------------------------------------------------------------- 답안 가리기 (3.6)

    /**
     * '답안 가리기' 켜고 끄기.
     * 켜면 지금 보는 페이지 배경의 하늘색 답안·해설만 흰색으로 지운 사본을 화면에 올린다.
     * 원본 배경과 캐시 파일은 건드리지 않으므로, 다시 끄면 원래 답안이 그대로 돌아온다.
     */
    private fun setAnswerHidden(hidden: Boolean) {
        if (answerHidden == hidden) return
        answerHidden = hidden
        Prefs.answerHidden = hidden
        answerBackgrounds.clear()
        refreshAnswerHideButton()
        val page = lecture.currentPage
        if (page != null) {
            val source = backgroundCache.getMemory(page.backgroundKey)
                ?: page.renderedBackground?.takeIf { !it.isRecycled }
            if (source != null) {
                binding.canvasView.setPageBackground(displayBackground(page, source))
            } else if (page.hasContent) {
                // 배경이 아직 올라오지 않은 페이지 — 원본부터 다시 불러와 그 사본을 올린다.
                loadBackground(page)
            }
            dirtyPages.addAll(lecture.pages)
            scheduleThumbRefresh(0)
        }
        toast(getString(if (hidden) R.string.answer_hide_on_hint else R.string.answer_hide_off_hint))
    }

    /** 상단 '답안 가리기' 버튼의 켜짐 상태를 보여 준다. */
    private fun refreshAnswerHideButton() {
        val on = ContextCompat.getColor(this, R.color.tool_text_selected)
        val off = ContextCompat.getColor(this, R.color.tool_text)
        binding.btnAnswerHide.setTextColor(if (answerHidden) on else off)
        binding.btnAnswerHide.backgroundTintList =
            ColorStateList.valueOf(if (answerHidden) 0x33FFFFFF else 0x00000000)
    }

    /**
     * 화면에 올릴 배경. '답안 가리기'가 꺼져 있으면 원본 그대로, 켜져 있으면
     * 하늘색 답안을 흰색으로 지운 사본을 준다(페이지마다 한 번만 만들어 둔다).
     */
    private fun displayBackground(page: Page, source: Bitmap?): Bitmap? {
        if (source == null || !answerHidden) return source
        val key = page.backgroundKey
        answerBackgrounds[key]?.takeIf { !it.isRecycled }?.let { return it }
        val filtered = try {
            AnswerHider.hide(source)
        } catch (e: OutOfMemoryError) {
            return source
        } catch (e: Exception) {
            return source
        }
        // 페이지 수만큼 쌓이지 않도록 두 장만 기억한다(나머지는 GC 가 회수).
        if (answerBackgrounds.size >= 2) answerBackgrounds.clear()
        answerBackgrounds[key] = filtered
        return filtered
    }

    /**
     * 지금 보는 페이지의 배경을 준비한다.
     * 메모리 → 캐시 파일 → 원본 PDF 순으로 찾고, 새로 그린 배경은 캐시 파일로 남긴다.
     */
    private fun loadBackground(page: Page) {
        val key = page.backgroundKey
        if (!page.hasContent) {
            page.renderedBackground = null
            if (lecture.currentPage === page) binding.canvasView.setPageBackground(null)
            return
        }
        val inMemory = backgroundCache.getMemory(key)
        if (inMemory != null) {
            page.renderedBackground = inMemory
            if (lecture.currentPage === page) {
                binding.canvasView.setPageBackground(displayBackground(page, inMemory))
            }
            return
        }
        val alreadyLoading = synchronized(pendingBackgrounds) { !pendingBackgrounds.add(key) }
        if (alreadyLoading) return

        val targetWidth = page.backgroundPixelWidth()
        io.execute {
            var bitmap = backgroundCache.load(key)
            if (bitmap == null) {
                // 캐시 파일이 없으면 원본에서 그리고, 다음부터는 파일로 읽도록 저장한다.
                bitmap = renderFromSource(page, targetWidth)
                if (bitmap != null) backgroundCache.save(key, bitmap, jpeg = false)
            }
            synchronized(pendingBackgrounds) { pendingBackgrounds.remove(key) }
            if (bitmap != null) backgroundCache.putMemory(key, bitmap)
            ui.post {
                if (bitmap == null) {
                    if (lecture.currentPage === page) {
                        toast(getString(R.string.pdf_page_render_failed))
                    }
                    return@post
                }
                page.renderedBackground = bitmap
                // 새로 그린 배경이므로 예전 '답안 가리기' 사본은 버린다.
                answerBackgrounds.remove(page.backgroundKey)
                if (lecture.currentPage === page) {
                    binding.canvasView.setPageBackground(displayBackground(page, bitmap))
                }
                releaseBackgrounds(page)
                dirtyPages.add(page)
                scheduleThumbRefresh(120)
                trimOpenDocs(page.sourceId)
            }
        }
    }

    /**
     * 지금 보는 페이지의 앞뒤 페이지 배경을 미리 캐시 파일로 만들어 둔다.
     * 수업 중 다음 장으로 넘길 때 기다리지 않도록 하되, 메모리가 아니라 파일에만 남긴다.
     */
    private fun prefetchAdjacent(index: Int) {
        val targets = listOf(index - 1, index + 1)
            .mapNotNull { lecture.pages.getOrNull(it) }
            .filter { it.hasContent }
        if (targets.isEmpty()) return
        io.execute {
            for (page in targets) {
                // 그새 다른 장으로 넘어갔으면 지금 필요 없는 배경은 그리지 않는다.
                if (lecture.index != index) return@execute
                val key = page.backgroundKey
                if (backgroundCache.hasFile(key) || backgroundCache.getMemory(key) != null) continue
                if (synchronized(pendingBackgrounds) { !pendingBackgrounds.add(key) }) continue
                try {
                    val bitmap = renderFromSource(page, page.backgroundPixelWidth()) ?: continue
                    val saved = backgroundCache.save(key, bitmap, jpeg = page.pdfPageIndex < 0)
                    bitmap.recycle()
                    // 미리 만들던 페이지로 사용자가 먼저 넘어왔으면 지금 화면에 올려 준다.
                    if (saved) ui.post { if (lecture.currentPage === page) loadBackground(page) }
                } finally {
                    synchronized(pendingBackgrounds) { pendingBackgrounds.remove(key) }
                }
            }
        }
    }

    /** 이 페이지가 온 PDF·이미지 원본에서 배경을 다시 그린다(필요하면 파일에서 다시 연다). */
    private fun renderFromSource(page: Page, targetWidth: Int): Bitmap? {
        if (page.pdfPageIndex < 0) {
            // 이미지 페이지: 원본 사진을 다시 읽는다(캐시 파일이 디스크 정리로 지워졌을 때).
            val uri = synchronized(imageUris) { imageUris[page.sourceId] } ?: return null
            return decodeBitmap(uri)
        }
        val source = sourceFor(page) ?: return null
        return try {
            source.render(page.pdfPageIndex, targetWidth)
                ?: source.render(page.pdfPageIndex, targetWidth / 2)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun sourceFor(page: Page): PdfPageSource? {
        synchronized(pdfDocs) {
            val open = pdfDocs[page.sourceId]
            if (open != null && open.isOpen) return open
        }
        val uri = synchronized(pdfDocs) { pdfUris[page.sourceId] } ?: return null
        // 오래 전에 닫아 둔 문서는 처음 고른 파일에서 다시 연다.
        val provider = PdfPageProvider(this, uri)
        if (!provider.isOpen || provider.pageCount <= 0) {
            provider.close()
            return null
        }
        synchronized(pdfDocs) { pdfDocs[page.sourceId] = provider }
        return provider
    }

    private fun registerDoc(sourceId: String, provider: PdfPageSource, uri: Uri?) {
        synchronized(pdfDocs) {
            pdfDocs[sourceId] = provider
            if (uri != null) pdfUris[sourceId] = uri
        }
    }

    /**
     * 열어 둔 PDF 렌더러가 너무 많아지면, 배경을 모두 캐시 파일로 남긴 문서부터 닫는다.
     * (다시 필요하면 [sourceFor] 가 처음 고른 파일에서 다시 연다.)
     */
    private fun trimOpenDocs(keepSourceId: String) {
        synchronized(pdfDocs) {
            if (pdfDocs.size <= MAX_OPEN_DOCS) return
            val targets = pdfDocs.keys.filter { it != keepSourceId && isDocFullyCached(it) }
            for (id in targets) {
                if (pdfDocs.size <= MAX_OPEN_DOCS) break
                val source = pdfDocs.remove(id) ?: continue
                try {
                    source.close()
                } catch (e: Exception) {
                    // 무시
                }
            }
        }
    }

    private fun isDocFullyCached(sourceId: String): Boolean {
        val pages = lecture.pages.filter { it.sourceId == sourceId }
        return pages.isNotEmpty() && pages.all { backgroundCache.hasFile(it.backgroundKey) }
    }

    private fun backgroundForThumb(page: Page): Bitmap? {
        if (!page.hasContent) return null
        page.renderedBackground?.takeIf { !it.isRecycled }?.let { return displayBackground(page, it) }
        backgroundCache.load(page.backgroundKey, THUMB_WIDTH * 2)?.let { small ->
            if (!answerHidden) return small
            return try {
                AnswerHider.hide(small)
            } catch (e: Exception) {
                small
            }
        }
        val rendered = renderFromSource(page, 360) ?: return null
        if (answerHidden) {
            // 방금 그린 비트맵이라 어디에도 공유되지 않는다 — 그 자리에서 지운다.
            try {
                AnswerHider.hideInPlace(rendered)
            } catch (e: Exception) {
                // 무시
            }
        }
        return rendered
    }

    /**
     * 내보내기용 배경. 캐시 파일에서 새로 읽어 준다(메모리에 있는 것을 빌려 쓰지 않는다).
     * 부른 쪽에서 다 쓴 뒤 recycle 해야 한다.
     */
    private fun backgroundForExport(page: Page): Bitmap? {
        if (!page.hasContent) return null
        val source = backgroundCache.load(page.backgroundKey)
            ?: renderFromSource(page, page.backgroundPixelWidth())
            ?: return null
        if (!answerHidden) return source
        // 저장하는 파일도 화면과 같은 상태로 만든다 — '답안 가리기'가 켜져 있으면 하늘색 답안을 지운
        // 사본을 준다. 캐시 파일에서 읽은 배경은 수정할 수 없는(immutable) 비트맵이라
        // [AnswerHider.hide] 로 수정 가능한 사본을 만들고 원본은 여기서 놓아준다.
        return try {
            val hidden = AnswerHider.hide(source)
            if (!source.isRecycled) source.recycle()
            hidden
        } catch (e: OutOfMemoryError) {
            // 지우지 못하면 원본을 그대로 내보낸다(화면만 가리는 것은 그대로 유지).
            source
        } catch (e: Exception) {
            source
        }
    }

    private fun closeAllPdfs() {
        val snapshot: List<PdfPageSource>
        synchronized(pdfDocs) {
            snapshot = ArrayList(pdfDocs.values)
            pdfDocs.clear()
            pdfUris.clear()
            synchronized(imageUris) { imageUris.clear() }
        }
        for (source in snapshot) {
            try {
                source.close()
            } catch (e: Exception) {
                // 무시
            }
        }
    }

    // ---------------------------------------------------------------- PDF / 이미지 열기

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 이어 쌓은 뒤 배경을 다시 그릴 때(회전 등) 같은 파일을 다시 열 수 있도록 권한을 남긴다.
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // 남길 수 없는 문서 제공자도 있다 — 이번 실행 동안은 그대로 읽을 수 있다.
            }
            openPdf(uri)
        }
    }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openImage(uri)
        }

    /**
     * PDF를 고르면 바로 열지 않고, 총 페이지 수를 보여준 뒤
     * 열 페이지 범위와 회전을 정하게 한다.
     */
    private fun openPdf(uri: Uri) {
        val provider = PdfPageProvider(this, uri)
        if (!provider.isOpen || provider.pageCount <= 0) {
            provider.close()
            toast(getString(R.string.pdf_open_failed))
            return
        }
        showPdfOpenDialog(provider, uri, rangeText = "1-${provider.pageCount}", rotation = 0)
    }

    private fun showPdfOpenDialog(
        provider: PdfPageSource,
        uri: Uri,
        rangeText: String,
        rotation: Int
    ) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }

        val total = TextView(this).apply {
            text = getString(R.string.pdf_total_pages, provider.pageCount)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tool_text))
            textSize = 14f
        }
        container.addView(total)

        val rangeLabel = TextView(this).apply {
            text = getString(R.string.pdf_range_label)
            setPadding(0, (12 * density).toInt(), 0, (2 * density).toInt())
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tool_text_selected))
            textSize = 13f
        }
        container.addView(rangeLabel)

        val rangeInput = EditText(this).apply {
            setText(rangeText)
            setSelection(text.length)
            hint = getString(R.string.pdf_range_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
        }
        container.addView(rangeInput)

        val rangeDesc = TextView(this).apply {
            text = getString(R.string.pdf_range_desc)
            setTextColor(0x99FFFFFF.toInt())
            textSize = 11.5f
            setPadding(0, (2 * density).toInt(), 0, (10 * density).toInt())
        }
        container.addView(rangeDesc)

        val rotateLabel = TextView(this).apply {
            text = getString(R.string.pdf_rotation)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tool_text_selected))
            textSize = 13f
            setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
        }
        container.addView(rotateLabel)

        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rotationLabels = arrayOf("0°", "90°", "180°", "270°")
        for ((i, label) in rotationLabels.withIndex()) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tool_text))
                tag = ROTATIONS[i]
                isChecked = ROTATIONS[i] == rotation
            }
            val lp = RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (12 * density).toInt()
            group.addView(rb, lp)
        }
        container.addView(group)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_open_title)
            .setView(container)
            .setNegativeButton(R.string.discard_cancel) { _, _ -> provider.close() }
            .setNeutralButton(R.string.pdf_open_all, null)
            .setPositiveButton(R.string.pdf_open_ok, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                rangeInput.setText("1-${provider.pageCount}")
                rangeInput.setSelection(rangeInput.text.length)
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pages = PageRange.parse(rangeInput.text.toString(), provider.pageCount)
                if (pages.isNullOrEmpty()) {
                    toast(getString(R.string.pdf_range_invalid))
                    return@setOnClickListener
                }
                val checked = group.checkedRadioButtonId
                val selectedRotation =
                    if (checked != View.NO_ID) (group.findViewById<RadioButton>(checked)?.tag as? Int ?: 0)
                    else 0
                dialog.dismiss()
                openPdfPages(provider, pages, selectedRotation, uri)
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                provider.close()
            }
        }
        dialog.show()
    }

    /**
     * 고른 페이지들을 지금 노트 뒤에 이어서 올린다.
     * (테스트에서도 같은 경로를 부를 수 있게 internal)
     */
    internal fun openPdfPages(
        provider: PdfPageSource,
        pages: List<Int>,
        rotation: Int,
        uri: Uri? = null
    ) {
        // 입력한 번호는 1부터 시작하는 '페이지 번호'이므로 0부터 시작하는 렌더러 인덱스로 바꾼다.
        // (이 변환이 빠지면 1페이지짜리 PDF는 모든 페이지가 범위 밖이 되어 흰 화면만 나온다.)
        val docId = "doc${++docSeq}"
        val built = PdfPages.buildPages(
            provider,
            pages,
            rotation,
            Prefs.noteLayoutEnum(),
            Prefs.noteAlignEnum(),
            Prefs.noteMargin,
            lecture.style,
            docId
        )
        if (built.isEmpty()) {
            provider.close()
            toast(getString(R.string.pdf_range_invalid))
            return
        }
        registerDoc(docId, provider, uri)
        appendPages(built)
        toast(getString(R.string.pdf_appended_pages, built.size, lecture.pages.size))
    }

    /** 테스트용: 지금 열려 있는 강의 페이지 목록 */
    internal fun pdfPagesForTest(): List<Page> = ArrayList(lecture.pages)

    /** 테스트용: 지금 보고 있는 페이지 번호(0부터) */
    internal fun currentPageIndexForTest(): Int = lecture.index

    /** 테스트용: 페이지 배경 캐시 */
    internal fun backgroundCacheForTest(): PageBackgroundCache = backgroundCache

    /** 테스트용: '답안 가리기'가 켜져 있는지 */
    internal fun answerHiddenForTest(): Boolean = answerHidden

    /** 테스트용: 지금 설정으로 이 배경이 화면에 어떻게 올려지는지 */
    internal fun displayBackgroundForTest(page: Page, source: Bitmap?): Bitmap? =
        displayBackground(page, source)

    /** 테스트용: 내보내기(PNG/PDF)에 쓰는 배경 */
    internal fun backgroundForExportForTest(page: Page): Bitmap? = backgroundForExport(page)

    /** 테스트용: 페이지 이동 */
    internal fun goToPageForTest(index: Int) = goToPage(index)

    /** 테스트용: 지금 떠 있는 글자 인라인 입력창(없으면 null) */
    internal fun inlineTextEditorForTest(): EditText? = inlineText?.editor

    /** 테스트용: 지금 새로 그리는 떡메모지의 테두리 선택 상태 */
    internal fun coverBorderedForTest(): Boolean = coverBordered

    /** 테스트용: 입력 중인 글자를 확정 */
    internal fun commitInlineTextForTest() = commitInlineText()

    /** 테스트용: 캔버스 바깥을 눌렀을 때와 같은 입력 정리 */
    internal fun clearInputsForTest() {
        commitInlineText()
        clearStickyFocus()
    }

    /** 상단 메뉴에서 PDF 페이지를 90도 단위로 돌린다. */
    private fun showRotationDialog() {
        if (lecture.pages.none { it.pdfPageIndex >= 0 }) {
            toast(getString(R.string.pdf_rotate_no_pdf))
            return
        }
        val labels = arrayOf(
            getString(R.string.rotate_0),
            getString(R.string.rotate_90),
            getString(R.string.rotate_180),
            getString(R.string.rotate_270)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_pdf_rotate)
            .setItems(labels) { _, which ->
                rotateAllPdfPages(ROTATIONS.getOrElse(which) { 0 })
            }
            .show()
    }

    private fun rotateAllPdfPages(degrees: Int) {
        val targets = lecture.pages.filter { it.pdfPageIndex >= 0 }
        if (targets.isEmpty()) {
            toast(getString(R.string.pdf_rotate_no_pdf))
            return
        }
        for (page in targets) {
            page.setRotation(degrees)
            page.invalidateBackground()
            // 회전하면 배경을 그리는 크기·방향이 달라지므로 캐시 파일도 버리고 다시 그린다.
            backgroundCache.evict(page.backgroundKey)
            page.applyContentLayout(Prefs.noteAlignEnum(), Prefs.noteMargin)
            dirtyPages.add(page)
        }
        rebuildThumbs()
        showPage()
    }

    /**
     * 이미지를 노트 뒤에 이어 붙인다. 사진은 메모리에 들고 있지 않고
     * 캐시 폴더에 JPEG 로 저장한 뒤, 볼 때만 읽어 온다.
     */
    private fun openImage(uri: Uri) {
        val pageId = Page.nextPageId()
        val sourceId = "img${++docSeq}"
        synchronized(imageUris) { imageUris[sourceId] = uri }
        io.execute {
            val bitmap = decodeBitmap(uri)
            if (bitmap == null) {
                ui.post { toast(getString(R.string.image_open_failed)) }
                return@execute
            }
            val aspect = bitmap.width.toFloat() /
                bitmap.height.toFloat().coerceAtLeast(1f)
            val key = Page.backgroundKeyFor(pageId)
            val saved = backgroundCache.save(key, bitmap, jpeg = true)
            bitmap.recycle()
            ui.post {
                if (!saved) {
                    synchronized(imageUris) { imageUris.remove(sourceId) }
                    toast(getString(R.string.image_open_failed))
                    return@post
                }
                val page = makePage(
                    Prefs.noteLayoutEnum(),
                    lecture.style,
                    -1,
                    null,
                    aspect.coerceIn(0.05f, 20f),
                    sourceId,
                    pageId
                )
                appendPages(listOf(page))
                toast(getString(R.string.image_appended_pages, lecture.index + 1))
            }
        }
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
            var background: Bitmap? = null
            val saved: String?
            try {
                background = backgroundForExport(page)
                val bitmap = PageExporter.renderPage(page, background)
                saved = PageExporter.savePng(
                    this,
                    bitmap,
                    "HunsuNote_${PageExporter.timestamp()}_p$pageNumber"
                )
                bitmap.recycle()
            } finally {
                background?.let { if (!it.isRecycled) it.recycle() }
            }
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
            val saved = PageExporter.exportPdf(
                this,
                pages,
                { page -> backgroundForExport(page) },
                releaseBackground = { bitmap ->
                    if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                }
            )
            ui.post {
                if (saved != null) toast(getString(R.string.saved_to_downloads, saved))
                else toast(getString(R.string.save_failed))
            }
        }
    }

    // ---------------------------------------------------------------- 포스트잇

    /**
     * 포스트잇 도구로 캔버스에 그린 사각형 자리에 포스트잇을 놓는다.
     * 탭만 했으면(너무 작으면) 누른 자리에 기본 크기로 놓는다.
     */
    private fun addStickyNote(x0: Float, y0: Float, x1: Float, y1: Float) {
        val page = lecture.currentPage ?: return
        val note = StickyGeometry.fromDrag(x0, y0, x1, y1)
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
        // 3.5: 다른 곳을 누르면 커서(깜박임)를 닫고, 포스트잇을 다시 누르면 이어서 입력한다.
        edit.setOnClickListener {
            edit.requestFocus()
            showKeyboard(edit)
        }
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideKeyboard(edit)
        }
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
        // 확대·이동을 해도 글자 입력창이 캔버스의 같은 지점에 남도록 함께 옮긴다.
        inlineText?.updateTransform()
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

    // ---------------------------------------------------------------- 글자

    /**
     * 3.5 글자 도구: 팝업 대신 누른 자리에 투명 입력창이 바로 뜨고 키보드가 올라온다.
     * 치는 글자가 그 자리에서 바로 보이고, 바깥을 누르면 그 위치에 글자 요소로 확정된다.
     */
    private fun startInlineText(x: Float, y: Float) {
        val page = lecture.currentPage ?: return
        clearStickyFocus()
        inlineTextPage = page
        inlineText?.start(x, y, penColor, textSize)
    }

    /** 입력 중인 글자를 지금 페이지에 확정한다(비어 있으면 아무것도 넣지 않는다). */
    private fun commitInlineText() {
        val editor = inlineText ?: return
        if (!editor.isActive) {
            inlineTextPage = null
            return
        }
        editor.commit()
    }

    /** 글자 요소를 지금 색·크기로 페이지에 넣는다. */
    private fun addTextElement(page: Page, x: Float, y: Float, text: String) {
        page.pushUndo()
        page.elements.add(
            DrawElement(
                ElementKind.TEXT,
                penColor,
                textSize,
                arrayListOf(Pt(x, y)),
                ArrayList(),
                text
            )
        )
        binding.canvasView.invalidateContent()
        dirtyPages.add(page)
        updateUndoRedo()
        scheduleThumbRefresh(500)
    }

    // ---------------------------------------------------------------- 덮개 테두리 (3.5)

    private fun setupCoverBorderChips() {
        binding.btnCoverBorderOn.setOnClickListener { setCoverBordered(true) }
        binding.btnCoverBorderOff.setOnClickListener { setCoverBordered(false) }
        binding.canvasView.coverBorder = coverBordered
        refreshCoverBorderChips()
    }

    /** 새로 그릴 덮개 떡메모지에 테두리를 둘지 고른다. */
    private fun setCoverBordered(bordered: Boolean) {
        coverBordered = bordered
        Prefs.coverBorder = bordered
        binding.canvasView.coverBorder = bordered
        refreshCoverBorderChips()
    }

    private fun refreshCoverBorderChips() {
        val on = ContextCompat.getColor(this, R.color.tool_text_selected)
        val off = ContextCompat.getColor(this, R.color.tool_text)
        val density = resources.displayMetrics.density
        for ((chip, selected) in listOf(
            binding.btnCoverBorderOn to coverBordered,
            binding.btnCoverBorderOff to !coverBordered
        )) {
            chip.setTextColor(if (selected) on else off)
            chip.background = GradientDrawable().apply {
                cornerRadius = 5 * density
                setColor(if (selected) 0x33FFFFFF else 0x00000000)
            }
        }
    }

    // ---------------------------------------------------------------- 포스트잇 커서

    /**
     * 캔버스 바깥을 누르면 포스트잇 입력 커서(깜박임)를 닫는다.
     * 글자는 그대로 남고, 포스트잇을 다시 누르면 그 자리에서 이어 쓸 수 있다.
     */
    private fun clearStickyFocus() {
        for (view in noteViews.values) {
            val edit = view.findViewById<EditText>(R.id.stickyText) ?: continue
            if (edit.hasFocus()) {
                edit.clearFocus()
                hideKeyboard(edit)
            }
        }
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
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

    /** 상태바·내비바를 숨긴다(전체화면). 스와이프로 잠깐 꺼내는 것만 허용. */
    private fun applyImmersive() {
        val controller: WindowInsetsControllerCompat =
            WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
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
                // 녹화는 항상 전체화면 한 가지 방식만 쓴다(영역 선택 없음).
                val settings = RecSettings(
                    Prefs.recordHeight,
                    Prefs.recordFps,
                    Prefs.recordBitrateKbps,
                    pendingMic
                )
                recStartMs = SystemClock.elapsedRealtime()
                ScreenRecordService.start(this, result.resultCode, data, settings)
                // 녹화를 시작해도 화면(전체화면·툴바)은 바꾸지 않는다.
                // 설정에서 '자동 숨김'을 켜 둔 경우에만 숨긴다.
                if (Prefs.autoHideToolbar) {
                    barsHiddenByRecording = true
                    setBarsVisible(false, false, false)
                }
            } else {
                toast(getString(R.string.recording_cancelled))
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            launchProjection()
        }

    /** 녹화 버튼: 누르면 곧바로 전체화면 녹화를 시작하고, 다시 누르면 멈춘다. */
    private fun onRecordClicked() {
        if (ScreenRecordService.isRecording) {
            ScreenRecordService.stop(this)
            return
        }
        startRecordingWithPermissions()
    }

    private fun startRecordingWithPermissions() {
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
            // 녹화 때문에 자동으로 숨긴 바는 녹화가 끝나면 돌려놓는다.
            if (barsHiddenByRecording) {
                barsHiddenByRecording = false
                setBarsVisible(true, true, true)
            }
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

    /** 뒤로가기: 입력 중이면 먼저 확정해서 글자를 잃지 않게 한다. */
    private fun onBackPressedWithInputs() {
        if (inlineText?.isActive == true) {
            commitInlineText()
            return
        }
        confirmExit()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
