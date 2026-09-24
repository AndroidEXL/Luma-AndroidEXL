package com.example.avrandroid

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the packaged Android-native avrdude executable for real tty paths.
 * Android USB Host devices normally require AvrUploader/UsbManager instead;
 * this runner is used when the selected port is exposed as /dev/tty*.
 */
class AvrdudeNativeRunner(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()

    fun upload(hexFile: File, board: AvrBoard, port: String, callback: AvrUploadCallback) {
        executor.execute { callback(run(hexFile, board, port)) }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun run(hexFile: File, board: AvrBoard, port: String): AvrUploadResult {
        if (!hexFile.isFile) return AvrUploadResult(false, "HEX file not found: ${hexFile.absolutePath}")
        val installation = AvrToolchainInstaller.ensureInstalled(appContext)
        if (!installation.installed) return AvrUploadResult(false, installation.message)
        val root = installation.root
        val avrdude = File(root, "bin/avrdude")
        val config = File(root, "avrdude.conf")
        if (!avrdude.isFile || !config.isFile) {
            return AvrUploadResult(false, "Native avrdude or avrdude.conf is missing under ${root.absolutePath}")
        }

        val command = listOf(
            avrdude.absolutePath,
            "-C", config.absolutePath,
            "-p", board.mcu,
            "-c", board.programmer,
            "-P", port,
            "-b", board.uploadBaudRate.toString(),
            "-D",
            "-U", "flash:w:${hexFile.absolutePath}:i",
            "-v"
        )
        return try {
            val processBuilder = ProcessBuilder(command)
                .directory(hexFile.parentFile ?: root)
                .redirectErrorStream(true)
            val environment = processBuilder.environment()
            environment["PATH"] = listOf(
                File(root, "bin").absolutePath,
                File(root, "libexec/gcc/avr/7.3.0").absolutePath,
                environment["PATH"].orEmpty()
            ).joinToString(File.pathSeparator)
            val process = processBuilder.start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.length > MAX_OUTPUT_CHARS) text.takeLast(MAX_OUTPUT_CHARS) else text
            }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return AvrUploadResult(false, "avrdude timed out after $TIMEOUT_SECONDS seconds\n$output")
            }
            AvrUploadResult(process.exitValue() == 0, "$ ${command.joinToString(" ")}\n$output")
        } catch (error: Exception) {
            AvrUploadResult(false, "avrdude process failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 90L
        private const val MAX_OUTPUT_CHARS = 1_000_000
    }
}
