package com.example.avrandroid

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the packaged avrdude executable and bridges its PTY slave to Android USB Host.
 * avrdude owns the programming protocol; this class only carries serial bytes.
 */
class AvrdudeUsbBridge(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    fun upload(request: AvrUploadRequest, callback: AvrUploadCallback) {
        executor.execute { callback(run(request)) }
    }

    fun readFirmware(
        request: AvrReadRequest,
        onLog: (String) -> Unit = {},
        callback: AvrReadCallback
    ) {
        executor.execute { callback(runRead(request, onLog)) }
    }

    fun runCustomCommand(
        request: AvrdudeCommandRequest,
        onLog: (String) -> Unit = {},
        callback: AvrdudeCommandCallback
    ) {
        executor.execute { callback(runCustomCommand(request, onLog)) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun run(request: AvrUploadRequest): AvrUploadResult {
        val output = StringBuilder()
        fun log(value: String) { output.append(value).append('\n') }
        if (!request.hexFile.isFile) {
            return AvrUploadResult(false, "HEX file not found: ${request.hexFile.absolutePath}")
        }

        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(request.usbDevice)) {
            return AvrUploadResult(false, "USB permission is not granted for ${request.usbDevice.deviceName}")
        }

        val installation = AvrToolchainInstaller.ensureInstalled(appContext)
        if (!installation.installed) return AvrUploadResult(false, installation.message)
        val root = installation.root
        val avrdude = File(root, "bin/avrdude")
        val config = File(root, "avrdude.conf")
        if (!avrdude.isFile || !config.isFile) {
            return AvrUploadResult(false, "Native avrdude or avrdude.conf is missing under ${root.absolutePath}")
        }

        val transport = UsbSerialTransport.open(
            manager = manager,
            device = request.usbDevice,
            baud = request.board.uploadBaudRate,
            log = ::log,
            initialize = false
        ) ?: return AvrUploadResult(false, output.toString() + "Unable to open a supported USB serial interface.")

        val pty = PtyBridge.open()
            ?: run {
                transport.close()
                return AvrUploadResult(false, output.toString() + "Unable to create Android PTY bridge.")
            }
        val masterFd = pty.first
        val slavePath = pty.second
        val command = listOf(
            avrdude.absolutePath,
            "-C", config.absolutePath,
            "-p", request.board.mcu,
            "-c", request.board.programmer,
            "-P", slavePath,
            "-b", request.board.uploadBaudRate.toString(),
            "-D",
            "-U", "flash:w:${request.hexFile.absolutePath}:i",
            "-v"
        )
        log("Engine: avrdude Android-native")
        log("Bridge: Android USB Host ↔ PTY $slavePath")
        log("Board: ${request.board.fqbn}")
        log("Programmer: ${request.board.programmer}")
        log("Port: ${request.usbDevice.deviceName}")
        log("Baud: ${request.board.uploadBaudRate}")
        log("HEX: ${request.hexFile.absolutePath}")
        log("$ ${command.joinToString(" ")}")

        return try {
            transport.configure()
            transport.pulseReset()
            transport.purge()
            val process = ProcessBuilder(command)
                .directory(request.hexFile.parentFile ?: root)
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = listOf(
                        File(root, "bin").absolutePath,
                        File(root, "libexec/gcc/avr/7.3.0").absolutePath,
                        environment()["PATH"].orEmpty()
                    ).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
                    environment()["TERM"] = "dumb"
                }
                .start()

            val avrdudeOutput = StringBuilder()
            val outputThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(avrdudeOutput) {
                            if (avrdudeOutput.length < MAX_OUTPUT_CHARS) avrdudeOutput.append(line).append('\n')
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val relayRunning = AtomicBoolean(true)
            val usbToPty = Thread {
                val buffer = ByteArray(512)
                try {
                    while (relayRunning.get()) {
                        val count = transport.readChunk(buffer, 100)
                        if (count > 0 && PtyBridge.write(masterFd, buffer, count) != count) break
                    }
                } catch (_: Exception) {
                    relayRunning.set(false)
                }
            }.apply { isDaemon = true; start() }
            val ptyToUsb = Thread {
                val buffer = ByteArray(512)
                try {
                    while (relayRunning.get()) {
                        val count = PtyBridge.read(masterFd, buffer, 100)
                        if (count > 0) transport.write(buffer, count)
                        if (count < 0) break
                    }
                } catch (_: Exception) {
                    relayRunning.set(false)
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            relayRunning.set(false)
            if (!finished) {
                process.destroyForcibly()
                outputThread.join(1_000)
                usbToPty.join(500)
                ptyToUsb.join(500)
                return AvrUploadResult(false, output.toString() + avrdudeOutput.toString() + "avrdude timed out after $TIMEOUT_SECONDS seconds\n")
            }
            outputThread.join(1_000)
            usbToPty.join(500)
            ptyToUsb.join(500)
            val avrdudeLog = avrdudeOutput.toString()
            AvrUploadResult(process.exitValue() == 0, output.toString() + avrdudeLog)
        } catch (error: Exception) {
            log("avrdude bridge failed: ${error.message ?: error.javaClass.simpleName}")
            AvrUploadResult(false, output.toString())
        } finally {
            PtyBridge.close(masterFd)
            transport.close()
        }
    }

    private fun runRead(request: AvrReadRequest, onLog: (String) -> Unit): AvrReadResult {
        val output = StringBuilder()
        fun log(value: String) {
            output.append(value).append('\n')
            onLog(value)
        }
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(request.usbDevice)) {
            return AvrReadResult(false, "USB permission is not granted for ${request.usbDevice.deviceName}", request.flashOutputFile, request.eepromOutputFile)
        }
        val installation = AvrToolchainInstaller.ensureInstalled(appContext)
        if (!installation.installed) return AvrReadResult(false, installation.message, request.flashOutputFile, request.eepromOutputFile)
        val root = installation.root
        val avrdude = File(root, "bin/avrdude")
        val config = File(root, "avrdude.conf")
        if (!avrdude.isFile || !config.isFile) {
            return AvrReadResult(false, "Native avrdude or avrdude.conf is missing under ${root.absolutePath}", request.flashOutputFile, request.eepromOutputFile)
        }
        request.flashOutputFile.parentFile?.mkdirs()
        request.eepromOutputFile?.parentFile?.mkdirs()
        request.flashOutputFile.delete()
        request.eepromOutputFile?.delete()
        val transport = UsbSerialTransport.open(manager, request.usbDevice, request.board.uploadBaudRate, ::log, false)
            ?: return AvrReadResult(false, output.toString() + "Unable to open a supported USB serial interface.", request.flashOutputFile, request.eepromOutputFile)
        val pty = PtyBridge.open() ?: run {
            transport.close()
            return AvrReadResult(false, output.toString() + "Unable to create Android PTY bridge.", request.flashOutputFile, request.eepromOutputFile)
        }
        val masterFd = pty.first
        val slavePath = pty.second
        val command = mutableListOf(
            avrdude.absolutePath, "-C", config.absolutePath, "-p", request.board.mcu,
            "-c", request.board.programmer, "-P", slavePath, "-b", request.board.uploadBaudRate.toString(),
            "-U", "flash:r:${request.flashOutputFile.absolutePath}:i"
        )
        request.eepromOutputFile?.let { command += listOf("-U", "eeprom:r:${it.absolutePath}:i") }
        command += "-v"
        log("Engine: avrdude Android-native")
        log("Operation: read Flash${if (request.eepromOutputFile != null) " + EEPROM" else ""}")
        log("Board: ${request.board.fqbn}")
        log("Port: ${request.usbDevice.deviceName}")
        log("Flash output: ${request.flashOutputFile.absolutePath}")
        request.eepromOutputFile?.let { log("EEPROM output: ${it.absolutePath}") }
        log("$ ${command.joinToString(" ")}")
        return try {
            transport.configure()
            transport.pulseReset()
            transport.purge()
            val process = ProcessBuilder(command).directory(request.flashOutputFile.parentFile ?: root).redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = listOf(File(root, "bin").absolutePath, File(root, "libexec/gcc/avr/7.3.0").absolutePath, environment()["PATH"].orEmpty()).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
                    environment()["TERM"] = "dumb"
                }.start()
            val avrdudeOutput = StringBuilder()
            val outputThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(avrdudeOutput) {
                            if (avrdudeOutput.length < MAX_OUTPUT_CHARS) avrdudeOutput.append(line).append('\n')
                        }
                        onLog(line)
                    }
                }
            }.apply { isDaemon = true; start() }
            val relayRunning = AtomicBoolean(true)
            val usbToPty = Thread {
                val buffer = ByteArray(512)
                try { while (relayRunning.get()) { val count = transport.readChunk(buffer, 100); if (count > 0 && PtyBridge.write(masterFd, buffer, count) != count) break } }
                catch (_: Exception) { relayRunning.set(false) }
            }.apply { isDaemon = true; start() }
            val ptyToUsb = Thread {
                val buffer = ByteArray(512)
                try { while (relayRunning.get()) { val count = PtyBridge.read(masterFd, buffer, 100); if (count > 0) transport.write(buffer, count); if (count < 0) break } }
                catch (_: Exception) { relayRunning.set(false) }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            relayRunning.set(false)
            if (!finished) {
                process.destroyForcibly()
                outputThread.join(1_000); usbToPty.join(500); ptyToUsb.join(500)
                return AvrReadResult(false, output.toString() + avrdudeOutput + "avrdude timed out after $TIMEOUT_SECONDS seconds\n", request.flashOutputFile, request.eepromOutputFile)
            }
            outputThread.join(1_000); usbToPty.join(500); ptyToUsb.join(500)
            val success = process.exitValue() == 0 && request.flashOutputFile.isFile && request.flashOutputFile.length() > 0L
            val logText = output.toString() + avrdudeOutput
            AvrReadResult(success, logText + if (success) "Firmware read completed.\n" else "Firmware read did not produce a Flash HEX file.\n", request.flashOutputFile, request.eepromOutputFile)
        } catch (error: Exception) {
            log("avrdude read bridge failed: ${error.message ?: error.javaClass.simpleName}")
            AvrReadResult(false, output.toString(), request.flashOutputFile, request.eepromOutputFile)
        } finally {
            PtyBridge.close(masterFd)
            transport.close()
        }
    }

    private fun runCustomCommand(request: AvrdudeCommandRequest, onLog: (String) -> Unit): AvrdudeCommandResult {
        val output = StringBuilder()
        fun log(value: String) {
            output.append(value).append('\n')
            onLog(value)
        }
        val invalid = validateCustomArguments(request.arguments)
        if (invalid != null) return AvrdudeCommandResult(false, invalid)
        val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(request.usbDevice)) {
            return AvrdudeCommandResult(false, "USB permission is not granted for ${request.usbDevice.deviceName}")
        }
        val installation = AvrToolchainInstaller.ensureInstalled(appContext)
        if (!installation.installed) return AvrdudeCommandResult(false, installation.message)
        val root = installation.root
        val avrdude = File(root, "bin/avrdude")
        val config = File(root, "avrdude.conf")
        if (!avrdude.isFile || !config.isFile) {
            return AvrdudeCommandResult(false, "Native avrdude or avrdude.conf is missing under ${root.absolutePath}")
        }
        val transport = UsbSerialTransport.open(manager, request.usbDevice, request.board.uploadBaudRate, ::log, false)
            ?: return AvrdudeCommandResult(false, output.toString() + "Unable to open a supported USB serial interface.")
        val pty = PtyBridge.open() ?: run {
            transport.close()
            return AvrdudeCommandResult(false, output.toString() + "Unable to create Android PTY bridge.")
        }
        val masterFd = pty.first
        val slavePath = pty.second
        val userArguments = request.arguments.toMutableList()
        if (userArguments.none { it == "-v" || it == "--verbose" }) userArguments += "-v"
        val command = mutableListOf(
            avrdude.absolutePath,
            "-C", config.absolutePath,
            "-p", request.board.mcu,
            "-c", request.board.programmer,
            "-P", slavePath,
            "-b", request.board.uploadBaudRate.toString()
        ).apply { addAll(userArguments) }
        log("Engine: avrdude Android-native")
        log("Operation: custom avrdude command")
        log("Board: ${request.board.fqbn}")
        log("Port: ${request.usbDevice.deviceName}")
        log("User arguments: ${request.arguments.joinToString(" ")}")
        log("$ ${command.joinToString(" ")}")
        return try {
            transport.configure()
            transport.pulseReset()
            transport.purge()
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = listOf(
                        File(root, "bin").absolutePath,
                        File(root, "libexec/gcc/avr/7.3.0").absolutePath,
                        environment()["PATH"].orEmpty()
                    ).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
                    environment()["TERM"] = "dumb"
                }
                .start()
            val avrdudeOutput = StringBuilder()
            val outputThread = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(avrdudeOutput) {
                            if (avrdudeOutput.length < MAX_OUTPUT_CHARS) avrdudeOutput.append(line).append('\n')
                        }
                        onLog(line)
                    }
                }
            }.apply { isDaemon = true; start() }
            val relayRunning = AtomicBoolean(true)
            val usbToPty = Thread {
                val buffer = ByteArray(512)
                try {
                    while (relayRunning.get()) {
                        val count = transport.readChunk(buffer, 100)
                        if (count > 0 && PtyBridge.write(masterFd, buffer, count) != count) break
                    }
                } catch (_: Exception) {
                    relayRunning.set(false)
                }
            }.apply { isDaemon = true; start() }
            val ptyToUsb = Thread {
                val buffer = ByteArray(512)
                try {
                    while (relayRunning.get()) {
                        val count = PtyBridge.read(masterFd, buffer, 100)
                        if (count > 0) transport.write(buffer, count)
                        if (count < 0) break
                    }
                } catch (_: Exception) {
                    relayRunning.set(false)
                }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            relayRunning.set(false)
            if (!finished) {
                process.destroyForcibly()
                outputThread.join(1_000)
                usbToPty.join(500)
                ptyToUsb.join(500)
                return AvrdudeCommandResult(false, output.toString() + avrdudeOutput + "avrdude timed out after $TIMEOUT_SECONDS seconds\n")
            }
            outputThread.join(1_000)
            usbToPty.join(500)
            ptyToUsb.join(500)
            val success = process.exitValue() == 0
            AvrdudeCommandResult(success, output.toString() + avrdudeOutput + if (success) "Custom command completed.\n" else "Custom command failed.\n")
        } catch (error: Exception) {
            log("avrdude custom command bridge failed: ${error.message ?: error.javaClass.simpleName}")
            AvrdudeCommandResult(false, output.toString())
        } finally {
            PtyBridge.close(masterFd)
            transport.close()
        }
    }

    private fun validateCustomArguments(arguments: List<String>): String? {
        if (arguments.isEmpty()) return "Custom avrdude command arguments are empty."
        if (arguments.size > MAX_CUSTOM_ARGUMENTS) return "Too many custom avrdude arguments."
        if (arguments.any { it.length > MAX_CUSTOM_ARGUMENT_LENGTH || it.indexOf('\u0000') >= 0 }) {
            return "Invalid custom avrdude argument."
        }
        val protectedOptions = listOf("-C", "--config-file", "-p", "--partno", "-c", "--programmer", "-P", "--port", "-b", "--baudrate", "-t", "--terminal")
        if (arguments.any { argument ->
                protectedOptions.any { option -> argument == option || argument.startsWith("$option=") || (option.length == 2 && argument.startsWith(option) && argument.length > option.length) }
            }) {
            return "The avrdude configuration, board, programmer, port, baud rate, and terminal mode are fixed by the selected Luma hardware."
        }
        return null
    }

    companion object {
        private const val TIMEOUT_SECONDS = 120L
        private const val MAX_OUTPUT_CHARS = 1_000_000
        private const val MAX_CUSTOM_ARGUMENTS = 48
        private const val MAX_CUSTOM_ARGUMENT_LENGTH = 2_048
    }
}
