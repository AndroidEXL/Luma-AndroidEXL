package com.example.animatedsplash

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BoardDataBundleWriter {
    fun create(
        destination: File,
        report: String,
        avrdudeOutput: String,
        values: Map<String, File>
    ): File {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            writeText(zip, "board-data-report.txt", report)
            writeText(zip, "avrdude-output.txt", avrdudeOutput)
            values.forEach { (name, file) ->
                if (!file.isFile) return@forEach
                zip.putNextEntry(ZipEntry("raw-values/$name"))
                file.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            writeText(
                zip,
                "README.txt",
                "This archive contains a read-only Luma board data report. It includes the avrdude log and raw captures for device signature, Fuses, and Lock bits when the connected board/bootloader allows them to be read.\n"
            )
        }
        return destination
    }

    private fun writeText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
