package com.example.animatedsplash

import com.example.avrandroid.AvrBoard
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AvrdudeCommandBundleWriter {
    fun create(
        destination: File,
        commandName: String,
        arguments: String,
        board: AvrBoard,
        port: String,
        success: Boolean,
        output: String
    ): File {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            write(zip, "command.txt", buildString {
                appendLine("Luma custom avrdude command")
                appendLine("Name: $commandName")
                appendLine("Board: ${board.fqbn}")
                appendLine("Port: $port")
                appendLine("Result: ${if (success) "SUCCESS" else "FAILED"}")
                appendLine()
                appendLine("Arguments entered by user:")
                appendLine(arguments)
            })
            write(zip, "avrdude-output.txt", output)
            write(zip, "README.txt", "This archive contains the execution metadata and complete avrdude output for a command run from Luma. Luma fixes the avrdude binary, configuration, board, programmer, USB PTY port, and baud rate to the selected hardware.\n")
        }
        return destination
    }

    private fun write(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
