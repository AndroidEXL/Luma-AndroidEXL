package com.example.animatedsplash

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ArduinoDiagnosticBundleWriter {
    fun create(destination: File, report: String, buildOutput: String): File {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            write(zip, "diagnostic-report.txt", report)
            write(zip, "last-build-output.txt", buildOutput)
            write(zip, "README.txt", "This Luma diagnostic bundle includes project metadata, board selection, USB information when selected, library names, and the last build output. It does not include the project's source code.\n")
        }
        return destination
    }

    private fun write(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
