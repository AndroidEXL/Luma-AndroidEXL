package com.example.animatedsplash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.roundToInt
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.addTextChangedListener
import com.example.animatedsplash.databinding.ActivityIdeBinding
import com.example.avrandroid.AvrBoard
import com.example.avrandroid.AvrCompileRequest
import com.example.avrandroid.AvrCompileResult
import com.example.avrandroid.AvrCompiler
import com.example.avrandroid.AvrdudeCommandRequest
import com.example.avrandroid.AvrReadRequest
import com.example.avrandroid.AvrdudeUsbBridge
import com.example.avrandroid.AvrUploadRequest
import com.example.avrandroid.AvrToolchainReset
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

private data class OpenFileTab(
    val fileName: String,
    var code: String,
    val backingFile: File?
)

private data class PendingAdvancedAvrdudeRun(
    val options: AdvancedAvrdudeOptions,
    val inputFile: File? = null
)

class IdeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIdeBinding
    private lateinit var inoFileStore: InoFileStore
    private lateinit var backupStore: ProjectBackupStore
    private lateinit var avrCompiler: AvrCompiler
    private lateinit var avrdudeBridge: AvrdudeUsbBridge
    private lateinit var buildHistoryStore: BuildHistoryStore
    private lateinit var project: Project
    private var selectedBoard = AvrBoard.UNO
    private var selectedUsbDevice: UsbDevice? = null
    private var savedPortName: String? = null
    private var isHistoryChange = false
    private var lastRecordedCode = ""
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var nextMenuId = 100
    private val openFileTabs = mutableListOf<OpenFileTab>()
    private var activeFileTab = 0
    private var suppressTabPersistence = false
    private var saveAutoEnabled = LumaApplication.DEFAULT_SAVE_AUTO_ENABLED
    private var libraryExampleGroups: Map<String, List<Pair<String, String>>> = emptyMap()
    private var lastCompileResult: AvrCompileResult? = null
    private var lastBuildStartedAt = 0L
    private var lastBuildDurationMs = 0L
    private var lastBuildLibraryDirectories: List<String> = emptyList()
    private var lastBuildCommand = ""
    private var suggestionWindow: PopupWindow? = null
    private var wordWrapEnabled = true
    private val backupHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val delayedBackup = Runnable { saveActiveFileTab() }
    private var usbDialog: androidx.appcompat.app.AlertDialog? = null
    private var permissionRequestInFlight = false
    private var pendingUploadAfterPermission = false
    private var pendingFirmwareReadAfterPermission = false
    private var pendingFirmwareZipFile: File? = null
    private var pendingCustomAvrdudeCommand: CustomAvrdudeCommand? = null
    private var pendingAvrdudeCommandZipFile: File? = null
    private var pendingHexExportFile: File? = null
    private var pendingAdvancedAvrdudeRun: PendingAdvancedAvrdudeRun? = null
    private var pendingAdvancedAvrdudeInputOptions: AdvancedAvrdudeOptions? = null
    private var pendingAdvancedAvrdudeZipFile: File? = null
    private var pendingArduinoReportText: String? = null
    private var pendingBoardDataAfterPermission = false
    private var pendingBoardDataZipFile: File? = null
    private var pendingBoardConnectionTest = false
    private var pendingDiagnosticZipFile: File? = null
    private val usbPermissionAction = "com.example.animatedsplash.USB_PERMISSION"
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == usbPermissionAction) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                permissionRequestInFlight = false
                if (granted) {
                    setStatus("USB permission granted")
                    if (pendingUploadAfterPermission) {
                        pendingUploadAfterPermission = false
                        binding.root.post { if (!isFinishing) uploadCode() }
                    }
                    if (pendingFirmwareReadAfterPermission) {
                        pendingFirmwareReadAfterPermission = false
                        binding.root.post { if (!isFinishing) extractFirmware() }
                    }
                    pendingCustomAvrdudeCommand?.let { command ->
                        pendingCustomAvrdudeCommand = null
                        binding.root.post { if (!isFinishing) runCustomAvrdudeCommand(command) }
                    }
                    pendingAdvancedAvrdudeRun?.let { run ->
                        pendingAdvancedAvrdudeRun = null
                        binding.root.post { if (!isFinishing) runAdvancedAvrdude(run) }
                    }
                    if (pendingBoardDataAfterPermission) {
                        pendingBoardDataAfterPermission = false
                        binding.root.post { if (!isFinishing) extractCurrentBoardData() }
                    }
                    if (pendingBoardConnectionTest) {
                        pendingBoardConnectionTest = false
                        binding.root.post { if (!isFinishing) testBoardConnection() }
                    }
                } else {
                    pendingUploadAfterPermission = false
                    pendingFirmwareReadAfterPermission = false
                    pendingCustomAvrdudeCommand = null
                    pendingAdvancedAvrdudeRun = null
                    pendingBoardDataAfterPermission = false
                    pendingBoardConnectionTest = false
                    setStatus("USB permission denied")
                }
            }
        }
    }

    private val importCodeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importCode(it) }
    }

    private val importLibraryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            setStatus(getString(R.string.ide_ready), "Library: ${uri.lastPathSegment ?: "selected"}")
        }
    }

    private val exportCodeLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) exportCode(uri)
    }

    private val exportFirmwareZipLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) saveFirmwareZipTo(uri)
    }

    private val exportAvrdudeCommandZipLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) saveAvrdudeCommandZipTo(uri)
    }

    private val exportHexLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) saveHexTo(uri)
    }

    private val importAdvancedAvrdudeInputLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val options = pendingAdvancedAvrdudeInputOptions
        pendingAdvancedAvrdudeInputOptions = null
        if (uri != null && options != null) copyAdvancedInputAndRun(uri, options)
    }

    private val exportAdvancedAvrdudeZipLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) saveAdvancedAvrdudeZipTo(uri)
    }

    private val exportArduinoReportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val report = pendingArduinoReportText
        pendingArduinoReportText = null
        if (uri != null && report != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { output -> output.write(report.toByteArray(Charsets.UTF_8)) }
                    ?: error("Unable to open selected destination")
            }.onSuccess {
                Toast.makeText(this, R.string.ide_arduino_report_saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, R.string.ide_arduino_report_save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val exportBoardDataZipLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) saveBoardDataZipTo(uri)
    }

    private val exportDiagnosticZipLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) saveDiagnosticZipTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityIdeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
                .getBoolean(LumaApplication.KEEP_SCREEN_ON_KEY, false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        saveAutoEnabled = getSharedPreferences(
            LumaApplication.PREFERENCES_NAME,
            MODE_PRIVATE
        ).getBoolean(LumaApplication.SAVE_AUTO_KEY, LumaApplication.DEFAULT_SAVE_AUTO_ENABLED)
        project = Project(
            id = intent.getLongExtra(EXTRA_PROJECT_ID, System.currentTimeMillis()),
            name = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: "Arduino Project",
            type = ProjectType.fromKey(intent.getStringExtra(EXTRA_PROJECT_TYPE).orEmpty()),
            iconKey = intent.getStringExtra(EXTRA_PROJECT_ICON) ?: "spark",
            inoFileName = intent.getStringExtra(EXTRA_INO_FILE),
            createdAt = intent.getLongExtra(EXTRA_CREATED_AT, System.currentTimeMillis()),
            updatedAt = System.currentTimeMillis()
        )
        restoreProjectHardwareState()
        inoFileStore = InoFileStore(this)
        backupStore = ProjectBackupStore(this)
        avrCompiler = AvrCompiler(this)
        avrdudeBridge = AvrdudeUsbBridge(this)
        buildHistoryStore = BuildHistoryStore(this)
        setupEditor()
        libraryExampleGroups = ArduinoLibraryStorage.exampleGroups(this)
        setupMenus()
        bindHardwareKeyboardShortcuts()
        registerUsbReceiver()
        val preferences = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
        if (preferences.getInt(
                LumaApplication.TUTORIAL_FLOW_STAGE_KEY,
                LumaApplication.TUTORIAL_STAGE_PROJECT
            ) == LumaApplication.TUTORIAL_STAGE_IDE &&
            !preferences.getBoolean(LumaApplication.INTERACTIVE_GUIDE_COMPLETE_KEY, false)) {
            binding.ideRoot.postDelayed({ startIdeTutorial() }, 500L)
        }
    }

    private fun projectStatePreferences() = getSharedPreferences(
        LumaApplication.PROJECT_STATE_PREFERENCES,
        MODE_PRIVATE
    )

    private fun boardStateKey() = "project_${project.id}_board"
    private fun portStateKey() = "project_${project.id}_port"

    private fun restoreProjectHardwareState() {
        val preferences = projectStatePreferences()
        selectedBoard = preferences.getString(boardStateKey(), null)
            ?.let { value -> runCatching { AvrBoard.valueOf(value) }.getOrNull() }
            ?: AvrBoard.UNO
        savedPortName = preferences.getString(portStateKey(), null)
    }

    private fun persistProjectHardwareState() {
        projectStatePreferences().edit()
            .putString(boardStateKey(), selectedBoard.name)
            .apply {
                savedPortName?.let { putString(portStateKey(), it) }
            }
            .apply()
    }

    private fun setupEditor() {
        binding.ideProjectName.text = project.name
        val file = inoFileStore.ensureFile(project)
        binding.ideProjectFile.text = getString(R.string.ide_project_file, file.name)
        val code = inoFileStore.read(project)
        openFileTabs.clear()
        openFileTabs += OpenFileTab(file.name, code, file)
        activeFileTab = 0
        binding.codeEditor.applyCode(code)
        val savedFontSize = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .getFloat(LumaApplication.EDITOR_FONT_SIZE_KEY, LumaApplication.DEFAULT_EDITOR_FONT_SIZE)
        binding.codeEditor.textSize = savedFontSize
        wordWrapEnabled = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(EDITOR_WORD_WRAP_KEY, true)
        applyWordWrap()
        renderFileTabs()
        lastRecordedCode = code
        updateLineNumbers()

        binding.ideBackButton.setOnClickListener { finish() }
        binding.codeEditor.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.lineNumbers.update(
                binding.codeEditor.lineCount,
                binding.codeEditor.lineHeight,
                scrollY
            )
        }
        binding.codeEditor.addTextChangedListener(afterTextChanged = { editable ->
            val current = editable?.toString().orEmpty()
            if (!isHistoryChange && !suppressTabPersistence) {
                if (current != lastRecordedCode) {
                    undoStack.addLast(lastRecordedCode)
                    lastRecordedCode = current
                    redoStack.clear()
                }
                openFileTabs.getOrNull(activeFileTab)?.let { tab ->
                    tab.code = current
                    if (saveAutoEnabled) {
                        tab.backingFile?.writeText(current) ?: inoFileStore.write(project, current)
                        backupHandler.removeCallbacks(delayedBackup)
                        backupHandler.postDelayed(delayedBackup, 900L)
                    }
                }

            }
            updateLineNumbers()
        })
        binding.codeEditor.onVariablesChanged = { variables ->
            val value = variables.joinToString(", ").ifBlank { "—" }
            binding.variablesStatus.text = getString(R.string.ide_variables, value)
        }
        binding.codeEditor.onSuggestionsChanged = { suggestions ->
            renderSuggestions(suggestions)
        }
        binding.codeEditor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) suggestionWindow?.dismiss()
        }
        binding.codeEditor.onSelectionChanged = {
            if (binding.codeEditor.hasFocus()) {
                renderSuggestions(binding.codeEditor.completionCandidates(binding.codeEditor.currentWord()))
            }
        }
    }

    private fun updateLineNumbers() {
        binding.lineNumbers.update(
            binding.codeEditor.lineCount,
            binding.codeEditor.lineHeight,
            binding.codeEditor.scrollY
        )
    }

    private fun renderSuggestions(suggestions: List<CodeCompletion>) {
        val activeWord = binding.codeEditor.currentWord()
        if (suggestions.isEmpty() || activeWord.length < 2 || !binding.codeEditor.hasFocus()) {
            suggestionWindow?.dismiss()
            return
        }

        val popupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@IdeActivity, R.drawable.completion_popup)
            elevation = dp(18).toFloat()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val popupWidth = (resources.displayMetrics.widthPixels * 0.64f).roundToInt()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        suggestions.take(6).forEach { suggestion ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                setPadding(dp(4), dp(3), dp(4), dp(3))
                setOnClickListener {                 insertSuggestion(suggestion.label) }
            }

            val badge = TextView(this).apply {
                text = suggestion.kind
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.completion_badge_text))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ContextCompat.getColor(this@IdeActivity, R.color.completion_badge))
                    cornerRadius = dp(8).toFloat()
                }
            }
            val badgeWidth = (popupWidth * 0.18f).roundToInt()
            row.addView(badge, LinearLayout.LayoutParams(badgeWidth, dp(28)))

            val label = TextView(this).apply {
                text = suggestion.label
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.completion_label))
                setPadding(dp(12), 0, dp(8), 0)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            row.addView(label, LinearLayout.LayoutParams((popupWidth * 0.28f).roundToInt(), -1))

            val detail = TextView(this).apply {
                text = suggestion.detail
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.completion_detail))
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(detail, LinearLayout.LayoutParams(0, -1, 1f))
            list.addView(row, LinearLayout.LayoutParams(-1, dp(40)))
        }

        val scroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, ViewGroup.LayoutParams(-1, -2))
        }
        val rowHeight = dp(40)
        popupContent.addView(scroll, LinearLayout.LayoutParams(-1, minOf(rowHeight * 4, rowHeight * suggestions.size)))

        popupContent.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dp(190), View.MeasureSpec.AT_MOST)
        )
        val popupHeight = popupContent.measuredHeight
        val visibleFrame = Rect()
        binding.codeEditor.getWindowVisibleDisplayFrame(visibleFrame)
        val location = IntArray(2)
        binding.codeEditor.getLocationOnScreen(location)
        val editorLayout = binding.codeEditor.layout
        val cursor = binding.codeEditor.selectionStart.coerceAtLeast(0)
        val safeCursor = cursor.coerceAtMost(binding.codeEditor.length())
        val line = editorLayout?.getLineForOffset(safeCursor) ?: 0
        val lineBottom = editorLayout?.getLineBottom(line) ?: binding.codeEditor.lineHeight
        val cursorX = editorLayout?.getPrimaryHorizontal(safeCursor) ?: 0f
        val x = (location[0] + binding.codeEditor.paddingLeft + cursorX.toInt() - dp(28))
            .coerceIn(dp(10), resources.displayMetrics.widthPixels - popupWidth - dp(10))
        var y = location[1] + binding.codeEditor.paddingTop + lineBottom - binding.codeEditor.scrollY + dp(8)
        if (y + popupHeight > visibleFrame.bottom) {
            y = location[1] + binding.codeEditor.paddingTop + (editorLayout?.getLineTop(line) ?: 0) - binding.codeEditor.scrollY - popupHeight - dp(8)
        }
        y = y.coerceAtLeast(visibleFrame.top + dp(8))

        if (suggestionWindow == null) {
            suggestionWindow = PopupWindow(popupContent, popupWidth, popupHeight, false).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = true
                isFocusable = false
                elevation = dp(18).toFloat()
            }
        } else {
            suggestionWindow?.contentView = popupContent
            suggestionWindow?.width = popupWidth
            suggestionWindow?.height = popupHeight
        }
        suggestionWindow?.let { popup ->
            if (popup.isShowing) popup.update(x, y, popupWidth, popupHeight)
            else popup.showAtLocation(binding.codeEditor, Gravity.TOP or Gravity.START, x, y)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun insertSuggestion(value: String) {
        val editor = binding.codeEditor
        val prefix = editor.currentWord()
        val start = (editor.selectionStart - prefix.length).coerceAtLeast(0)
        suggestionWindow?.dismiss()
        editor.text?.replace(start, editor.selectionStart, value)
        suggestionWindow?.dismiss()
        editor.requestFocus()
    }

    private fun renderFileTabs() {
        binding.openFilesContainer.removeAllViews()
        openFileTabs.forEachIndexed { index, tab ->
            val tabRoot = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (index == activeFileTab) {
                    ContextCompat.getDrawable(this@IdeActivity, R.drawable.tab_active)
                } else {
                    ContextCompat.getDrawable(this@IdeActivity, R.drawable.tab_unselected)
                }
                setPadding(dp(7), 0, dp(2), 0)
                minimumWidth = 0
                minimumHeight = 0
                isClickable = true
                setOnClickListener { switchToFileTab(index) }
            }
            val label = TextView(this).apply {
                text = tab.fileName
                textSize = 11f
                maxLines = 1
                minimumWidth = 0
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                ellipsize = null
                setTextColor(ContextCompat.getColor(this@IdeActivity, if (index == activeFileTab) R.color.violet_500 else R.color.text_secondary))
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 0, dp(3), 0)
            }
            val close = TextView(this).apply {
                text = "×"
                textSize = 14f
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_secondary))
                contentDescription = "Close ${tab.fileName}"
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { closeFileTab(index) }
            }
            tabRoot.addView(label, LinearLayout.LayoutParams(-2, -1))
            tabRoot.addView(close, LinearLayout.LayoutParams(dp(22), -1))
            binding.openFilesContainer.addView(tabRoot, LinearLayout.LayoutParams(-2, dp(32)).apply {
                marginEnd = dp(5)
            })
        }
        binding.openFilesScroll.post { binding.openFilesScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun switchToFileTab(index: Int) {
        if (index !in openFileTabs.indices || index == activeFileTab) return
        saveActiveFileTab()
        activeFileTab = index
        val tab = openFileTabs[index]
        suppressTabPersistence = true
        binding.codeEditor.applyCode(tab.code)
        suppressTabPersistence = false
        lastRecordedCode = tab.code
        binding.ideProjectFile.text = getString(R.string.ide_project_file, tab.fileName)
        renderFileTabs()
        updateLineNumbers()
        setStatus(tab.fileName)
    }

    private fun closeFileTab(index: Int) {
        if (openFileTabs.size <= 1) {
            setStatus(getString(R.string.ide_last_file_tab))
            return
        }
        if (index == activeFileTab) saveActiveFileTab()
        openFileTabs.removeAt(index)
        activeFileTab = when {
            index < activeFileTab -> activeFileTab - 1
            activeFileTab >= openFileTabs.size -> openFileTabs.lastIndex
            else -> activeFileTab
        }
        val tab = openFileTabs[activeFileTab]
        suppressTabPersistence = true
        binding.codeEditor.applyCode(tab.code)
        suppressTabPersistence = false
        lastRecordedCode = tab.code
        binding.ideProjectFile.text = getString(R.string.ide_project_file, tab.fileName)
        renderFileTabs()
        updateLineNumbers()
    }

    private fun saveActiveFileTab() {
        val tab = openFileTabs.getOrNull(activeFileTab) ?: return
        val code = binding.codeEditor.text?.toString().orEmpty()
        tab.code = code
        tab.backingFile?.writeText(code) ?: inoFileStore.write(project, code)
        if (::backupStore.isInitialized && code.isNotBlank()) {
            runCatching { backupStore.save(project, code) }
        }
    }

    private fun saveAllTabs() {
        val active = activeFileTab
        openFileTabs.forEachIndexed { index, tab ->
            val code = if (index == active) binding.codeEditor.text?.toString().orEmpty() else tab.code
            tab.code = code
            tab.backingFile?.writeText(code) ?: inoFileStore.write(project, code)
        }
        backupStore.save(project, openFileTabs[active].code)
        Toast.makeText(this, R.string.ide_save_all_done, Toast.LENGTH_SHORT).show()
    }

    private fun closeOtherTabs() {
        if (openFileTabs.size <= 1) return
        saveActiveFileTab()
        val active = openFileTabs[activeFileTab]
        openFileTabs.clear()
        openFileTabs += active
        activeFileTab = 0
        renderFileTabs()
        setStatus(getString(R.string.ide_close_other_tabs))
    }

    private fun deleteCurrentFile() {
        val tab = openFileTabs.getOrNull(activeFileTab) ?: return
        val mainFile = inoFileStore.ensureFile(project).absolutePath
        if (tab.backingFile?.absolutePath == mainFile || openFileTabs.size <= 1) {
            Toast.makeText(this, R.string.ide_delete_main_file_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_delete_current_file)
            .setMessage(getString(R.string.ide_delete_file_message, tab.fileName))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                runCatching { tab.backingFile?.delete() }
                openFileTabs.removeAt(activeFileTab)
                activeFileTab = activeFileTab.coerceAtMost(openFileTabs.lastIndex)
                val next = openFileTabs[activeFileTab]
                suppressTabPersistence = true
                binding.codeEditor.applyCode(next.code)
                suppressTabPersistence = false
                lastRecordedCode = next.code
                binding.ideProjectFile.text = getString(R.string.ide_project_file, next.fileName)
                renderFileTabs()
                updateLineNumbers()
                setStatus(getString(R.string.ide_delete_file_done))
            }
            .show()
    }

    private fun openFileTab(fileName: String, code: String, backingFile: File? = null) {
        saveActiveFileTab()
        val resolvedFile = backingFile ?: inoFileStore.saveAdditional(project, fileName, code)
        val existing = resolvedFile.let { file ->
            openFileTabs.indexOfFirst { it.backingFile?.absolutePath == file.absolutePath }
        } ?: -1
        if (existing >= 0) {
            activeFileTab = existing
            openFileTabs[existing].code = code
        } else {
            openFileTabs += OpenFileTab(resolvedFile.name, code, resolvedFile)
            activeFileTab = openFileTabs.lastIndex
        }
        suppressTabPersistence = true
        binding.codeEditor.applyCode(code)
        suppressTabPersistence = false
        lastRecordedCode = code
        binding.ideProjectFile.text = getString(R.string.ide_project_file, fileName)
        renderFileTabs()
        updateLineNumbers()
        binding.codeEditor.requestFocus()
    }

    private fun setupMenus() {
        binding.fileMenuButton.setOnClickListener { activateMenu(it); showFileMenu(it) }
        binding.actionMenuButton.setOnClickListener { activateMenu(it); showActionMenu(it) }
        binding.examplesMenuButton.setOnClickListener { activateMenu(it); showExamplesMenu(it) }
        binding.libraryMenuButton.setOnClickListener { activateMenu(it); showLibraryMenu(it) }
        binding.codeMenuButton.setOnClickListener { activateMenu(it); showCodeMenu(it) }
    }

    private fun startIdeTutorial() {
        SpotlightTutorial(
            activity = this,
            steps = listOf(
                TutorialStep(binding.codeEditor, getString(R.string.tutorial_ide_editor_title), getString(R.string.tutorial_ide_editor_body)),
                TutorialStep(binding.fileMenuButton, getString(R.string.tutorial_file_title), getString(R.string.tutorial_file_body)),
                TutorialStep(binding.examplesMenuButton, getString(R.string.tutorial_examples_title), getString(R.string.tutorial_examples_body)),
                TutorialStep(binding.libraryMenuButton, getString(R.string.tutorial_library_title), getString(R.string.tutorial_library_body)),
                TutorialStep(binding.codeMenuButton, getString(R.string.tutorial_code_title), getString(R.string.tutorial_code_body)),
                TutorialStep(binding.actionMenuButton, getString(R.string.tutorial_action_title), getString(R.string.tutorial_action_body)),
                TutorialStep(binding.actionMenuButton, getString(R.string.tutorial_verify_title), getString(R.string.tutorial_verify_body)),
                TutorialStep(binding.actionMenuButton, getString(R.string.tutorial_upload_title), getString(R.string.tutorial_upload_body)),
                TutorialStep(binding.actionMenuButton, getString(R.string.tutorial_serial_title), getString(R.string.tutorial_serial_body))
            ),
            onFinished = { completed ->
                if (completed) {
                    getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
                        .edit()
                        .putInt(LumaApplication.TUTORIAL_FLOW_STAGE_KEY, LumaApplication.TUTORIAL_STAGE_COMPLETE)
                        .putBoolean(LumaApplication.INTERACTIVE_GUIDE_COMPLETE_KEY, true)
                        .apply()
                    setStatus(getString(R.string.tutorial_complete_title), getString(R.string.tutorial_complete_body))
                }
            },
            onStepExplained = { step, _, _ ->
                Toast.makeText(this, getString(R.string.tutorial_step_explained, step.title), Toast.LENGTH_SHORT).show()
            }
        ).start()
    }

    private fun activateMenu(active: View) {
        val buttons = listOf(
            binding.fileMenuButton,
            binding.actionMenuButton,
            binding.examplesMenuButton,
            binding.libraryMenuButton,
            binding.codeMenuButton
        )
        buttons.forEach { button ->
            val selected = button === active
            button.background = if (selected) {
                ContextCompat.getDrawable(this, R.drawable.ide_menu_active)
            } else {
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            }
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.violet_500 else R.color.text_primary
                )
            )
        }
    }

    private fun showFileMenu(anchor: View) {
        val saveAutoLabel = getString(
            if (saveAutoEnabled) R.string.ide_save_auto_on else R.string.ide_save_auto_off
        )
        showMenu(anchor, listOf(
            getString(R.string.ide_save),
            getString(R.string.ide_save_all),
            saveAutoLabel,
            getString(R.string.ide_import_code),
            getString(R.string.ide_new_file),
            getString(R.string.ide_close_other_tabs),
            getString(R.string.ide_delete_current_file),
            getString(R.string.ide_export_code),
            getString(R.string.ide_export_hex)
        )) { label ->
            when (label) {
                getString(R.string.ide_save) -> {
                    saveActiveFileTab()
                    Toast.makeText(this, R.string.ide_saved, Toast.LENGTH_SHORT).show()
                }
                getString(R.string.ide_save_all) -> saveAllTabs()
                saveAutoLabel -> toggleSaveAuto()
                getString(R.string.ide_import_code) -> importCodeLauncher.launch(arrayOf("text/*", "text/plain", "*/*"))
                getString(R.string.ide_new_file) -> newFile()
                getString(R.string.ide_close_other_tabs) -> closeOtherTabs()
                getString(R.string.ide_delete_current_file) -> deleteCurrentFile()
                getString(R.string.ide_export_code) -> exportCodeLauncher.launch("${project.name}.ino")
                getString(R.string.ide_export_hex) -> exportProjectHex()
            }
        }
    }

    private fun toggleSaveAuto() {
        saveAutoEnabled = !saveAutoEnabled
        getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(LumaApplication.SAVE_AUTO_KEY, saveAutoEnabled)
            .apply()
        if (!saveAutoEnabled) {
            backupHandler.removeCallbacks(delayedBackup)
        } else {
            saveActiveFileTab()
        }
        Toast.makeText(
            this,
            if (saveAutoEnabled) R.string.ide_save_auto_enabled else R.string.ide_save_auto_disabled,
            Toast.LENGTH_SHORT
        ).show()
        setStatus(getString(if (saveAutoEnabled) R.string.ide_save_auto_on else R.string.ide_save_auto_off))
    }

    private fun showActionMenu(anchor: View) {
        showMenu(anchor, listOf(
            getString(R.string.ide_upload_code),
            getString(R.string.ide_check_code),
                getString(R.string.ide_build_details),
                getString(R.string.ide_memory_analyzer),
                getString(R.string.ide_board_connection_test),
                getString(R.string.ide_project_health),
            getString(R.string.ide_dependencies),
                getString(R.string.ide_build_history),
                getString(R.string.ide_extract_firmware),
                getString(R.string.ide_extract_board_data),
                getString(R.string.ide_custom_avrdude_command),
            getString(R.string.ide_advanced_avrdude),
            getString(R.string.ide_arduino_tools),
            getString(R.string.ide_select_board),
            getString(R.string.ide_select_port),
            getString(R.string.ide_serial_monitor),
            getString(R.string.ide_library_core_reset)
        )) { label ->
            when (label) {
                getString(R.string.ide_upload_code) -> uploadCode()
                getString(R.string.ide_check_code) -> checkCode()
                getString(R.string.ide_build_details) -> showBuildDetails()
                getString(R.string.ide_memory_analyzer) -> showMemoryAnalyzer()
                getString(R.string.ide_board_connection_test) -> testBoardConnection()
                getString(R.string.ide_project_health) -> showProjectHealth()
                getString(R.string.ide_dependencies) -> showProjectDependencies()
                getString(R.string.ide_build_history) -> showBuildHistory()
                getString(R.string.ide_extract_firmware) -> extractFirmware()
                getString(R.string.ide_extract_board_data) -> extractCurrentBoardData()
                getString(R.string.ide_custom_avrdude_command) -> showCustomAvrdudeCommands()
                getString(R.string.ide_advanced_avrdude) -> showAdvancedAvrdudeOptions()
                getString(R.string.ide_arduino_tools) -> showArduinoTools()
                getString(R.string.ide_select_board) -> selectBoard()
                getString(R.string.ide_select_port) -> selectUsbPort()
                getString(R.string.ide_serial_monitor) -> showSerialMonitor()
                getString(R.string.ide_library_core_reset) -> showLibraryCoreReset()
            }
        }
    }

    private fun bindHardwareKeyboardShortcuts() {
        binding.codeEditor.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || !event.isCtrlPressed) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_S -> { saveActiveFileTab(); Toast.makeText(this, R.string.ide_saved, Toast.LENGTH_SHORT).show(); true }
                KeyEvent.KEYCODE_F -> { showFindReplaceDialog(); true }
                KeyEvent.KEYCODE_R -> { checkCode(); true }
                KeyEvent.KEYCODE_U -> { uploadCode(); true }
                KeyEvent.KEYCODE_L -> { openLibraryManager(); true }
                else -> false
            }
        }
    }

    private fun dependencyReport(): ProjectDependencyReport = ProjectDependencyAnalyzer.analyze(
        context = this,
        selectedLibraries = project.libraries,
        sourceCode = binding.codeEditor.text?.toString().orEmpty()
    )

    private fun showProjectDependencies() {
        saveActiveFileTab()
        val report = dependencyReport()
        val message = buildString {
            append(getString(R.string.ide_dependencies_includes, report.includes.joinToString().ifBlank { getString(R.string.ide_none) })).append("\n\n")
            append(getString(R.string.ide_dependencies_linked, report.linked.joinToString().ifBlank { getString(R.string.ide_none) })).append("\n\n")
            append(getString(R.string.ide_dependencies_available, report.installedAvailable.joinToString().ifBlank { getString(R.string.ide_none) })).append("\n\n")
            append(getString(R.string.ide_dependencies_missing, report.missing.joinToString().ifBlank { getString(R.string.ide_none) }))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_dependencies)
            .setMessage(message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_open_library_manager) { _, _ -> openLibraryManager() }
            .show()
    }

    private fun showProjectHealth() {
        saveActiveFileTab()
        val code = binding.codeEditor.text?.toString().orEmpty()
        val report = dependencyReport()
        val findings = mutableListOf<String>()
        val mainFile = inoFileStore.ensureFile(project)
        if (!mainFile.isFile) findings += getString(R.string.ide_health_missing_main)
        if (!Regex("\\bvoid\\s+setup\\s*\\(").containsMatchIn(code)) findings += getString(R.string.ide_preflight_missing_setup)
        if (!Regex("\\bvoid\\s+loop\\s*\\(").containsMatchIn(code)) findings += getString(R.string.ide_preflight_missing_loop)
        if (code.count { it == '{' } != code.count { it == '}' }) findings += getString(R.string.ide_health_braces)
        if (report.missing.isNotEmpty()) findings += getString(R.string.ide_health_missing_libraries, report.missing.joinToString())
        if (findings.isEmpty()) findings += getString(R.string.ide_health_passed)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_project_health)
            .setMessage(findings.joinToString("\n• ", prefix = "• "))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_dependencies) { _, _ -> showProjectDependencies() }
            .show()
    }

    private fun showBuildHistory() {
        val history = buildHistoryStore.load(project.id)
        val text = history.joinToString("\n\n") { entry ->
            val state = if (entry.success) getString(R.string.ide_history_success) else getString(R.string.ide_history_failed)
            "${entry.type} · $state\n${entry.board}${if (entry.port.isBlank()) "" else " · ${entry.port}"}\n${entry.durationMs} ms\n${entry.summary}"
        }.ifBlank { getString(R.string.ide_history_empty) }
        showCopyableCompilerDialog(getString(R.string.ide_build_history), text)
    }

    private fun showExamplesMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        val examples = linkedMapOf<Int, Triple<String, String, String>>()
        fun addBranch(title: String, entries: List<Pair<String, String>>) {
            val branch = popup.menu.addSubMenu(title)
            entries.forEach { (name, code) ->
                val id = nextMenuId++
                branch.add(Menu.NONE, id, Menu.NONE, name)
                examples[id] = Triple(title, name, code)
            }
        }
        addBranch(getString(R.string.ide_liquid_crystal), listOf(
            "Hello LCD.ino" to """#include <LiquidCrystal.h>\nLiquidCrystal lcd(12, 11, 5, 4, 3, 2);\n\nvoid setup() {\n  lcd.begin(16, 2);\n  lcd.print(\"Hello Arduino\");\n}\n\nvoid loop() {}\n""",
            "LCD Counter.ino" to """#include <LiquidCrystal.h>\nLiquidCrystal lcd(12, 11, 5, 4, 3, 2);\nint counter = 0;\n\nvoid setup() {\n  lcd.begin(16, 2);\n}\n\nvoid loop() {\n  lcd.setCursor(0, 0);\n  lcd.print(counter++);\n  delay(1000);\n}\n"""
        ))
        addBranch(getString(R.string.ide_ultrasonic), listOf(
            "Distance Sensor.ino" to """const int trigPin = 9;\nconst int echoPin = 10;\n\nvoid setup() {\n  pinMode(trigPin, OUTPUT);\n  pinMode(echoPin, INPUT);\n  Serial.begin(9600);\n}\n\nvoid loop() {\n  digitalWrite(trigPin, LOW);\n  delayMicroseconds(2);\n  digitalWrite(trigPin, HIGH);\n  delayMicroseconds(10);\n  digitalWrite(trigPin, LOW);\n  long duration = pulseIn(echoPin, HIGH);\n  Serial.println(duration);\n}\n"""
        ))
        addBranch(getString(R.string.ide_blink), listOf(
            "Basic Blink.ino" to """const int ledPin = LED_BUILTIN;\n\nvoid setup() {\n  pinMode(ledPin, OUTPUT);\n}\n\nvoid loop() {\n  digitalWrite(ledPin, HIGH);\n  delay(1000);\n  digitalWrite(ledPin, LOW);\n  delay(1000);\n}\n"""
        ))
        addBranch(getString(R.string.ide_button), listOf(
            "Button LED.ino" to """const int buttonPin = 2;\nconst int ledPin = 13;\n\nvoid setup() {\n  pinMode(buttonPin, INPUT_PULLUP);\n  pinMode(ledPin, OUTPUT);\n}\n\nvoid loop() {\n  digitalWrite(ledPin, digitalRead(buttonPin) == LOW);\n}\n"""
        ))
        addBranch(getString(R.string.ide_serial), listOf(
            "Serial Hello.ino" to """void setup() {\n  Serial.begin(9600);\n  Serial.println(\"Hello from Luma\");\n}\n\nvoid loop() {\n  delay(1000);\n}\n"""
        ))
        libraryExampleGroups.forEach { (libraryName, entries) -> addBranch(libraryName, entries) }
        if (libraryExampleGroups.isEmpty()) popup.menu.add(getString(R.string.ide_no_library_examples))
        popup.setOnMenuItemClickListener { item ->
            examples[item.itemId]?.let { (library, name, code) ->
                val normalized = normalizeSketch(code) + "\n"
                val file = inoFileStore.saveAdditional(project, name, normalized)
                openFileTab(file.name, normalized, file)
                setStatus(file.name, "$library / $name")
                true
            } ?: false
        }
        popup.show()
    }

    private fun showLibraryMenu(anchor: View) {
        showMenu(anchor, listOf(
            getString(R.string.ide_import_library_device),
            getString(R.string.ide_install_library_internet),
            getString(R.string.ide_select_library)
        )) { label ->
            when (label) {
                getString(R.string.ide_import_library_device) -> importLibraryLauncher.launch(arrayOf("application/zip", "text/*", "*/*"))
                getString(R.string.ide_install_library_internet) -> openLibraryManager()
                getString(R.string.ide_select_library) -> openLibraryManager()
            }
        }
    }

    private fun showCodeMenu(anchor: View) {
        showMenu(anchor, listOf(
            getString(R.string.ide_undo),
            getString(R.string.ide_redo),
            getString(R.string.ide_format_code),
            getString(R.string.ide_go_to_line_tool),
            getString(R.string.ide_find_replace),
            getString(R.string.ide_toggle_comment),
            getString(R.string.ide_duplicate_selection),
            getString(R.string.ide_trim_whitespace),
            getString(R.string.ide_insert_snippet),
            getString(R.string.ide_word_wrap),
            getString(R.string.ide_code_statistics),
            getString(R.string.ide_preflight_check),
            getString(R.string.ide_select_all),
            getString(R.string.ide_copy),
            getString(R.string.ide_delete_all),
            getString(R.string.ide_paste),
            getString(R.string.ide_settings_editor),
            getString(R.string.ide_restore_backup)
        )) { label ->
            when (label) {
                getString(R.string.ide_undo) -> undo()
                getString(R.string.ide_redo) -> redo()
                getString(R.string.ide_format_code) -> formatActiveCode()
                getString(R.string.ide_go_to_line_tool) -> showGoToLineDialog()
                getString(R.string.ide_find_replace) -> showFindReplaceDialog()
                getString(R.string.ide_toggle_comment) -> toggleCommentSelection()
                getString(R.string.ide_duplicate_selection) -> duplicateSelection()
                getString(R.string.ide_trim_whitespace) -> trimTrailingWhitespace()
                getString(R.string.ide_insert_snippet) -> showSnippetMenu()
                getString(R.string.ide_word_wrap) -> toggleWordWrap()
                getString(R.string.ide_code_statistics) -> showCodeStatistics()
                getString(R.string.ide_preflight_check) -> showPreflightCheck()
                getString(R.string.ide_select_all) -> binding.codeEditor.selectAll()
                getString(R.string.ide_copy) -> binding.codeEditor.onTextContextMenuItem(android.R.id.copy)
                getString(R.string.ide_delete_all) -> binding.codeEditor.setText("")
                getString(R.string.ide_paste) -> binding.codeEditor.onTextContextMenuItem(android.R.id.paste)
                getString(R.string.ide_settings_editor) -> showEditorSettings()
                getString(R.string.ide_restore_backup) -> restoreBackup()
            }
        }
    }

    private fun formatActiveCode() {
        val formatted = ArduinoCodeFormatter.format(binding.codeEditor.text?.toString().orEmpty())
        replaceEditorContent(formatted)
        setStatus(getString(R.string.ide_format_code))
    }

    private fun replaceEditorContent(content: String, selectionStart: Int = content.length, selectionEnd: Int = selectionStart) {
        suppressTabPersistence = true
        binding.codeEditor.applyCode(content)
        binding.codeEditor.setSelection(
            selectionStart.coerceIn(0, content.length),
            selectionEnd.coerceIn(0, content.length)
        )
        suppressTabPersistence = false
        openFileTabs.getOrNull(activeFileTab)?.code = content
        lastRecordedCode = content
        saveActiveFileTab()
        updateLineNumbers()
    }

    private fun selectedLineRange(): Pair<Int, Int> {
        val content = binding.codeEditor.text?.toString().orEmpty()
        val start = binding.codeEditor.selectionStart.coerceIn(0, content.length)
        val end = binding.codeEditor.selectionEnd.coerceIn(0, content.length)
        val lineStart = content.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val lineEnd = content.indexOf('\n', end).let { if (it < 0) content.length else it }
        return lineStart to lineEnd
    }

    private fun toggleCommentSelection() {
        val content = binding.codeEditor.text?.toString().orEmpty()
        val range = selectedLineRange()
        val block = content.substring(range.first, range.second)
        val lines = block.split('\n')
        val uncomment = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith("//") }
        val changed = lines.joinToString("\n") { line ->
            if (line.isBlank()) line else if (uncomment) line.replaceFirst(Regex("^(\\s*)//\\s?"), "$1")
            else line.replaceFirst(Regex("^(\\s*)"), "$1// ")
        }
        val updated = content.replaceRange(range.first, range.second, changed)
        replaceEditorContent(updated, range.first, range.first + changed.length)
        setStatus(getString(R.string.ide_toggle_comment))
    }

    private fun duplicateSelection() {
        val content = binding.codeEditor.text?.toString().orEmpty()
        val start = binding.codeEditor.selectionStart.coerceIn(0, content.length)
        val end = binding.codeEditor.selectionEnd.coerceIn(0, content.length)
        val range = if (start == end) selectedLineRange() else start to end
        val selected = content.substring(range.first, range.second)
        val separator = if (start == end) "\n" else ""
        val inserted = selected + separator + selected
        val updated = content.replaceRange(range.first, range.second, inserted)
        val copyStart = range.first + selected.length + separator.length
        replaceEditorContent(updated, copyStart, copyStart + selected.length)
        setStatus(getString(R.string.ide_duplicate_selection))
    }

    private fun trimTrailingWhitespace() {
        val content = binding.codeEditor.text?.toString().orEmpty()
        val trimmed = content.lines().joinToString("\n") { it.trimEnd() }
        replaceEditorContent(trimmed)
        setStatus(getString(R.string.ide_trim_whitespace))
    }

    private fun toggleWordWrap() {
        wordWrapEnabled = !wordWrapEnabled
        getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .edit().putBoolean(EDITOR_WORD_WRAP_KEY, wordWrapEnabled).apply()
        applyWordWrap()
        setStatus(getString(if (wordWrapEnabled) R.string.ide_word_wrap_on else R.string.ide_word_wrap_off))
    }

    private fun applyWordWrap() {
        binding.codeEditor.setHorizontallyScrolling(!wordWrapEnabled)
        binding.codeEditor.isSingleLine = false
    }

    private fun showSnippetMenu() {
        val snippets = linkedMapOf(
            "for loop" to "for (int i = 0; i < 10; i++) {\n  \n}",
            "if / else" to "if (condition) {\n  \n} else {\n  \n}",
            "Serial print" to "Serial.println(value);",
            "millis timer" to "unsigned long previousMillis = 0;\nconst unsigned long interval = 1000;\n\nif (millis() - previousMillis >= interval) {\n  previousMillis = millis();\n}",
            "debounce button" to "const unsigned long debounceDelay = 50;\nunsigned long lastChange = 0;\n\nif (millis() - lastChange > debounceDelay) {\n  lastChange = millis();\n}"
        )
        showMenu(binding.codeMenuButton, snippets.keys.toList()) { label ->
            val snippet = snippets[label] ?: return@showMenu
            val content = binding.codeEditor.text?.toString().orEmpty()
            val start = binding.codeEditor.selectionStart.coerceIn(0, content.length)
            val end = binding.codeEditor.selectionEnd.coerceIn(0, content.length)
            val updated = content.replaceRange(start, end, snippet)
            replaceEditorContent(updated, start, start + snippet.length)
            setStatus(getString(R.string.ide_insert_snippet), label)
        }
    }

    private fun showFindReplaceDialog() {
        val findInput = com.google.android.material.textfield.TextInputEditText(this).apply { hint = getString(R.string.ide_find_hint); setSingleLine(true) }
        val replaceInput = com.google.android.material.textfield.TextInputEditText(this).apply { hint = getString(R.string.ide_replace_hint); setSingleLine(true) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        listOf(findInput, replaceInput).forEach { input ->
            root.addView(com.google.android.material.textfield.TextInputLayout(this).apply { hint = input.hint; addView(input) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_find_replace)
            .setView(root)
            .setNegativeButton(R.string.cancel_action, null)
            .setNeutralButton(R.string.ide_find_next, null)
            .setPositiveButton(R.string.ide_replace_all, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val query = findInput.text?.toString().orEmpty()
                if (query.isBlank()) return@setOnClickListener
                val code = binding.codeEditor.text?.toString().orEmpty()
                val from = binding.codeEditor.selectionEnd.coerceAtLeast(0)
                val index = code.indexOf(query, from).takeIf { it >= 0 } ?: code.indexOf(query)
                if (index >= 0) binding.codeEditor.setSelection(index, index + query.length)
                else findInput.error = getString(R.string.ide_find_not_found)
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = findInput.text?.toString().orEmpty()
                if (query.isBlank()) return@setOnClickListener
                val code = binding.codeEditor.text?.toString().orEmpty()
                val count = Regex(Regex.escape(query)).findAll(code).count()
                replaceEditorContent(code.replace(query, replaceInput.text?.toString().orEmpty()))
                setStatus(getString(R.string.ide_replace_done, count))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showCodeStatistics() {
        val code = binding.codeEditor.text?.toString().orEmpty()
        val lineCount = code.lines().size
        val functions = Regex("\\b(?:void|int|long|float|double|bool|boolean|char|String)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").findAll(code).count()
        val includes = Regex("#include").findAll(code).count()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_code_statistics)
            .setMessage(getString(R.string.ide_code_statistics_message, lineCount, code.length, functions, includes))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showPreflightCheck() {
        val code = binding.codeEditor.text?.toString().orEmpty()
        val braces = code.count { it == '{' } - code.count { it == '}' }
        val findings = mutableListOf<String>()
        if (!Regex("\\bvoid\\s+setup\\s*\\(").containsMatchIn(code)) findings += getString(R.string.ide_preflight_missing_setup)
        if (!Regex("\\bvoid\\s+loop\\s*\\(").containsMatchIn(code)) findings += getString(R.string.ide_preflight_missing_loop)
        if (braces != 0) findings += getString(R.string.ide_preflight_braces, braces)
        if (findings.isEmpty()) findings += getString(R.string.ide_preflight_passed)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_preflight_check)
            .setMessage(findings.joinToString("\n• ", prefix = "• "))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showGoToLineDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.ide_go_to_line_hint)
            setSingleLine(true)
        }
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.ide_go_to_line_hint)
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_go_to_line_tool)
            .setView(inputLayout)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requestedLine = input.text?.toString()?.toIntOrNull()
                val lines = binding.codeEditor.text?.toString().orEmpty().split('\n')
                if (requestedLine == null || requestedLine !in 1..lines.size) {
                    inputLayout.error = getString(R.string.ide_go_to_line_invalid, lines.size)
                    return@setOnClickListener
                }
                val offset = lines.take(requestedLine - 1).sumOf { it.length + 1 }
                val lineEnd = (offset + lines[requestedLine - 1].length).coerceAtMost(binding.codeEditor.length())
                binding.codeEditor.requestFocus()
                binding.codeEditor.setSelection(offset, lineEnd)
                setStatus(getString(R.string.ide_go_to_line_selected, requestedLine))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showMenu(anchor: View, labels: List<String>, onSelected: (String) -> Unit) {
        val popup = android.widget.PopupMenu(this, anchor)
        labels.forEach { popup.menu.add(it) }
        popup.setOnMenuItemClickListener { item ->
            onSelected(item.title.toString())
            true
        }
        popup.show()
    }

    private fun restoreBackup() {
        val backup = backupStore.restoreLatest(project)
        if (backup == null) {
            Toast.makeText(this, R.string.ide_restore_backup_none, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_restore_backup_title)
            .setMessage(R.string.ide_restore_backup_message)
            .setPositiveButton(R.string.ide_restore_backup_done) { _, _ ->
                openFileTabs.getOrNull(activeFileTab)?.let { tab ->
                    tab.code = backup
                    suppressTabPersistence = true
                    binding.codeEditor.applyCode(backup)
                    suppressTabPersistence = false
                    lastRecordedCode = backup
                    tab.backingFile?.writeText(backup) ?: inoFileStore.write(project, backup)
                    setStatus(getString(R.string.ide_restore_backup_done))
                }
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun newFile() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.ide_new_file_hint)
            setText("Untitled${openFileTabs.size + 1}.ino")
            setSingleLine(true)
            selectAll()
        }
        val layout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.ide_new_file_hint)
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_new_file)
            .setView(layout)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString().orEmpty().trim().ifBlank { "Untitled${openFileTabs.size + 1}.ino" }
                val code = when {
                    name.endsWith(".h", ignoreCase = true) -> "#pragma once\n\n"
                    name.endsWith(".cpp", ignoreCase = true) -> "#include \"${name.substringBeforeLast('.')}.h\"\n\n"
                    else -> "// ${project.name}\n\nvoid setup() {\n}\n\nvoid loop() {\n}\n"
                }
                val file = inoFileStore.saveAdditional(project, name, code)
                openFileTab(file.name, code, file)
                setStatus(getString(R.string.ide_new_file), file.name)
            }
            .show()
    }

    private fun normalizeSketch(code: String): String {
        return code.replace("""\n""", "\n").replace("\\\"", "\"").trimIndent()
    }

    private fun importCode(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input -> input.readBytes().toString(Charsets.UTF_8) }
        }.onSuccess { code ->
            if (code != null) {
                val requestedName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported.ino"
                val file = inoFileStore.saveAdditional(project, requestedName, code)
                openFileTab(file.name, code, file)
                setStatus(getString(R.string.ide_import_code), file.name)
            }
        }.onFailure {
            Toast.makeText(this, it.message ?: "Import failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportCode(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(binding.codeEditor.text?.toString().orEmpty().toByteArray())
            }
            setStatus(getString(R.string.ide_export_code))
        }.onFailure {
            Toast.makeText(this, it.message ?: "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveHexTo(uri: Uri) {
        val hexFile = pendingHexExportFile
        if (hexFile == null || !hexFile.isFile || hexFile.length() <= 0L) {
            Toast.makeText(this, R.string.ide_export_hex_missing, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val output = contentResolver.openOutputStream(uri)
                ?: error("Unable to open selected destination")
            output.use { destination ->
                hexFile.inputStream().buffered().use { input -> input.copyTo(destination) }
            }
        }.onSuccess {
            pendingHexExportFile = null
            setStatus(getString(R.string.ide_export_hex_saved, hexFile.name))
            Toast.makeText(this, getString(R.string.ide_export_hex_saved, hexFile.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: getString(R.string.ide_export_hex_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun exportProjectHex() {
        saveAllTabs()
        val sourceCode = binding.codeEditor.text?.toString().orEmpty()
        val libraryDirectories = ArduinoLibraryStorage
            .directoriesFor(this, project.libraries, sourceCode)
            .map { it.absolutePath }
        val logView = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Preparing HEX export...\nBoard: ${selectedBoard.fqbn}\n"
        }
        val logScroll = android.widget.ScrollView(this).apply {
            addView(logView, ViewGroup.LayoutParams(-1, -2))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_export_hex)
            .setView(logScroll)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Luma HEX export log", logView.text))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.ide_export_hex_save, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val startedAt = System.currentTimeMillis()
        lastBuildStartedAt = startedAt
        lastBuildLibraryDirectories = libraryDirectories
        lastBuildCommand = buildCommandPreview(libraryDirectories)
        setStatus(getString(R.string.ide_export_hex_running), selectedBoard.fqbn)
        avrCompiler.compile(
            AvrCompileRequest(
                projectName = project.name,
                code = sourceCode,
                board = selectedBoard,
                libraryDirectories = libraryDirectories
            )
        ) { result ->
            runOnUiThread {
                lastBuildDurationMs = System.currentTimeMillis() - startedAt
                lastCompileResult = result
                val output = when (result) {
                    is AvrCompileResult.Success -> result.output
                    is AvrCompileResult.Failure -> result.output
                    is AvrCompileResult.ToolchainUnavailable -> result.message
                }
                logView.append("\n$output\n")
                val hexFile = (result as? AvrCompileResult.Success)?.hexFilePath
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0L }
                buildHistoryStore.add(project.id, BuildHistoryEntry(
                    type = "Export HEX",
                    success = hexFile != null,
                    timestamp = System.currentTimeMillis(),
                    durationMs = lastBuildDurationMs,
                    board = selectedBoard.fqbn,
                    port = savedPortName.orEmpty(),
                    summary = output
                ))
                if (hexFile == null) {
                    setStatus(getString(R.string.ide_export_hex_failed))
                    logView.append("\n${getString(R.string.ide_export_hex_missing)}\n")
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(android.R.string.ok)
                        setOnClickListener { dialog.dismiss() }
                    }
                } else {
                    pendingHexExportFile = hexFile
                    setStatus(getString(R.string.ide_export_hex_ready, hexFile.name))
                    logView.append("\n${getString(R.string.ide_export_hex_ready, hexFile.name)}\n")
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(R.string.ide_export_hex_save)
                        setOnClickListener {
                            dialog.dismiss()
                            val name = project.name.replace(Regex("[^A-Za-z0-9_-]"), "_")
                                .trim('_')
                                .ifBlank { "arduino_project" }
                            exportHexLauncher.launch("$name.hex")
                        }
                    }
                }
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun saveFirmwareZipTo(uri: Uri) {
        val zipFile = pendingFirmwareZipFile
        if (zipFile == null || !zipFile.isFile) {
            Toast.makeText(this, R.string.ide_extract_zip_missing, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val output = contentResolver.openOutputStream(uri)
                ?: error("Unable to open selected destination")
            output.use { destination ->
                zipFile.inputStream().buffered().use { input -> input.copyTo(destination) }
            }
        }.onSuccess {
            pendingFirmwareZipFile = null
            setStatus(getString(R.string.ide_extract_zip_saved, zipFile.name))
            Toast.makeText(this, getString(R.string.ide_extract_zip_saved, zipFile.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: getString(R.string.ide_extract_zip_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAvrdudeCommandZipTo(uri: Uri) {
        val zipFile = pendingAvrdudeCommandZipFile
        if (zipFile == null || !zipFile.isFile) {
            Toast.makeText(this, R.string.ide_avrdude_command_zip_missing, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val output = contentResolver.openOutputStream(uri)
                ?: error("Unable to open selected destination")
            output.use { destination ->
                zipFile.inputStream().buffered().use { input -> input.copyTo(destination) }
            }
        }.onSuccess {
            pendingAvrdudeCommandZipFile = null
            setStatus(getString(R.string.ide_avrdude_command_zip_saved, zipFile.name))
            Toast.makeText(this, getString(R.string.ide_avrdude_command_zip_saved, zipFile.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: getString(R.string.ide_avrdude_command_zip_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAdvancedAvrdudeZipTo(uri: Uri) {
        val zipFile = pendingAdvancedAvrdudeZipFile
        if (zipFile == null || !zipFile.isFile) {
            Toast.makeText(this, R.string.ide_advanced_avrdude_zip_missing, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val output = contentResolver.openOutputStream(uri)
                ?: error("Unable to open selected destination")
            output.use { destination ->
                zipFile.inputStream().buffered().use { input -> input.copyTo(destination) }
            }
        }.onSuccess {
            pendingAdvancedAvrdudeZipFile = null
            setStatus(getString(R.string.ide_advanced_avrdude_zip_saved, zipFile.name))
            Toast.makeText(this, getString(R.string.ide_advanced_avrdude_zip_saved, zipFile.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: getString(R.string.ide_advanced_avrdude_zip_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveBoardDataZipTo(uri: Uri) {
        val zipFile = pendingBoardDataZipFile
        if (zipFile == null || !zipFile.isFile) {
            Toast.makeText(this, R.string.ide_board_data_zip_missing, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val output = contentResolver.openOutputStream(uri)
                ?: error("Unable to open selected destination")
            output.use { destination ->
                zipFile.inputStream().buffered().use { input -> input.copyTo(destination) }
            }
        }.onSuccess {
            pendingBoardDataZipFile = null
            setStatus(getString(R.string.ide_board_data_zip_saved, zipFile.name))
            Toast.makeText(this, getString(R.string.ide_board_data_zip_saved, zipFile.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: getString(R.string.ide_board_data_zip_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveDiagnosticZipTo(uri: Uri) {
        val zipFile = pendingDiagnosticZipFile
        if (zipFile == null || !zipFile.isFile) {
            Toast.makeText(this, R.string.ide_arduino_diagnostic_missing, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val output = contentResolver.openOutputStream(uri)
                ?: error("Unable to open selected destination")
            output.use { destination ->
                zipFile.inputStream().buffered().use { input -> input.copyTo(destination) }
            }
        }.onSuccess {
            pendingDiagnosticZipFile = null
            Toast.makeText(this, R.string.ide_arduino_diagnostic_saved, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.ide_arduino_diagnostic_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun extractCurrentBoardData() {
        val device = selectedUsbDevice
        if (device == null) {
            pendingBoardDataAfterPermission = true
            setStatus(getString(R.string.ide_board_data_select_port))
            selectUsbPort()
            return
        }
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            pendingBoardDataAfterPermission = true
            requestUsbPermission(device)
            setStatus(getString(R.string.ide_board_data_usb_permission))
            return
        }
        runBoardDataExtraction(device)
    }

    private fun testBoardConnection() {
        val device = selectedUsbDevice
        if (device == null) {
            pendingBoardConnectionTest = true
            setStatus(getString(R.string.ide_board_connection_select_port))
            selectUsbPort()
            return
        }
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            pendingBoardConnectionTest = true
            requestUsbPermission(device)
            setStatus(getString(R.string.ide_board_connection_usb_permission))
            return
        }
        val logView = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Testing board connection without writing...\nBoard: ${selectedBoard.fqbn}\nPort: ${device.deviceName}\n\n"
        }
        val logScroll = android.widget.ScrollView(this).apply { addView(logView, ViewGroup.LayoutParams(-1, -2)) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_board_connection_test)
            .setView(logScroll)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Luma connection test", logView.text))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val startedAt = System.currentTimeMillis()
        setStatus(getString(R.string.ide_board_connection_running))
        avrdudeBridge.runCustomCommand(
            AvrdudeCommandRequest(selectedBoard, device, listOf("-v", "-n")),
            onLog = { line -> runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            } }
        ) { result ->
            runOnUiThread {
                buildHistoryStore.add(project.id, BuildHistoryEntry(
                    type = "Connection test",
                    success = result.success,
                    timestamp = System.currentTimeMillis(),
                    durationMs = System.currentTimeMillis() - startedAt,
                    board = selectedBoard.fqbn,
                    port = device.deviceName,
                    summary = result.output
                ))
                setStatus(getString(if (result.success) R.string.ide_board_connection_success else R.string.ide_board_connection_failed))
                logView.append("\n${getString(if (result.success) R.string.ide_board_connection_success else R.string.ide_board_connection_failed)}\n")
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                    isEnabled = true
                    setOnClickListener { dialog.dismiss() }
                }
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun runBoardDataExtraction(device: UsbDevice) {
        val seed = inoFileStore.firmwareBackupFile(project, "board-data", "zip")
        val directory = seed.parentFile ?: filesDir
        val stamp = System.currentTimeMillis()
        val values = linkedMapOf(
            "signature.txt" to File(directory, "board_${stamp}_signature.txt"),
            "low-fuse.txt" to File(directory, "board_${stamp}_lfuse.txt"),
            "high-fuse.txt" to File(directory, "board_${stamp}_hfuse.txt"),
            "extended-fuse.txt" to File(directory, "board_${stamp}_efuse.txt"),
            "lock-bits.txt" to File(directory, "board_${stamp}_lock.txt")
        )
        values.values.forEach { it.delete() }
        val requests = listOf(
            "signature" to values.getValue("signature.txt"),
            "lfuse" to values.getValue("low-fuse.txt"),
            "hfuse" to values.getValue("high-fuse.txt"),
            "efuse" to values.getValue("extended-fuse.txt"),
            "lock" to values.getValue("lock-bits.txt")
        )
        val arguments = mutableListOf("-v")
        requests.forEach { (memory, file) -> arguments += listOf("-U", "$memory:r:${file.absolutePath}:h") }
        val logView = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Preparing read-only board data extraction...\nBoard: ${selectedBoard.fqbn}\nPort: ${device.deviceName}\n\n"
        }
        val logScroll = android.widget.ScrollView(this).apply { addView(logView, ViewGroup.LayoutParams(-1, -2)) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_extract_board_data)
            .setView(logScroll)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Luma board data log", logView.text))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.ide_board_data_save_zip, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val startedAt = System.currentTimeMillis()
        setStatus(getString(R.string.ide_board_data_running))
        avrdudeBridge.runCustomCommand(
            AvrdudeCommandRequest(selectedBoard, device, arguments),
            onLog = { line -> runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            } }
        ) { result ->
            runOnUiThread {
                val report = buildString {
                    appendLine("Luma current board data report")
                    appendLine("Board: ${selectedBoard.fqbn}")
                    appendLine("MCU: ${selectedBoard.mcu}")
                    appendLine("Programmer: ${selectedBoard.programmer}")
                    appendLine("Port: ${device.deviceName}")
                    appendLine("USB vendor/product: ${device.vendorId}/${device.productId}")
                    appendLine("Result: ${if (result.success) "SUCCESS" else "PARTIAL OR FAILED"}")
                    appendLine()
                    values.forEach { (label, file) ->
                        val value = runCatching { file.readText().trim().ifBlank { "Unavailable" } }.getOrDefault("Unavailable")
                        appendLine("$label: $value")
                    }
                }
                buildHistoryStore.add(project.id, BuildHistoryEntry(
                    type = "Board data",
                    success = result.success,
                    timestamp = System.currentTimeMillis(),
                    durationMs = System.currentTimeMillis() - startedAt,
                    board = selectedBoard.fqbn,
                    port = device.deviceName,
                    summary = result.output
                ))
                setStatus(getString(if (result.success) R.string.ide_board_data_success else R.string.ide_board_data_failed))
                logView.append("\n$report\n")
                runCatching {
                    BoardDataBundleWriter.create(seed, report, result.output, values)
                }.onSuccess { zipFile ->
                    pendingBoardDataZipFile = zipFile
                    logView.append("\n${getString(R.string.ide_board_data_zip_ready, zipFile.name)}\n")
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(R.string.ide_board_data_save_zip)
                        setOnClickListener {
                            dialog.dismiss()
                            exportBoardDataZipLauncher.launch(zipFile.name)
                        }
                    }
                }.onFailure { error ->
                    logView.append("\n${getString(R.string.ide_board_data_zip_failed)}: ${error.message}\n")
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(android.R.string.ok)
                        setOnClickListener { dialog.dismiss() }
                    }
                }
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun copyAdvancedInputAndRun(uri: Uri, options: AdvancedAvrdudeOptions) {
        runCatching {
            val source = contentResolver.openInputStream(uri) ?: error("Unable to open selected file")
            val extension = uri.lastPathSegment?.substringAfterLast('.', "bin")
                ?.replace(Regex("[^A-Za-z0-9]"), "")
                ?.take(8)
                ?.ifBlank { "bin" } ?: "bin"
            val destination = File(cacheDir, "advanced_avrdude_input").apply { mkdirs() }
                .resolve("input_${System.currentTimeMillis()}.$extension")
            source.buffered().use { input -> destination.outputStream().buffered().use { output -> input.copyTo(output) } }
            require(destination.length() > 0L) { "Selected input file is empty" }
            destination
        }.onSuccess { inputFile ->
            runAdvancedAvrdude(PendingAdvancedAvrdudeRun(options, inputFile))
        }.onFailure {
            Toast.makeText(this, it.message ?: getString(R.string.ide_advanced_avrdude_input_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun runAdvancedAvrdude(run: PendingAdvancedAvrdudeRun) {
        val device = selectedUsbDevice
        if (device == null) {
            pendingAdvancedAvrdudeRun = run
            setStatus(getString(R.string.ide_advanced_avrdude_select_port))
            selectUsbPort()
            return
        }
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            pendingAdvancedAvrdudeRun = run
            requestUsbPermission(device)
            setStatus(getString(R.string.ide_advanced_avrdude_usb_permission))
            return
        }
        val memoryFile = if (run.options.producesOutputFile()) {
            inoFileStore.firmwareBackupFile(project, "advanced_${run.options.memory.avrdudeName}", run.options.memory.defaultExtension)
        } else null
        val arguments = runCatching { run.options.toArguments(run.inputFile, memoryFile) }.getOrElse {
            Toast.makeText(this, it.message ?: getString(R.string.ide_advanced_avrdude_invalid), Toast.LENGTH_LONG).show()
            return
        }
        val logView = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Advanced operation: ${run.options.preview()}\nBoard: ${selectedBoard.fqbn}\nPort: ${device.deviceName}\n\n"
        }
        val logScroll = android.widget.ScrollView(this).apply { addView(logView, ViewGroup.LayoutParams(-1, -2)) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_advanced_avrdude)
            .setView(logScroll)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Luma advanced avrdude log", logView.text))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.ide_advanced_avrdude_save_zip, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val startedAt = System.currentTimeMillis()
        setStatus(getString(R.string.ide_advanced_avrdude_running), run.options.operation.label)
        avrdudeBridge.runCustomCommand(
            AvrdudeCommandRequest(selectedBoard, device, arguments),
            onLog = { line -> runOnUiThread {
                logView.append("$line\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            } }
        ) { result ->
            runOnUiThread {
                val duration = System.currentTimeMillis() - startedAt
                buildHistoryStore.add(project.id, BuildHistoryEntry(
                    type = "Advanced avrdude",
                    success = result.success,
                    timestamp = System.currentTimeMillis(),
                    durationMs = duration,
                    board = selectedBoard.fqbn,
                    port = device.deviceName,
                    summary = result.output
                ))
                setStatus(getString(if (result.success) R.string.ide_advanced_avrdude_success else R.string.ide_advanced_avrdude_failed), run.options.operation.label)
                runCatching {
                    AdvancedAvrdudeBundleWriter.create(
                        destination = inoFileStore.firmwareBackupFile(project, "advanced-avrdude", "zip"),
                        options = run.options,
                        board = selectedBoard,
                        port = device.deviceName,
                        success = result.success,
                        output = result.output,
                        memoryFile = memoryFile?.takeIf { it.isFile }
                    )
                }.onSuccess { zipFile ->
                    pendingAdvancedAvrdudeZipFile = zipFile
                    logView.append("\n${getString(R.string.ide_advanced_avrdude_zip_ready, zipFile.name)}\n")
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(R.string.ide_advanced_avrdude_save_zip)
                        setOnClickListener {
                            dialog.dismiss()
                            exportAdvancedAvrdudeZipLauncher.launch(zipFile.name)
                        }
                    }
                }.onFailure { error ->
                    logView.append("\n${getString(R.string.ide_advanced_avrdude_zip_failed)}: ${error.message}\n")
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(android.R.string.ok)
                        setOnClickListener { dialog.dismiss() }
                    }
                }
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun showAdvancedAvrdudeOptions() {
        var operation = AdvancedAvrdudeOperation.IDENTIFY
        var memory = AdvancedAvrdudeMemory.FLASH
        var format = AdvancedAvrdudeFormat.INTEL_HEX
        var verbosity = 1
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val description = TextView(this).apply {
            text = getString(R.string.ide_advanced_avrdude_intro)
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        }
        val operationButton = MaterialButton(this).apply { isAllCaps = false }
        val memoryButton = MaterialButton(this).apply { isAllCaps = false }
        val formatButton = MaterialButton(this).apply { isAllCaps = false }
        val verboseButton = MaterialButton(this).apply { isAllCaps = false }
        val dryRun = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = getString(R.string.ide_advanced_avrdude_dry_run)
        }
        val noAutoErase = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = getString(R.string.ide_advanced_avrdude_no_auto_erase)
        }
        val valueInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.ide_advanced_avrdude_value_hint)
            setSingleLine(true)
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val valueLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.ide_advanced_avrdude_value)
            addView(valueInput)
        }
        val preview = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.violet_500))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(12), dp(6), dp(8))
        }
        fun currentOptions() = AdvancedAvrdudeOptions(
            operation = operation,
            memory = memory,
            format = format,
            dryRun = dryRun.isChecked,
            disableAutoErase = noAutoErase.isChecked,
            verbosity = verbosity,
            valueToWrite = valueInput.text?.toString().orEmpty()
        )
        fun updateUi() {
            operationButton.text = getString(R.string.ide_advanced_avrdude_operation, operation.label)
            memoryButton.text = getString(R.string.ide_advanced_avrdude_memory, memory.label)
            formatButton.text = getString(R.string.ide_advanced_avrdude_format, format.label)
            verboseButton.text = getString(R.string.ide_advanced_avrdude_verbose, verbosity)
            val usesMemory = operation !in setOf(AdvancedAvrdudeOperation.IDENTIFY, AdvancedAvrdudeOperation.ERASE)
            memoryButton.isEnabled = usesMemory
            formatButton.isEnabled = operation in setOf(AdvancedAvrdudeOperation.READ, AdvancedAvrdudeOperation.WRITE)
            noAutoErase.visibility = if (operation == AdvancedAvrdudeOperation.WRITE) View.VISIBLE else View.GONE
            valueLayout.visibility = if (operation == AdvancedAvrdudeOperation.WRITE_VALUE) View.VISIBLE else View.GONE
            preview.text = getString(R.string.ide_advanced_avrdude_preview, currentOptions().preview())
        }
        operationButton.setOnClickListener {
            val values = AdvancedAvrdudeOperation.entries.toList()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ide_advanced_avrdude_choose_operation)
                .setSingleChoiceItems(values.map { it.label }.toTypedArray(), values.indexOf(operation)) { dialog, which ->
                    operation = values[which]
                    updateUi()
                    dialog.dismiss()
                }
                .show()
        }
        memoryButton.setOnClickListener {
            val values = AdvancedAvrdudeMemory.entries.toList()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ide_advanced_avrdude_choose_memory)
                .setSingleChoiceItems(values.map { it.label }.toTypedArray(), values.indexOf(memory)) { dialog, which ->
                    memory = values[which]
                    updateUi()
                    dialog.dismiss()
                }
                .show()
        }
        formatButton.setOnClickListener {
            val values = AdvancedAvrdudeFormat.entries.toList()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ide_advanced_avrdude_choose_format)
                .setSingleChoiceItems(values.map { it.label }.toTypedArray(), values.indexOf(format)) { dialog, which ->
                    format = values[which]
                    updateUi()
                    dialog.dismiss()
                }
                .show()
        }
        verboseButton.setOnClickListener {
            val values = arrayOf("0", "1", "2", "3")
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ide_advanced_avrdude_choose_verbose)
                .setSingleChoiceItems(values, verbosity.coerceIn(0, 3)) { dialog, which ->
                    verbosity = which
                    updateUi()
                    dialog.dismiss()
                }
                .show()
        }
        dryRun.setOnCheckedChangeListener { _, _ -> updateUi() }
        noAutoErase.setOnCheckedChangeListener { _, _ -> updateUi() }
        root.addView(description)
        root.addView(operationButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(memoryButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(formatButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(verboseButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(dryRun)
        root.addView(noAutoErase)
        root.addView(valueLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(preview)
        updateUi()
        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_advanced_avrdude)
            .setView(scroll)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_advanced_avrdude_run, null)
            .create()
        fun start(options: AdvancedAvrdudeOptions) {
            if (options.requiresInputFile()) {
                pendingAdvancedAvrdudeInputOptions = options
                importAdvancedAvrdudeInputLauncher.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
            } else {
                runAdvancedAvrdude(PendingAdvancedAvrdudeRun(options))
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val options = currentOptions()
                val valid = runCatching {
                    if (options.operation == AdvancedAvrdudeOperation.WRITE_VALUE) options.toArguments()
                }.isSuccess
                if (!valid) {
                    Toast.makeText(this, R.string.ide_advanced_avrdude_invalid, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (options.requiresDangerConfirmation()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.ide_advanced_avrdude_danger_title)
                        .setMessage(R.string.ide_advanced_avrdude_danger_message)
                        .setNegativeButton(R.string.cancel_action, null)
                        .setPositiveButton(R.string.ide_advanced_avrdude_continue) { _, _ ->
                            dialog.dismiss()
                            start(options)
                        }
                        .show()
                } else {
                    dialog.dismiss()
                    start(options)
                }
            }
        }
        dialog.show()
    }

    private fun showArduinoTools() {
        val actions = arrayOf(
            getString(R.string.ide_arduino_board_catalog),
            getString(R.string.ide_arduino_library_workspace),
            getString(R.string.ide_arduino_project_files),
            getString(R.string.ide_arduino_search_project),
            getString(R.string.ide_arduino_restore_point),
            getString(R.string.ide_arduino_example_generator),
            getString(R.string.ide_arduino_shortcuts),
            getString(R.string.ide_arduino_diagnostic_bundle),
            getString(R.string.ide_arduino_board_profile),
            getString(R.string.ide_arduino_usb_diagnostics),
            getString(R.string.ide_arduino_project_report),
            getString(R.string.ide_arduino_firmware_backups),
            getString(R.string.ide_arduino_clear_firmware_backups)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_arduino_tools)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showBoardCatalog()
                    1 -> openLibraryManager()
                    2 -> showProjectFileMap()
                    3 -> showProjectSearch()
                    4 -> restoreLatestProjectBackup()
                    5 -> showExampleGenerator()
                    6 -> showShortcutReference()
                    7 -> createArduinoDiagnosticBundle()
                    8 -> showBoardProfile()
                    9 -> showUsbDiagnostics()
                    10 -> showArduinoProjectReport()
                    11 -> showFirmwareBackups()
                    12 -> confirmClearFirmwareBackups()
                }
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun createArduinoDiagnosticBundle() {
        val device = selectedUsbDevice
        val memory = (lastCompileResult as? AvrCompileResult.Success)?.memoryUsage
        val lastOutput = when (val result = lastCompileResult) {
            is AvrCompileResult.Success -> result.output
            is AvrCompileResult.Failure -> result.output
            is AvrCompileResult.ToolchainUnavailable -> result.message
            null -> "No build has run in this IDE session."
        }
        val report = buildString {
            appendLine("Luma Arduino diagnostic report")
            appendLine("Project: ${project.name}")
            appendLine("Board: ${selectedBoard.fqbn}")
            appendLine("MCU: ${selectedBoard.mcu}")
            appendLine("Programmer: ${selectedBoard.programmer}")
            appendLine("Upload baud: ${selectedBoard.uploadBaudRate}")
            appendLine("Saved USB port: ${savedPortName ?: "None"}")
            appendLine("Active USB: ${device?.deviceName ?: "None"}")
            appendLine("Active USB VID/PID: ${device?.vendorId ?: "-"}/${device?.productId ?: "-"}")
            appendLine("Libraries: ${project.libraries.joinToString().ifBlank { "None" }}")
            appendLine("Project files: ${inoFileStore.projectFiles(project).size}")
            appendLine("Firmware backups: ${inoFileStore.firmwareBackupFiles(project).size}")
            memory?.let {
                appendLine("Flash usage: ${it.flashBytes}/${selectedBoard.memoryCapacity.flashBytes}")
                appendLine("RAM usage: ${it.sramBytes}/${selectedBoard.memoryCapacity.sramBytes}")
            }
            append("Created: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
        }
        runCatching {
            ArduinoDiagnosticBundleWriter.create(
                destination = inoFileStore.firmwareBackupFile(project, "diagnostic", "zip"),
                report = report,
                buildOutput = lastOutput
            )
        }.onSuccess { zipFile ->
            pendingDiagnosticZipFile = zipFile
            exportDiagnosticZipLauncher.launch(zipFile.name)
        }.onFailure {
            Toast.makeText(this, R.string.ide_arduino_diagnostic_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showBoardCatalog() {
        val boards = AvrBoard.entries.toList()
        val labels = boards.map { board ->
            val memory = board.memoryCapacity
            "${board.fqbn}\n${board.mcu} • Flash ${memory.flashBytes / 1024} KB • RAM ${memory.sramBytes / 1024} KB"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_arduino_board_catalog)
            .setSingleChoiceItems(labels, boards.indexOf(selectedBoard)) { dialog, which ->
                selectedBoard = boards[which]
                persistProjectHardwareState()
                setStatus(getString(R.string.ide_select_board), selectedBoard.fqbn)
                Toast.makeText(this, getString(R.string.ide_arduino_board_selected, selectedBoard.fqbn), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun showProjectFileMap() {
        val files = inoFileStore.projectFiles(project)
        val message = if (files.isEmpty()) getString(R.string.ide_arduino_project_files_empty) else buildString {
            appendLine(getString(R.string.ide_arduino_project_files_count, files.size))
            appendLine()
            files.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }.forEach { file ->
                appendLine("${inoFileStore.relativePath(project, file)} • ${formatByteCount(file.length())}")
            }
        }
        showCopyableTextDialog(getString(R.string.ide_arduino_project_files), message)
    }

    private fun showProjectSearch() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.ide_arduino_search_hint)
            setSingleLine(true)
        }
        val layout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.ide_arduino_search_project)
            addView(input)
            setPadding(dp(20), 0, dp(20), 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_arduino_search_project)
            .setView(layout)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_arduino_search) { _, _ ->
                val query = input.text?.toString().orEmpty().trim()
                if (query.isBlank()) return@setPositiveButton
                val matches = inoFileStore.projectFiles(project).flatMap { file ->
                    runCatching {
                        file.readLines().mapIndexedNotNull { index, line ->
                            if (line.contains(query, ignoreCase = true)) "${inoFileStore.relativePath(project, file)}:${index + 1}: ${line.trim()}" else null
                        }
                    }.getOrDefault(emptyList())
                }.take(100)
                showCopyableTextDialog(
                    getString(R.string.ide_arduino_search_results),
                    matches.joinToString("\n").ifBlank { getString(R.string.ide_arduino_search_empty) }
                )
            }
            .show()
    }

    private fun restoreLatestProjectBackup() {
        val backup = backupStore.latest(project)
        if (backup == null || !backup.isFile) {
            Toast.makeText(this, R.string.ide_arduino_restore_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_arduino_restore_point)
            .setMessage(getString(R.string.ide_arduino_restore_message, backup.name))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_arduino_restore) { _, _ ->
                saveActiveFileTab()
                val restored = backupStore.restoreLatest(project)
                if (restored == null) {
                    Toast.makeText(this, R.string.ide_arduino_restore_failed, Toast.LENGTH_SHORT).show()
                } else {
                    binding.codeEditor.setText(restored)
                    binding.codeEditor.setSelection(0)
                    saveActiveFileTab()
                    Toast.makeText(this, R.string.ide_arduino_restore_done, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showExampleGenerator() {
        val examples = listOf(
            "Blink" to """void setup() {\n  pinMode(LED_BUILTIN, OUTPUT);\n}\n\nvoid loop() {\n  digitalWrite(LED_BUILTIN, HIGH);\n  delay(1000);\n  digitalWrite(LED_BUILTIN, LOW);\n  delay(1000);\n}\n""",
            "Serial Echo" to """void setup() {\n  Serial.begin(9600);\n}\n\nvoid loop() {\n  if (Serial.available()) {\n    Serial.write(Serial.read());\n  }\n}\n""",
            "Button + LED" to """const int buttonPin = 2;\nconst int ledPin = LED_BUILTIN;\n\nvoid setup() {\n  pinMode(buttonPin, INPUT_PULLUP);\n  pinMode(ledPin, OUTPUT);\n}\n\nvoid loop() {\n  digitalWrite(ledPin, digitalRead(buttonPin) == LOW ? HIGH : LOW);\n}\n"""
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_arduino_example_generator)
            .setItems(examples.map { it.first }.toTypedArray()) { _, which ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(examples[which].first)
                    .setMessage(R.string.ide_arduino_example_replace_message)
                    .setNegativeButton(R.string.cancel_action, null)
                    .setPositiveButton(R.string.ide_arduino_example_use) { _, _ ->
                        binding.codeEditor.setText(examples[which].second)
                        binding.codeEditor.setSelection(0)
                        saveActiveFileTab()
                    }
                    .show()
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun showShortcutReference() {
        showCopyableTextDialog(
            getString(R.string.ide_arduino_shortcuts),
            "Ctrl+S  Save active file\nCtrl+F  Find / Replace\nCtrl+R  Check Code\nCtrl+U  Upload Code\nCtrl+L  Library Manager\n\nSerial Monitor shortcuts follow the device keyboard defaults."
        )
    }

    private fun showBoardProfile() {
        val details = buildString {
            appendLine("FQBN: ${selectedBoard.fqbn}")
            appendLine("MCU: ${selectedBoard.mcu}")
            appendLine("Variant: ${selectedBoard.variant}")
            appendLine("CPU: ${selectedBoard.cpuHz} Hz")
            appendLine("Programmer: ${selectedBoard.programmer}")
            appendLine("Upload baud: ${selectedBoard.uploadBaudRate}")
            appendLine("Flash page: ${selectedBoard.flashPageSize} bytes")
            append("1200-touch: ${selectedBoard.uses1200Touch}")
        }
        showCopyableTextDialog(getString(R.string.ide_arduino_board_profile), details)
    }

    private fun showUsbDiagnostics() {
        val device = selectedUsbDevice
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val details = if (device == null) {
            getString(R.string.ide_arduino_usb_none)
        } else {
            buildString {
                appendLine("Path: ${device.deviceName}")
                appendLine("Product: ${device.productName ?: "Unknown"}")
                appendLine("Vendor ID: ${device.vendorId}")
                appendLine("Product ID: ${device.productId}")
                appendLine("Device class: ${device.deviceClass}")
                appendLine("Interfaces: ${device.interfaceCount}")
                appendLine("Permission: ${manager.hasPermission(device)}")
                append("Saved port: ${savedPortName ?: "None"}")
            }
        }
        showCopyableTextDialog(getString(R.string.ide_arduino_usb_diagnostics), details)
    }

    private fun showArduinoProjectReport() {
        val files = inoFileStore.projectFiles(project)
        val firmware = inoFileStore.firmwareBackupFiles(project)
        val report = buildString {
            appendLine("Luma Arduino Project Report")
            appendLine("Project: ${project.name}")
            appendLine("Board: ${selectedBoard.fqbn}")
            appendLine("USB port: ${savedPortName ?: "Not selected"}")
            appendLine("Libraries: ${project.libraries.ifEmpty { listOf("None") }.joinToString()}")
            appendLine("Files (${files.size}):")
            files.forEach { file -> appendLine("- ${inoFileStore.relativePath(project, file)} • ${formatByteCount(file.length())}") }
            appendLine("Firmware backups: ${firmware.size} • ${formatByteCount(firmware.sumOf { it.length() })}")
            appendLine("Auto-save: $saveAutoEnabled")
            append("Generated: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}")
        }
        pendingArduinoReportText = report
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_arduino_project_report)
            .setMessage(report)
            .setNegativeButton(R.string.cancel_action, null)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Luma Arduino project report", report))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.ide_arduino_export_report) { _, _ ->
                exportArduinoReportLauncher.launch("${project.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "arduino_project" }}_report.txt")
            }
            .show()
    }

    private fun showFirmwareBackups() {
        val backups = inoFileStore.firmwareBackupFiles(project)
        if (backups.isEmpty()) {
            Toast.makeText(this, R.string.ide_arduino_firmware_backups_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val details = backups.joinToString("\n") { file -> "${file.name} • ${formatByteCount(file.length())}" }
        showCopyableTextDialog(getString(R.string.ide_arduino_firmware_backups), details)
    }

    private fun confirmClearFirmwareBackups() {
        val count = inoFileStore.firmwareBackupFiles(project).size
        if (count == 0) {
            Toast.makeText(this, R.string.ide_arduino_firmware_backups_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_arduino_clear_firmware_backups)
            .setMessage(getString(R.string.ide_arduino_clear_firmware_backups_message, count))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_arduino_clear_firmware_backups) { _, _ ->
                val deleted = inoFileStore.clearFirmwareBackups(project)
                Toast.makeText(this, getString(R.string.ide_arduino_clear_firmware_backups_done, deleted), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCopyableTextDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel_action, null)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, message))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatByteCount(value: Long): String = when {
        value < 1024L -> "$value B"
        value < 1024L * 1024L -> "%.1f KB".format(java.util.Locale.ROOT, value / 1024.0)
        else -> "%.1f MB".format(java.util.Locale.ROOT, value / (1024.0 * 1024.0))
    }

    private fun showCustomAvrdudeCommands() {
        val store = CustomAvrdudeCommandStore(this)
        val commands = store.load()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(2))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.ide_avrdude_command_intro)
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })
        if (commands.isEmpty()) {
            root.addView(TextView(this).apply {
                text = getString(R.string.ide_avrdude_command_empty)
                setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_secondary))
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            commands.forEach { command ->
                root.addView(MaterialButton(this).apply {
                    text = "${command.name}\n${command.arguments}"
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { showCustomAvrdudeCommandActions(command) }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_custom_avrdude_command)
            .setView(root)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_avrdude_command_new) { _, _ -> showCustomAvrdudeCommandEditor() }
            .show()
    }

    private fun showCustomAvrdudeCommandActions(command: CustomAvrdudeCommand) {
        val actions = arrayOf(
            getString(R.string.ide_avrdude_command_run),
            getString(R.string.ide_avrdude_command_edit),
            getString(R.string.ide_avrdude_command_delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(command.name)
            .setMessage(command.arguments)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> runCustomAvrdudeCommand(command)
                    1 -> showCustomAvrdudeCommandEditor(command)
                    2 -> confirmDeleteCustomAvrdudeCommand(command)
                }
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun confirmDeleteCustomAvrdudeCommand(command: CustomAvrdudeCommand) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_avrdude_command_delete)
            .setMessage(getString(R.string.ide_avrdude_command_delete_message, command.name))
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_avrdude_command_delete) { _, _ ->
                CustomAvrdudeCommandStore(this).delete(command.id)
                Toast.makeText(this, R.string.ide_avrdude_command_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCustomAvrdudeCommandEditor(existing: CustomAvrdudeCommand? = null) {
        val nameInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(existing?.name.orEmpty())
            setSingleLine(true)
        }
        val argumentsInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(existing?.arguments.orEmpty())
            minLines = 3
            maxLines = 6
            setHorizontallyScrolling(false)
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.ide_avrdude_command_editor_help)
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.ide_avrdude_command_name)
            addView(nameInput)
        })
        root.addView(com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.ide_avrdude_command_arguments)
            setPadding(0, dp(10), 0, 0)
            addView(argumentsInput)
        })
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.ide_avrdude_command_new else R.string.ide_avrdude_command_edit)
            .setView(root)
            .setNegativeButton(R.string.cancel_action, null)
            .setNeutralButton(R.string.ide_avrdude_command_save, null)
            .setPositiveButton(R.string.ide_avrdude_command_save_run, null)
            .create()
        fun saveCommand(): CustomAvrdudeCommand? {
            val name = nameInput.text?.toString().orEmpty().trim()
            val arguments = argumentsInput.text?.toString().orEmpty().trim()
            val valid = runCatching { tokenizeAvrdudeArguments(arguments) }.isSuccess
            if (name.isBlank() || !valid) {
                Toast.makeText(this, R.string.ide_avrdude_command_invalid, Toast.LENGTH_LONG).show()
                return null
            }
            val command = CustomAvrdudeCommand(existing?.id ?: 0L, name, arguments)
            return runCatching {
                CustomAvrdudeCommandStore(this).upsert(command)
                command
            }.getOrElse {
                Toast.makeText(this, it.message ?: getString(R.string.ide_avrdude_command_invalid), Toast.LENGTH_LONG).show()
                null
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (saveCommand() != null) {
                    dialog.dismiss()
                    Toast.makeText(this, R.string.ide_avrdude_command_saved, Toast.LENGTH_SHORT).show()
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                saveCommand()?.let { command ->
                    dialog.dismiss()
                    runCustomAvrdudeCommand(command)
                }
            }
        }
        dialog.show()
    }

    private fun tokenizeAvrdudeArguments(value: String): List<String> {
        require(value.length <= 2_048) { getString(R.string.ide_avrdude_command_too_long) }
        val values = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        value.forEach { character ->
            when {
                quote != null && character == quote -> quote = null
                quote == null && (character == '\'' || character == '"') -> quote = character
                quote == null && character.isWhitespace() -> if (token.isNotEmpty()) {
                    values += token.toString()
                    token.clear()
                }
                character == '\u0000' -> throw IllegalArgumentException(getString(R.string.ide_avrdude_command_invalid))
                else -> token.append(character)
            }
        }
        require(quote == null) { getString(R.string.ide_avrdude_command_invalid_quotes) }
        if (token.isNotEmpty()) values += token.toString()
        require(values.isNotEmpty() && values.size <= 48) { getString(R.string.ide_avrdude_command_too_many_arguments) }
        return values
    }

    private fun runCustomAvrdudeCommand(command: CustomAvrdudeCommand) {
        val device = selectedUsbDevice
        if (device == null) {
            pendingCustomAvrdudeCommand = command
            setStatus(getString(R.string.ide_avrdude_command_select_port))
            selectUsbPort()
            return
        }
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            pendingCustomAvrdudeCommand = command
            requestUsbPermission(device)
            setStatus(getString(R.string.ide_avrdude_command_usb_permission))
            return
        }
        val arguments = runCatching { tokenizeAvrdudeArguments(command.arguments) }.getOrElse {
            Toast.makeText(this, it.message ?: getString(R.string.ide_avrdude_command_invalid), Toast.LENGTH_LONG).show()
            return
        }
        val logView = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Custom command: ${command.name}\nBoard: ${selectedBoard.fqbn}\nPort: ${device.deviceName}\nArguments: ${command.arguments}\n\n"
        }
        val logScroll = android.widget.ScrollView(this).apply { addView(logView, ViewGroup.LayoutParams(-1, -2)) }
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_custom_avrdude_command)
            .setView(logScroll)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Luma custom avrdude log", logView.text))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.ide_avrdude_command_save_zip, null)
            .create()
        progressDialog.setCanceledOnTouchOutside(false)
        progressDialog.setCancelable(false)
        progressDialog.show()
        progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val startedAt = System.currentTimeMillis()
        setStatus(getString(R.string.ide_avrdude_command_running), command.name)
        avrdudeBridge.runCustomCommand(
            AvrdudeCommandRequest(
                board = selectedBoard,
                usbDevice = device,
                arguments = arguments
            ),
            onLog = { line ->
                runOnUiThread {
                    logView.append("$line\n")
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        ) { result ->
            runOnUiThread {
                val duration = System.currentTimeMillis() - startedAt
                buildHistoryStore.add(project.id, BuildHistoryEntry(
                    type = "Custom avrdude",
                    success = result.success,
                    timestamp = System.currentTimeMillis(),
                    durationMs = duration,
                    board = selectedBoard.fqbn,
                    port = device.deviceName,
                    summary = result.output
                ))
                setStatus(getString(if (result.success) R.string.ide_avrdude_command_success else R.string.ide_avrdude_command_failed), command.name)
                runCatching {
                    AvrdudeCommandBundleWriter.create(
                        destination = inoFileStore.firmwareBackupFile(project, "avrdude-command", "zip"),
                        commandName = command.name,
                        arguments = command.arguments,
                        board = selectedBoard,
                        port = device.deviceName,
                        success = result.success,
                        output = result.output
                    )
                }.onSuccess { zipFile ->
                    pendingAvrdudeCommandZipFile = zipFile
                    logView.append("\n${getString(R.string.ide_avrdude_command_zip_ready, zipFile.name)}\n")
                    progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(R.string.ide_avrdude_command_save_zip)
                        setOnClickListener {
                            progressDialog.dismiss()
                            exportAvrdudeCommandZipLauncher.launch(zipFile.name)
                        }
                    }
                }.onFailure { error ->
                    logView.append("\n${getString(R.string.ide_avrdude_command_zip_failed)}: ${error.message}\n")
                    progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(android.R.string.ok)
                        setOnClickListener { progressDialog.dismiss() }
                    }
                }
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun extractFirmware() {
        val device = selectedUsbDevice
        if (device == null) {
            pendingFirmwareReadAfterPermission = true
            setStatus(getString(R.string.ide_extract_select_port))
            selectUsbPort()
            return
        }
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            pendingFirmwareReadAfterPermission = true
            requestUsbPermission(device)
            setStatus(getString(R.string.ide_extract_usb_permission))
            return
        }
        val includeEeprom = com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = getString(R.string.ide_extract_eeprom)
            isChecked = false
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_extract_firmware)
            .setMessage(R.string.ide_extract_warning)
            .setView(includeEeprom)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_extract_start) { _, _ ->
                runFirmwareExtraction(device, includeEeprom.isChecked)
            }
            .show()
    }

    private fun runFirmwareExtraction(device: UsbDevice, includeEeprom: Boolean) {
        val flashFile = inoFileStore.firmwareBackupFile(project, "firmware", "hex")
        val eepromFile = if (includeEeprom) inoFileStore.firmwareBackupFile(project, "eeprom", "eep") else null
        val startedAt = System.currentTimeMillis()
        val logView = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Preparing Firmware extraction...\nBoard: ${selectedBoard.fqbn}\nPort: ${device.deviceName}\n"
        }
        val logScroll = android.widget.ScrollView(this).apply {
            addView(logView, ViewGroup.LayoutParams(-1, -2))
        }
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_extract_firmware)
            .setView(logScroll)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Luma firmware extraction log", logView.text))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.ide_extract_save_zip, null)
            .create()
        progressDialog.setCanceledOnTouchOutside(false)
        progressDialog.setCancelable(false)
        progressDialog.show()
        progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
        setStatus(getString(R.string.ide_extract_running), selectedBoard.fqbn)
        avrdudeBridge.readFirmware(
            AvrReadRequest(
                flashOutputFile = flashFile,
                eepromOutputFile = eepromFile,
                board = selectedBoard,
                usbDevice = device
            ),
            onLog = { line ->
                runOnUiThread {
                    logView.append("$line\n")
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        ) { result ->
            runOnUiThread {
                val duration = System.currentTimeMillis() - startedAt
                buildHistoryStore.add(project.id, BuildHistoryEntry(
                    type = "Firmware read",
                    success = result.success,
                    timestamp = System.currentTimeMillis(),
                    durationMs = duration,
                    board = selectedBoard.fqbn,
                    port = device.deviceName,
                    summary = result.output
                ))
                if (result.success) {
                    setStatus(getString(R.string.ide_extract_success), flashFile.name)
                    runCatching {
                        FirmwareBundleWriter.create(
                            destination = inoFileStore.firmwareBackupFile(project, "firmware-extraction", "zip"),
                            flash = result.flashOutputFile,
                            eeprom = result.eepromOutputFile?.takeIf { it.isFile },
                            board = selectedBoard,
                            port = device.deviceName,
                            log = result.output
                        )
                    }.onSuccess { zipFile ->
                        pendingFirmwareZipFile = zipFile
                        logView.append("\n${getString(R.string.ide_extract_zip_ready, zipFile.name)}\n")
                        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                        progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                            isEnabled = true
                            text = getString(R.string.ide_extract_save_zip)
                            setOnClickListener {
                                progressDialog.dismiss()
                                exportFirmwareZipLauncher.launch(zipFile.name)
                            }
                        }
                    }.onFailure { error ->
                        logView.append("\n${getString(R.string.ide_extract_zip_failed)}: ${error.message}\n")
                        progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                            isEnabled = true
                            text = getString(android.R.string.ok)
                            setOnClickListener { progressDialog.dismiss() }
                        }
                    }
                } else {
                    setStatus(getString(R.string.ide_extract_failed))
                    progressDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        text = getString(android.R.string.ok)
                        setOnClickListener { progressDialog.dismiss() }
                    }
                }
                val files = buildString {
                    append(getString(R.string.ide_extract_flash_path, result.flashOutputFile.absolutePath))
                    result.eepromOutputFile?.takeIf { it.isFile }?.let {
                        append("\n").append(getString(R.string.ide_extract_eeprom_path, it.absolutePath))
                    }
                }
                logView.append("\n$files\n")
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun uploadCode() {
        val uploadStartedAt = System.currentTimeMillis()
        val device = selectedUsbDevice
        if (device == null) {
            pendingUploadAfterPermission = true
            setStatus("Select a USB port before uploading")
            selectUsbPort()
            return
        }
        val manager = getSystemService(USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            pendingUploadAfterPermission = true
            requestUsbPermission(device)
            setStatus("USB permission is required before upload")
            return
        }
        saveActiveFileTab()
        val sourceCode = binding.codeEditor.text?.toString().orEmpty()
        val libraryDirectories = ArduinoLibraryStorage.directoriesFor(this, project.libraries, sourceCode)
            .map { it.absolutePath }
        val logView = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Preparing AVR-GCC build...\nBoard: ${selectedBoard.fqbn}\nPort: ${device.deviceName}\n"
        }
        val logScroll = android.widget.ScrollView(this).apply { addView(logView, ViewGroup.LayoutParams(-1, -2)) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Upload Code")
            .setView(logScroll)
            .setNegativeButton(R.string.cancel_action, null)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Luma upload log", logView.text))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
        setStatus("Building HEX for upload", selectedBoard.fqbn)
        avrCompiler.compile(
            AvrCompileRequest(
                projectName = project.name,
                code = sourceCode,
                board = selectedBoard,
                libraryDirectories = libraryDirectories
            )
        ) { result ->
            runOnUiThread {
                when (result) {
                    is AvrCompileResult.Success -> {
                        val hexPath = result.hexFilePath
                        logView.text = "${logView.text}\n${result.output}\n"
                        if (hexPath.isNullOrBlank()) {
                            logView.append("ERROR: Compiler did not produce a HEX file.\n")
                            setStatus("HEX generation failed")
                            return@runOnUiThread
                        }
                        logView.append("Starting avrdude upload through USB bridge...\n")
                        setStatus("Uploading HEX", selectedBoard.fqbn)
                        avrdudeBridge.upload(
                            AvrUploadRequest(File(hexPath), selectedBoard, device)
                        ) { uploadResult ->
                            runOnUiThread {
                                logView.append("\n${uploadResult.output}")
                                buildHistoryStore.add(project.id, BuildHistoryEntry(
                                    type = "Upload",
                                    success = uploadResult.success,
                                    timestamp = System.currentTimeMillis(),
                                    durationMs = System.currentTimeMillis() - uploadStartedAt,
                                    board = selectedBoard.fqbn,
                                    port = device.deviceName,
                                    summary = uploadResult.output
                                ))
                                setStatus(
                                    if (uploadResult.success) "Upload complete" else "Upload failed",
                                    "${selectedBoard.fqbn} • ${device.deviceName}"
                                )
                            }
                        }
                    }
                    is AvrCompileResult.Failure -> {
                        logView.text = "${logView.text}\n${result.output.ifBlank { "AVR-GCC build failed" }}"
                        buildHistoryStore.add(project.id, BuildHistoryEntry(
                            type = "Upload",
                            success = false,
                            timestamp = System.currentTimeMillis(),
                            durationMs = System.currentTimeMillis() - uploadStartedAt,
                            board = selectedBoard.fqbn,
                            port = device.deviceName,
                            summary = result.output.ifBlank { "AVR-GCC build failed" }
                        ))
                        setStatus("HEX build failed")
                    }
                    is AvrCompileResult.ToolchainUnavailable -> {
                        logView.text = "${logView.text}\n${result.message}"
                        buildHistoryStore.add(project.id, BuildHistoryEntry(
                            type = "Upload",
                            success = false,
                            timestamp = System.currentTimeMillis(),
                            durationMs = System.currentTimeMillis() - uploadStartedAt,
                            board = selectedBoard.fqbn,
                            port = device.deviceName,
                            summary = result.message
                        ))
                        setStatus("AVR toolchain unavailable")
                    }
                }
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun checkCode() {
        val progress = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_check_code)
            .setMessage(R.string.ide_compiler_running)
            .setView(progress)
            .setNegativeButton(R.string.cancel_action, null)
            .create()
        dialog.show()
        val sourceCode = binding.codeEditor.text?.toString().orEmpty()
        val libraryDirectories = ArduinoLibraryStorage
            .directoriesFor(this, project.libraries, sourceCode)
            .map { it.absolutePath }
        lastBuildStartedAt = System.currentTimeMillis()
        lastBuildLibraryDirectories = libraryDirectories
        lastBuildCommand = buildCommandPreview(libraryDirectories)
        avrCompiler.verify(
            request = AvrCompileRequest(
                projectName = project.name,
                code = sourceCode,
                board = selectedBoard,
                libraryDirectories = libraryDirectories
            )
        ) { result ->
            runOnUiThread {
                if (!isFinishing) {
                    dialog.dismiss()
                    lastBuildDurationMs = System.currentTimeMillis() - lastBuildStartedAt
                    lastCompileResult = result
                    buildHistoryStore.add(project.id, BuildHistoryEntry(
                        type = "Check",
                        success = result is AvrCompileResult.Success,
                        timestamp = System.currentTimeMillis(),
                        durationMs = lastBuildDurationMs,
                        board = selectedBoard.fqbn,
                        port = savedPortName.orEmpty(),
                        summary = when (result) {
                            is AvrCompileResult.Success -> result.output
                            is AvrCompileResult.Failure -> result.output
                            is AvrCompileResult.ToolchainUnavailable -> result.message
                        }
                    ))
                    showCompilerResult(result)
                }
            }
        }
    }

    private fun buildCommandPreview(libraryDirectories: List<String>): String {
        val root = File(filesDir, "toolchain").absolutePath
        val specs = "$root/lib/gcc/avr/7.3.0/device-specs/specs-${selectedBoard.mcu}"
        val args = mutableListOf(
            "$root/bin/avr-g++",
            "-std=gnu++11",
            "-specs=$specs",
            "-mmcu=${selectedBoard.mcu}",
            "-DF_CPU=${selectedBoard.cpuHz}L",
            "-DARDUINO=10819",
            "-DARDUINO_ARCH_AVR",
            "-Os",
            "-fsyntax-only",
            "-fdiagnostics-color=never",
            "-fno-diagnostics-show-caret",
            "-Wall",
            "-Wextra",
            "-B $root/bin/",
            "-B $root/libexec/gcc/avr/7.3.0/",
            "-L $root/avr/lib",
            "-L $root/lib/gcc/avr/7.3.0",
            "-I $root/avr-core/cores/arduino",
            "-I $root/avr-core/variants/${selectedBoard.variant}",
            "-I $root/avr/include",
            "-I $root/lib/gcc/avr/7.3.0/include",
            "<work-dir>/<active-file>.cpp"
        )
        libraryDirectories.forEach { args += "-I $it (recursive)" }
        args += "<library C/C++ sources resolved recursively>"
        return args.joinToString(" \\\n")
    }

    private fun showBuildDetails() {
        saveActiveFileTab()
        val source = binding.codeEditor.text?.toString().orEmpty()
        val result = lastCompileResult
        val root = File(filesDir, "toolchain").absolutePath
        val details = buildString {
            append("========== BUILD SUMMARY ==========").append('\n')
            append("STATUS: ").append(
                when (result) {
                    null -> "NOT_RUN"
                    is AvrCompileResult.Success -> "SUCCESS"
                    is AvrCompileResult.Failure -> "FAILURE"
                    is AvrCompileResult.ToolchainUnavailable -> "TOOLCHAIN_UNAVAILABLE"
                }
            ).append('\n')
            append("STARTED_AT_MS: ").append(lastBuildStartedAt).append('\n')
            append("DURATION_MS: ").append(lastBuildDurationMs).append('\n')
            append("FILE: ").append(openFileTabs.getOrNull(activeFileTab)?.fileName ?: "unknown").append('\n')
            append("PROJECT: ").append(project.name).append('\n')
            append("BOARD: ").append(selectedBoard.fqbn).append('\n')
            append("MCU: ").append(selectedBoard.mcu).append('\n')
            append("VARIANT: ").append(selectedBoard.variant).append('\n')
            append("CPU: ").append(selectedBoard.cpuHz).append(" Hz\n")
            append("ABI: ").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown").append('\n')
            append("TOOLCHAIN_ROOT: ").append(root).append('\n')
            append("COMPILER: ").append(File(root, "bin/avr-g++").absolutePath).append('\n')
            append("NATIVE_ENGINE: ").append(result?.let {
                when (it) {
                    is AvrCompileResult.Success -> it.nativeEngineVersion
                    is AvrCompileResult.Failure -> it.nativeEngineVersion
                    is AvrCompileResult.ToolchainUnavailable -> "unavailable"
                }
            } ?: "not run").append('\n')
            append("LIBRARIES: ").append(project.libraries.joinToString().ifBlank { "none" }).append('\n')
            append("LIBRARY_DIRECTORIES: ").append(lastBuildLibraryDirectories.joinToString(" | ").ifBlank { "none" }).append('\n')
            append("GCC_EXEC_PREFIX: ").append(root).append("/lib/gcc/").append('\n')
            append("COMPILER_PATH: ").append(root).append("/bin:").append(root).append("/libexec/gcc/avr/7.3.0").append('\n')
            append("LIBRARY_PATH: ").append(root).append("/lib/gcc/avr/7.3.0:").append(root).append("/avr/lib:").append(root).append("/bin").append('\n')
            append("\n========== FULL COMMAND ==========").append('\n')
            append(lastBuildCommand.ifBlank { "No Check Code command has run yet." }).append('\n')
            append("\n========== FULL CODE ==========").append('\n')
            append(source).append('\n')
            append("\n========== FULL AVR-GCC OUTPUT ==========").append('\n')
            when (result) {
                null -> append("No Check Code run in this session.\n")
                is AvrCompileResult.ToolchainUnavailable -> append(result.message).append('\n')
                is AvrCompileResult.Success -> append(result.output).append('\n')
                is AvrCompileResult.Failure -> append(result.output).append('\n')
            }
            append("\n========== ALL DIAGNOSTICS ==========").append('\n')
            when (result) {
                is AvrCompileResult.Success -> append("No error diagnostics. Warnings, if any, are included above.\n")
                is AvrCompileResult.Failure -> result.diagnostics.forEach { diagnostic ->
                    append(diagnostic.severity.name)
                        .append(" line ").append(diagnostic.line)
                        .append(", col ").append(diagnostic.column)
                        .append(": ").append(diagnostic.message).append('\n')
                }
                is AvrCompileResult.ToolchainUnavailable -> append(result.message).append('\n')
                null -> append("No diagnostics.\n")
            }
        }
        showCopyableCompilerDialog(getString(R.string.ide_build_details), details)
    }

    private fun showMemoryAnalyzer() {
        val success = lastCompileResult as? AvrCompileResult.Success
        val usage = success?.memoryUsage
        if (usage == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ide_memory_analyzer)
                .setMessage(R.string.ide_memory_analyzer_run_build)
                .setNegativeButton(R.string.cancel_action, null)
                .setPositiveButton(R.string.ide_check_code) { _, _ -> checkCode() }
                .show()
            return
        }
        val capacity = selectedBoard.memoryCapacity
        fun percent(value: Int, limit: Int): String = "%.1f%%".format(java.util.Locale.ROOT, value * 100.0 / limit.coerceAtLeast(1))
        val text = buildString {
            appendLine("Board: ${selectedBoard.fqbn}")
            appendLine()
            appendLine("Flash: ${usage.flashBytes} / ${capacity.flashBytes} bytes (${percent(usage.flashBytes, capacity.flashBytes)})")
            appendLine("RAM: ${usage.sramBytes} / ${capacity.sramBytes} bytes (${percent(usage.sramBytes, capacity.sramBytes)})")
            appendLine("EEPROM capacity: ${capacity.eepromBytes} bytes")
            appendLine()
            appendLine("Sections:")
            usage.sections.toSortedMap().forEach { (name, bytes) -> appendLine("$name: $bytes bytes") }
        }
        showCopyableTextDialog(getString(R.string.ide_memory_analyzer), text)
    }

    private fun showCompilerResult(result: AvrCompileResult) {
        when (result) {
            is AvrCompileResult.ToolchainUnavailable -> {
                setStatus(getString(R.string.ide_compiler_missing))
                showCopyableCompilerDialog(
                    title = getString(R.string.ide_compiler_missing),
                    text = result.message
                )
            }
            is AvrCompileResult.Success -> {
                setStatus(getString(R.string.ide_compile_ok))
                showCopyableCompilerDialog(
                    title = getString(R.string.ide_compile_ok),
                    text = result.output.ifBlank { "Native engine: ${result.nativeEngineVersion}" }
                )
            }
            is AvrCompileResult.Failure -> {
                val diagnosticText = result.diagnostics.joinToString("\n") {
                    "${it.severity.name}  line ${it.line}, col ${it.column}: ${it.message}"
                }
                val firstDiagnostic = result.diagnostics.firstOrNull { it.line > 0 }
                setStatus(getString(R.string.ide_compile_error))
                val arabicSummary = result.diagnostics.joinToString("\n") { diagnostic ->
                    getString(
                        R.string.ide_arabic_diagnostic_line,
                        diagnostic.line,
                        diagnostic.column,
                        translateDiagnostic(diagnostic.message)
                    )
                }
                val copyText = (
                    getString(R.string.ide_arabic_diagnostic_header) + "\n" +
                        arabicSummary + "\n\n" +
                        result.output.ifBlank { diagnosticText }
                    ).takeLast(16_000)
                val builder = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.ide_compile_error)
                    .setMessage(copyText)
                    .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("AVR-GCC", copyText))
                        Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
                    }
                    .setPositiveButton(android.R.string.ok, null)
                if (firstDiagnostic != null) {
                    builder.setNegativeButton(getString(R.string.ide_go_to_line, firstDiagnostic.line)) { _, _ ->
                        focusDiagnostic(firstDiagnostic.line, firstDiagnostic.column)
                    }
                }
                builder.show()
            }
        }
    }

    private fun translateDiagnostic(message: String): String {
        val normalized = message.lowercase()
        return when {
            "no such file or directory" in normalized -> getString(
                R.string.ide_diagnostic_missing_file,
                message.substringBefore(':')
            )
            "was not declared in this scope" in normalized || "not declared" in normalized ->
                getString(R.string.ide_diagnostic_undeclared)
            "expected" in normalized -> getString(R.string.ide_diagnostic_expected)
            "redefinition" in normalized || "conflicting declaration" in normalized ->
                getString(R.string.ide_diagnostic_redefinition)
            "does not name a type" in normalized -> getString(R.string.ide_diagnostic_type)
            else -> getString(R.string.ide_diagnostic_generic, message)
        }
    }

    private fun focusDiagnostic(line: Int, column: Int) {
        val lineIndex = (line - 1).coerceAtLeast(0)
        val layout = binding.codeEditor.layout ?: return
        if (lineIndex >= layout.lineCount) return
        val start = layout.getLineStart(lineIndex)
        val end = layout.getLineEnd(lineIndex)
        val offset = (start + column.coerceAtLeast(1) - 1).coerceIn(start, end)
        binding.codeEditor.requestFocus()
        binding.codeEditor.setSelection(offset)
        binding.codeEditor.post {
            binding.codeEditor.scrollTo(0, layout.getLineTop(lineIndex).coerceAtLeast(0))
        }
    }

    private fun showCopyableCompilerDialog(title: String, text: String) {
        val copyText = text.ifBlank { getString(R.string.ide_no_diagnostics) }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(copyText)
            .setNeutralButton(R.string.ide_copy_diagnostics) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AVR-GCC", copyText))
                Toast.makeText(this, R.string.ide_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun selectBoard() {
        val boards = arrayOf(
            "Arduino Uno", "Arduino Uno R3", "Arduino Uno WiFi Rev2", "Arduino Nano",
            "Arduino Nano Every", "Arduino Nano 33 IoT", "Arduino Nano 33 BLE",
            "Arduino Mega 2560", "Arduino Mega ADK", "Arduino Leonardo", "Arduino Micro",
            "Arduino Pro Mini", "Arduino Pro Micro", "Arduino Due", "Arduino Zero",
            "Arduino MKR WiFi 1010", "Arduino MKR Uno", "Arduino Robot",
            "Elegoo UNO R3", "Elegoo Mega 2560", "Keyestudio UNO", "Keyestudio Mega",
            "WAVGAT UNO R3", "WeMos D1 mini", "ESP8266 NodeMCU", "ESP32 Dev Module"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_select_board)
            .setItems(boards) { _, which ->
                selectedBoard = when {
                    boards[which].contains("Mega") -> AvrBoard.MEGA
                    boards[which].contains("Leonardo") -> AvrBoard.LEONARDO
                    boards[which].contains("Micro") -> AvrBoard.MICRO
                    boards[which].contains("Pro Mini") -> AvrBoard.PRO_MINI
                    boards[which].contains("Nano") -> AvrBoard.NANO
                    else -> AvrBoard.UNO
                }
                projectStatePreferences().edit()
                    .putString(boardStateKey(), selectedBoard.name)
                    .apply()
                setStatus(getString(R.string.ide_board_selected, boards[which]))
            }
            .show()
    }

    private fun selectUsbPort() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        val portList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val refreshButton = MaterialButton(this).apply {
            text = getString(R.string.ide_refresh_ports)
            isAllCaps = false
            setOnClickListener { refreshUsbPorts(portList, usbDialog) }
        }
        root.addView(refreshButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(portList, LinearLayout.LayoutParams(-1, -2))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_select_port)
            .setView(root)
            .setNegativeButton(R.string.cancel_action, null)
            .create()
        usbDialog = dialog
        dialog.setOnShowListener { refreshUsbPorts(portList, dialog) }
        dialog.setOnDismissListener { usbDialog = null }
        dialog.show()
    }

    private fun refreshUsbPorts(portList: LinearLayout, dialog: androidx.appcompat.app.AlertDialog?) {
        portList.removeAllViews()
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.toList()
        if (devices.isEmpty()) {
            portList.addView(TextView(this).apply {
                text = getString(R.string.ide_no_usb)
                setTextColor(ContextCompat.getColor(this@IdeActivity, R.color.text_secondary))
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        devices.forEach { device ->
            val isSavedPort = savedPortName == device.deviceName
            if (isSavedPort && selectedUsbDevice == null) selectedUsbDevice = device
            val portButton = MaterialButton(this).apply {
                text = (if (isSavedPort) "✓ " else "") +
                    "${device.deviceName}  •  ${device.productName ?: "USB device"}"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    selectedUsbDevice = device
                    savedPortName = device.deviceName
                    persistProjectHardwareState()
                    val manager = getSystemService(USB_SERVICE) as UsbManager
                    val shouldResumeUpload = pendingUploadAfterPermission
                    val customCommand = pendingCustomAvrdudeCommand
                    val advancedRun = pendingAdvancedAvrdudeRun
                    val shouldExtractBoardData = pendingBoardDataAfterPermission
                    val shouldTestConnection = pendingBoardConnectionTest
                    if (!manager.hasPermission(device)) {
                        requestUsbPermission(device)
                    }
                    dialog?.dismiss()
                    if (shouldResumeUpload && manager.hasPermission(device)) {
                        pendingUploadAfterPermission = false
                        binding.root.post { if (!isFinishing) uploadCode() }
                    }
                    if (customCommand != null && manager.hasPermission(device)) {
                        pendingCustomAvrdudeCommand = null
                        binding.root.post { if (!isFinishing) runCustomAvrdudeCommand(customCommand) }
                    }
                    if (advancedRun != null && manager.hasPermission(device)) {
                        pendingAdvancedAvrdudeRun = null
                        binding.root.post { if (!isFinishing) runAdvancedAvrdude(advancedRun) }
                    }
                    if (shouldExtractBoardData && manager.hasPermission(device)) {
                        pendingBoardDataAfterPermission = false
                        binding.root.post { if (!isFinishing) extractCurrentBoardData() }
                    }
                    if (shouldTestConnection && manager.hasPermission(device)) {
                        pendingBoardConnectionTest = false
                        binding.root.post { if (!isFinishing) testBoardConnection() }
                    }
                }
            }
            portList.addView(portButton, LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(6)
            })
        }
    }

    private fun requestUsbPermission(device: android.hardware.usb.UsbDevice) {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        selectedUsbDevice = device
        savedPortName = device.deviceName
        persistProjectHardwareState()
        if (manager.hasPermission(device)) {
            permissionRequestInFlight = false
            setStatus("USB: ${device.deviceName}", "${selectedBoard.fqbn} • ${device.deviceName}")
            return
        }
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        val intent = Intent(usbPermissionAction).setPackage(packageName)
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.requestPermission(device, permissionIntent)
        setStatus(getString(R.string.ide_usb_permission_required))
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(usbPermissionAction)
        ContextCompat.registerReceiver(this, usbPermissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun showSerialMonitor() {
        val intent = Intent(this, SerialMonitorActivity::class.java)
        selectedUsbDevice?.let { intent.putExtra(SerialMonitorActivity.EXTRA_USB_DEVICE, it) }
        startActivity(intent)
    }

    private fun showLibraryCoreReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_library_core_reset_title)
            .setMessage(R.string.ide_library_core_reset_message)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.ide_library_core_reset_action) { _, _ -> resetLibraryCore() }
            .show()
    }

    private fun resetLibraryCore() {
        val progress = android.widget.ProgressBar(this).apply { isIndeterminate = true }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_library_core_reset_title)
            .setMessage(R.string.ide_library_core_reset_running)
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()
        setStatus(getString(R.string.ide_library_core_reset_running))
        Thread {
            val result = AvrToolchainReset.resetAndReload(this)
            runOnUiThread {
                if (!isFinishing) {
                    dialog.dismiss()
                    if (result.success) {
                        setStatus(getString(R.string.ide_library_core_reset_done))
                        showCopyableCompilerDialog(getString(R.string.ide_library_core_reset_done), result.message)
                    } else {
                        setStatus(getString(R.string.ide_library_core_reset_failed, result.message))
                        showCopyableCompilerDialog(
                            getString(R.string.ide_library_core_reset_title),
                            getString(R.string.ide_library_core_reset_failed, result.message)
                        )
                    }
                }
            }
        }.start()
    }

    private fun openLibraryManager() {
        startActivity(
            Intent(this, LibraryActivity::class.java)
                .putExtra(LibraryActivity.EXTRA_PROJECT_ID, project.id)
        )
    }

    private fun selectLibrary() {
        val libraries = arrayOf("LiquidCrystal", "Servo", "Ultrasonic", "DHT sensor library", "Wire")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_select_library)
            .setItems(libraries) { _, which -> setStatus("Library: ${libraries[which]}") }
            .show()
    }

    private fun showEditorSettings() {
        val sizes = arrayOf("12", "14", "16", "18")
        val preferences = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
        val current = preferences.getFloat(
            LumaApplication.EDITOR_FONT_SIZE_KEY,
            LumaApplication.DEFAULT_EDITOR_FONT_SIZE
        )
        val checked = sizes.indexOfFirst { it.toFloat() == current }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ide_settings_editor)
            .setSingleChoiceItems(sizes, checked) { dialog, which ->
                val size = sizes[which].toFloat()
                binding.codeEditor.textSize = size
                preferences.edit().putFloat(LumaApplication.EDITOR_FONT_SIZE_KEY, size).apply()
                dialog.dismiss()
            }
            .show()
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(lastRecordedCode)
        isHistoryChange = true
        val previous = undoStack.removeLast()
        binding.codeEditor.applyCode(previous)
        isHistoryChange = false
        lastRecordedCode = previous
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(lastRecordedCode)
        isHistoryChange = true
        val next = redoStack.removeLast()
        binding.codeEditor.applyCode(next)
        isHistoryChange = false
        lastRecordedCode = next
    }

    override fun onPause() {
        if (::inoFileStore.isInitialized) saveActiveFileTab()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::inoFileStore.isInitialized) {
            libraryExampleGroups = ArduinoLibraryStorage.exampleGroups(this)
        }
    }

    override fun onDestroy() {
        backupHandler.removeCallbacks(delayedBackup)
        suggestionWindow?.dismiss()
        if (::inoFileStore.isInitialized) saveActiveFileTab()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { avrCompiler.close() }
        runCatching { avrdudeBridge.close() }
        super.onDestroy()
    }

    private fun setStatus(status: String, trail: String = status) {
        binding.ideStatus.text = status
        binding.sectionTrail.text = trail
    }

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_PROJECT_NAME = "project_name"
        const val EXTRA_PROJECT_TYPE = "project_type"
        const val EXTRA_PROJECT_ICON = "project_icon"
        const val EXTRA_INO_FILE = "ino_file"
        const val EXTRA_CREATED_AT = "created_at"
        private const val EDITOR_WORD_WRAP_KEY = "editor_word_wrap"
    }
}
