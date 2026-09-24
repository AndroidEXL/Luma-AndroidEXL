package com.example.animatedsplash

import com.example.avrandroid.AvrBoard
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FirmwareBundleWriter {
    fun create(
        destination: File,
        flash: File,
        eeprom: File?,
        board: AvrBoard,
        port: String,
        log: String
    ): File {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            addFile(zip, flash, "firmware/${flash.name}")
            eeprom?.takeIf { it.isFile }?.let { addFile(zip, it, "firmware/${it.name}") }
            zip.putNextEntry(ZipEntry("read-log.txt"))
            zip.write(log.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(
                "Luma Firmware extraction\nBoard: ${board.fqbn}\nPort: $port\n\nThis archive contains a Flash HEX image and optional EEPROM image read by avrdude. It does not contain the original Arduino source (.ino), comments, or variable names.\n".toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return destination
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }
}
