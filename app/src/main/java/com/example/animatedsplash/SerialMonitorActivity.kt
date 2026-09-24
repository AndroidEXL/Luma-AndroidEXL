package com.example.animatedsplash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowCompat
import com.example.animatedsplash.databinding.ActivitySerialMonitorBinding
import com.example.avrandroid.AndroidSerialSession
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SerialMonitorActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySerialMonitorBinding
    private lateinit var chart: SerialChartView
    private var baudRate = 9600
    private var connected = false
    private var session: AndroidSerialSession? = null
    private var selectedDevice: UsbDevice? = null
    private var permissionRequestInFlight = false
    private var autoConnectAfterPermission = false
    private var serialCharset: Charset = StandardCharsets.UTF_8
    private val textDecoder = SerialTextDecoder(serialCharset)
    private val incomingLineBuffer = StringBuilder()
    private val serialLog = StringBuilder()
    private val usbPermissionAction = "com.example.animatedsplash.SERIAL_USB_PERMISSION"
    private var textOutputPaused = false
    private var serialLogFilter = ""
    private var serialRegexEnabled = false
    private var timestampsEnabled = false
    private var autoScrollEnabled = true
    private var lineTerminator = "\n"
    private var receivedBytes = 0L
    private var sentBytes = 0L
    private val commandHistory = ArrayDeque<String>()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val exportLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { destination ->
        if (destination == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(destination)?.use { stream ->
                stream.write(serialLog.toString().toByteArray(Charsets.UTF_8))
            } ?: error("تعذر فتح ملف السجل")
        }.onSuccess {
            Toast.makeText(this, R.string.serial_monitor_export_log_success, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.serial_monitor_export_log_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != usbPermissionAction) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            permissionRequestInFlight = false
            if (granted) {
                binding.serialStatus.text = getString(R.string.serial_monitor_usb_selected)
                if (autoConnectAfterPermission) {
                    autoConnectAfterPermission = false
                    connectSerial()
                }
            } else {
                autoConnectAfterPermission = false
                binding.serialStatus.text = getString(R.string.serial_monitor_permission_required)
            }
            updateDeviceButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivitySerialMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        baudRate = getSharedPreferences(LumaApplication.PREFERENCES_NAME, MODE_PRIVATE)
            .getInt(LumaApplication.DEFAULT_SERIAL_BAUD_KEY, LumaApplication.DEFAULT_SERIAL_BAUD)

        selectedDevice = readDeviceExtra() ?: usbManager().deviceList.values.firstOrNull()
        chart = SerialChartView(this)
        binding.serialGraphContainer.addView(chart, 0, android.widget.FrameLayout.LayoutParams(-1, -1))
        registerUsbReceiver()

        binding.serialBackButton.setOnClickListener { finish() }
        binding.serialClearButton.setOnClickListener { clearOutput() }
        binding.serialRegexButton.setOnClickListener {
            serialRegexEnabled = !serialRegexEnabled
            updateTextToolButtons()
            if (!textOutputPaused) renderSerialText()
        }
        binding.serialPauseButton.setOnClickListener {
            textOutputPaused = !textOutputPaused
            updateTextToolButtons()
            if (!textOutputPaused) renderSerialText()
        }
        binding.serialExportLogButton.setOnClickListener { exportSerialLog() }
        binding.serialTimestampButton.setOnClickListener {
            timestampsEnabled = !timestampsEnabled
            updateTextToolButtons()
        }
        binding.serialAutoScrollButton.setOnClickListener {
            autoScrollEnabled = !autoScrollEnabled
            updateTextToolButtons()
        }
        binding.serialHistoryButton.setOnClickListener { showCommandHistory() }
        binding.serialCopyLogButton.setOnClickListener { copyVisibleLog() }
        binding.serialStatsButton.setOnClickListener { showSerialStatistics() }
        binding.serialNewlineButton.setOnClickListener { showLineTerminatorPicker() }
        binding.serialLogFilterInput.doAfterTextChanged { editable ->
            serialLogFilter = editable?.toString().orEmpty()
            if (!textOutputPaused) renderSerialText()
        }
        binding.serialExportGraphButton.setOnClickListener { exportGraphPng() }
        binding.serialPlotterStopButton.setOnClickListener {
            chart.setPlotting(!chart.isPlotting())
            updatePlotterButtons()
        }
        binding.serialPlotterInterpolateButton.setOnClickListener {
            chart.setInterpolate(!chart.isInterpolate())
            updatePlotterButtons()
        }
        binding.serialPlotterNamesButton.setOnClickListener { showChannelNamesDialog() }
        binding.baudRateButton.setOnClickListener { showBaudRatePicker() }
        binding.serialUsbConnectButton.setOnClickListener {
            if (connected) disconnectSerial() else connectSerial()
        }
        binding.serialUsbDeviceButton.setOnClickListener { showUsbDevicePicker() }
        binding.serialSendButton.setOnClickListener { sendTextToBoard() }
        binding.serialSendInput.setOnEditorActionListener { _, _, _ ->
            sendTextToBoard()
            true
        }
        binding.displayModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) updateDisplayMode(checkedId == R.id.serialGraphModeButton)
        }
        binding.serialBaudValue.text = baudRate.toString()
        binding.serialEncodingValue.text = serialCharset.name()
        updateDeviceButton()
        updateConnectButton()
        updateDisplayMode(false)
        updatePlotterButtons()
        updateTextToolButtons()
        binding.serialStatus.text = if (selectedDevice == null) {
            getString(R.string.serial_monitor_no_device)
        } else {
            statusText()
        }
        binding.serialEncodingButton.setOnClickListener { showEncodingPicker() }
        if (selectedDevice != null) {
            binding.serialStatus.postDelayed({
                if (!isFinishing && !connected) connectSerial()
            }, 180L)
        }
    }

    private fun connectSerial() {
        val device = selectedDevice ?: usbManager().deviceList.values.firstOrNull().also { selectedDevice = it }
        if (device == null) {
            binding.serialStatus.text = getString(R.string.serial_monitor_no_device)
            return
        }
        val manager = usbManager()
        if (!manager.hasPermission(device)) {
            autoConnectAfterPermission = true
            requestUsbPermission(device)
            binding.serialStatus.text = getString(R.string.serial_monitor_permission_required)
            return
        }

        binding.serialUsbConnectButton.isEnabled = false
        binding.serialStatus.text = getString(R.string.serial_monitor_opening)
        session = AndroidSerialSession(
            context = this,
            device = device,
            baudRate = baudRate,
            charset = serialCharset,
            listener = object : AndroidSerialSession.Listener {
            override fun onOpened() {
                runOnUiThread {
                    connected = true
                    binding.serialUsbConnectButton.isEnabled = true
                    updateConnectButton()
                    binding.serialStatus.text = getString(R.string.serial_monitor_connected, baudRate)
                    appendText("\n${getString(R.string.serial_monitor_ready_line, baudRate)}\n")
                }
            }

            override fun onBytes(bytes: ByteArray) {
                val text = textDecoder.decode(bytes)
                runOnUiThread {
                    receivedBytes += bytes.size
                    if (text.isNotEmpty()) {
                        appendText(text)
                        collectNumericValues(text)
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    connected = false
                    binding.serialUsbConnectButton.isEnabled = true
                    updateConnectButton()
                    binding.serialStatus.text = message
                    appendText("\n[ERROR] $message\n")
                }
            }

            override fun onClosed() {
                runOnUiThread {
                    connected = false
                    binding.serialUsbConnectButton.isEnabled = true
                    updateConnectButton()
                    binding.serialStatus.text = getString(R.string.serial_monitor_closed)
                }
            }
            }
        )
        session?.start()
    }

    private fun showUsbDevicePicker() {
        val devices = usbManager().deviceList.values.toList()
        if (devices.isEmpty()) {
            binding.serialStatus.text = getString(R.string.serial_monitor_usb_no_devices)
            Toast.makeText(this, R.string.serial_monitor_usb_no_devices, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = devices.map { deviceLabel(it) }.toTypedArray()
        val selected = devices.indexOfFirst { it.deviceName == selectedDevice?.deviceName }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.serial_monitor_usb_choose_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                if (connected) disconnectSerial()
                selectedDevice = devices[which]
                updateDeviceButton()
                binding.serialStatus.text = getString(R.string.serial_monitor_usb_selected)
                dialog.dismiss()
                connectSerial()
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun deviceLabel(device: UsbDevice): String {
        val product = runCatching { device.productName }.getOrNull().orEmpty()
        return if (product.isBlank()) "${device.deviceName} (${device.vendorId}:${device.productId})" else "$product — ${device.deviceName}"
    }

    private fun updateDeviceButton() {
        val device = selectedDevice
        binding.serialUsbDeviceButton.text = if (device == null) {
            getString(R.string.serial_monitor_usb_device)
        } else {
            getString(R.string.serial_monitor_usb_device_selected, deviceLabel(device))
        }
    }

    private fun updateConnectButton() {
        binding.serialUsbConnectButton.setText(
            if (connected) R.string.serial_monitor_usb_disconnect else R.string.serial_monitor_usb_connect
        )
    }

    private fun disconnectSerial() {
        session?.close()
        session = null
        connected = false
        binding.serialUsbConnectButton.isEnabled = true
        updateConnectButton()
        binding.serialStatus.text = getString(R.string.serial_monitor_closed)
    }

    private fun sendTextToBoard() {
        val value = binding.serialSendInput.text?.toString().orEmpty()
        if (value.isBlank()) return
        if (!connected) {
            Toast.makeText(this, R.string.serial_monitor_send_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val payload = value + lineTerminator
        session?.sendText(payload, appendNewline = false)
        commandHistory.remove(value)
        commandHistory.addFirst(value)
        while (commandHistory.size > MAX_COMMAND_HISTORY) commandHistory.removeLast()
        sentBytes += payload.toByteArray(Charsets.UTF_8).size
        appendText("> $value\n")
        binding.serialSendInput.text?.clear()
        binding.serialStatus.text = getString(R.string.serial_monitor_sent, payload.toByteArray(Charsets.UTF_8).size)
    }

    private fun appendText(value: String) {
        val normalized = if (!timestampsEnabled) value else value
            .splitToSequence('\n')
            .joinToString("\n") { line -> if (line.isBlank()) line else "[${timestampFormat.format(Date())}] $line" }
        serialLog.append(normalized)
        if (serialLog.length > MAX_SERIAL_LOG_CHARS) {
            serialLog.delete(0, serialLog.length - MAX_SERIAL_LOG_CHARS)
        }
        if (!textOutputPaused) renderSerialText()
    }

    private fun renderSerialText() {
        val fullLog = serialLog.toString()
        val visibleLog = if (serialLogFilter.isBlank()) fullLog else runCatching {
            if (serialRegexEnabled) {
                val expression = Regex(serialLogFilter, RegexOption.IGNORE_CASE)
                fullLog.lineSequence().filter { line -> expression.containsMatchIn(line) }.joinToString("\n")
            } else {
                fullLog.lineSequence().filter { line -> line.contains(serialLogFilter, ignoreCase = true) }.joinToString("\n")
            }
        }.getOrElse { error ->
            binding.serialStatus.text = getString(R.string.serial_monitor_regex_invalid, error.message.orEmpty())
            ""
        }
        binding.serialTextOutput.text = when {
            visibleLog.isNotBlank() -> visibleLog
            fullLog.isBlank() -> getString(R.string.serial_monitor_empty)
            else -> getString(R.string.serial_monitor_filter_empty)
        }
        if (autoScrollEnabled) binding.serialTextScroll.post { binding.serialTextScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateTextToolButtons() {
        binding.serialPauseButton.setText(
            if (textOutputPaused) R.string.serial_monitor_resume_text else R.string.serial_monitor_pause_text
        )
        binding.serialTimestampButton.setText(
            if (timestampsEnabled) R.string.serial_monitor_timestamps_on else R.string.serial_monitor_timestamps_off
        )
        binding.serialAutoScrollButton.setText(
            if (autoScrollEnabled) R.string.serial_monitor_autoscroll_on else R.string.serial_monitor_autoscroll_off
        )
        binding.serialRegexButton.setText(
            if (serialRegexEnabled) R.string.serial_monitor_regex_on else R.string.serial_monitor_regex_off
        )
    }

    private fun exportSerialLog() {
        if (serialLog.isBlank()) {
            Toast.makeText(this, R.string.serial_monitor_export_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        exportLogLauncher.launch("luma_serial_${System.currentTimeMillis()}.txt")
    }

    private fun showCommandHistory() {
        if (commandHistory.isEmpty()) {
            Toast.makeText(this, R.string.serial_monitor_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.serial_monitor_history)
            .setItems(commandHistory.toTypedArray()) { _, which ->
                binding.serialSendInput.setText(commandHistory.elementAt(which))
                binding.serialSendInput.setSelection(binding.serialSendInput.length())
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun copyVisibleLog() {
        val value = binding.serialTextOutput.text?.toString().orEmpty()
        if (value.isBlank() || value == getString(R.string.serial_monitor_empty)) {
            Toast.makeText(this, R.string.serial_monitor_copy_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Luma Serial", value))
        Toast.makeText(this, R.string.serial_monitor_copy_log_done, Toast.LENGTH_SHORT).show()
    }

    private fun showSerialStatistics() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.serial_monitor_stats)
            .setMessage(getString(R.string.serial_monitor_stats_message, receivedBytes, sentBytes, commandHistory.size, serialLog.length))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLineTerminatorPicker() {
        val labels = arrayOf("LF (\\n)", "CRLF (\\r\\n)", "CR (\\r)", "None")
        val values = arrayOf("\n", "\r\n", "\r", "")
        val checked = values.indexOf(lineTerminator).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.serial_monitor_newline)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                lineTerminator = values[which]
                binding.serialNewlineButton.text = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun collectNumericValues(chunk: String) {
        incomingLineBuffer.append(chunk)
        while (true) {
            val newline = incomingLineBuffer.indexOf('\n')
            if (newline < 0) break
            val line = incomingLineBuffer.substring(0, newline).trim()
            incomingLineBuffer.delete(0, newline + 1)
            val numericTokens = line.split(Regex("[,;\\s]+"))
                .mapNotNull { token -> token.toFloatOrNull() }
            if (numericTokens.isNotEmpty()) chart.appendValues(numericTokens)
        }
        if (incomingLineBuffer.length > 256) {
            incomingLineBuffer.delete(0, incomingLineBuffer.length - 256)
        }
    }

    private fun exportGraphPng() {
        val width = chart.width.coerceAtLeast(binding.serialGraphContainer.width).coerceAtLeast(1)
        val height = chart.height.coerceAtLeast(binding.serialGraphContainer.height).coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(this, R.string.serial_monitor_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "luma_graph_${System.currentTimeMillis()}.png"
        var saved = false
        try {
            val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Luma")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                try {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        chart.draw(Canvas(bitmap))
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    } ?: throw IllegalStateException("Cannot open image output")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    saved = true
                } catch (error: Throwable) {
                    contentResolver.delete(uri, null, null)
                    throw error
                }
                null
            } else {
                val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Luma").apply { mkdirs() }
                val file = File(directory, fileName)
                FileOutputStream(file).use { stream ->
                    chart.draw(Canvas(bitmap))
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                saved = true
                file
            }
            output
        } catch (_: Throwable) {
            saved = false
        } finally {
            bitmap.recycle()
        }
        Toast.makeText(this, if (saved) R.string.serial_monitor_export_success else R.string.serial_monitor_export_failed, Toast.LENGTH_SHORT).show()
    }

    private fun clearOutput() {
        serialLog.clear()
        incomingLineBuffer.clear()
        chart.clearValues()
        receivedBytes = 0L
        sentBytes = 0L
        renderSerialText()
        binding.serialStatus.text = statusText()
    }

    private fun showChannelNamesDialog() {
        val count = chart.channelCount().coerceAtLeast(1)
        val inputViews = (0 until count).map { index ->
            com.google.android.material.textfield.TextInputEditText(this).apply {
                setText(chart.channelName(index))
                hint = getString(R.string.serial_plotter_channel_name, index + 1)
                setSingleLine(true)
                textSize = 13f
            }
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 4, 20, 4)
        }
        inputViews.forEach { input ->
            val layout = com.google.android.material.textfield.TextInputLayout(this).apply {
                hint = input.hint
                addView(input)
            }
            root.addView(layout, android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 8
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.serial_plotter_names)
            .setView(root)
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                chart.setChannelNames(inputViews.map { it.text?.toString().orEmpty() })
                binding.serialStatus.text = getString(R.string.serial_plotter_names_saved)
            }
            .show()
    }

    private fun showEncodingPicker() {
        val wasConnected = connected
        val labels = arrayOf("UTF-8 — العربية واللغات الحديثة", "Windows-1256 — العربية القديمة", "ISO-8859-1 — Latin")
        val charsets = arrayOf(StandardCharsets.UTF_8, Charset.forName("windows-1256"), StandardCharsets.ISO_8859_1)
        val selected = charsets.indexOfFirst { it.name().equals(serialCharset.name(), ignoreCase = true) }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.serial_monitor_encoding_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                if (wasConnected) disconnectSerial()
                serialCharset = charsets[which]
                textDecoder.setCharset(serialCharset)
                binding.serialEncodingValue.text = serialCharset.name()
                binding.serialStatus.text = getString(R.string.serial_monitor_encoding_selected, serialCharset.name())
                dialog.dismiss()
                if (wasConnected && selectedDevice != null) {
                    binding.root.postDelayed({ if (!isFinishing) connectSerial() }, 160L)
                }
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun showBaudRatePicker() {
        val rates = arrayOf("300", "1200", "2400", "4800", "9600", "19200", "38400", "57600", "115200")
        val selected = rates.indexOf(baudRate.toString()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.serial_monitor_baud_title)
            .setSingleChoiceItems(rates, selected) { dialog, which ->
                val newRate = rates[which].toInt()
                if (connected) disconnectSerial()
                baudRate = newRate
                binding.serialBaudValue.text = baudRate.toString()
                binding.serialStatus.text = statusText()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel_action, null)
            .show()
    }

    private fun updatePlotterButtons() {
        binding.serialPlotterStopButton.setText(
            if (chart.isPlotting()) R.string.serial_plotter_stop else R.string.serial_plotter_run
        )
        binding.serialPlotterInterpolateButton.setText(
            if (chart.isInterpolate()) R.string.serial_plotter_interpolate else R.string.serial_plotter_linear
        )
    }

    private fun updateDisplayMode(graph: Boolean) {
        binding.serialTextScroll.visibility = if (graph) View.GONE else View.VISIBLE
        binding.serialGraphContainer.visibility = if (graph) View.VISIBLE else View.GONE
        binding.serialStatus.text = statusText()
    }

    private fun statusText(): String {
        val mode = if (binding.serialGraphContainer.visibility == View.VISIBLE) {
            getString(R.string.serial_monitor_graph_mode)
        } else {
            getString(R.string.serial_monitor_text_mode)
        }
        return if (connected) {
            getString(R.string.serial_monitor_connected_mode, baudRate, mode)
        } else {
            getString(R.string.serial_monitor_disconnected_mode, baudRate, mode)
        }
    }

    private fun usbManager(): UsbManager = getSystemService(Context.USB_SERVICE) as UsbManager

    private fun requestUsbPermission(device: UsbDevice) {
        val manager = usbManager()
        if (manager.hasPermission(device)) {
            permissionRequestInFlight = false
            return
        }
        if (permissionRequestInFlight) return
        permissionRequestInFlight = true
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(this, device.deviceId, Intent(usbPermissionAction).setPackage(packageName), flags)
        manager.requestPermission(device, pendingIntent)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(usbPermissionAction)
        permissionRequestInFlight = false
        ContextCompat.registerReceiver(this, usbPermissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @Suppress("DEPRECATION")
    private fun readDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_USB_DEVICE)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        session?.close()
        session = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_USB_DEVICE = "serial_usb_device"
        private const val MAX_SERIAL_LOG_CHARS = 120_000
        private const val MAX_COMMAND_HISTORY = 25
    }
}
