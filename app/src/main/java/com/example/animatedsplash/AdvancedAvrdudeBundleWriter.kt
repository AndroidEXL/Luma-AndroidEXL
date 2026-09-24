package com.example.animatedsplash

import com.example.avrandroid.AvrBoard
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AdvancedAvrdudeBundleWriter {
    fun create(
        destination: File,
        options: AdvancedAvrdudeOptions,
        board: AvrBoard,
        port: String,
        success: Boolean,
        output: String,
        memoryFile: File?
    ): File {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            write(zip, "operation.txt", buildString {
                appendLine("Luma Advanced avrdude operation")
                appendLine("Operation: ${options.operation.label}")
                appendLine("Memory: ${options.memory.label}")
                appendLine("Format: ${options.format.label}")
                appendLine("Board: ${board.fqbn}")
                appendLine("Port: $port")
                appendLine("Dry run: ${options.dryRun}")
                appendLine("Disable auto erase: ${options.disableAutoErase}")
                appendLine("Verbose level: ${options.verbosity}")
                appendLine("Result: ${if (success) "SUCCESS" else "FAILED"}")
            })
            write(zip, "avrdude-output.txt", output)
            memoryFile?.takeIf { it.isFile }?.let { file ->
                zip.putNextEntry(ZipEntry("memory/${file.name}"))
                file.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            write(zip, "README.txt", "This archive contains a Luma advanced avrdude operation, its options, and the complete avrdude output. Any captured memory file is in the memory directory.\n")
        }
        return destination
    }

    private fun write(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
