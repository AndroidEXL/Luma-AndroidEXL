package com.example.animatedsplash

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

/** Project-scoped storage. Every project owns an isolated directory keyed by its stable ID. */
class InoFileStore(context: Context) {

    private val projectsDirectory = File(context.filesDir, "ino_projects").apply { mkdirs() }

    private fun projectDirectory(project: Project): File {
        return File(projectsDirectory, project.id.toString()).apply { mkdirs() }
    }

    fun fileFor(project: Project): File {
        val requested = project.inoFileName?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "${sanitize(project.name).ifBlank { "arduino_project" }}.ino"
        val safeName = sanitizeFileName(requested)
        return File(projectDirectory(project), safeName)
    }

    fun ensureFile(project: Project): File {
        val file = fileFor(project)
        if (!file.exists()) {
            migrateLegacyMainFileIfSafe(project, file)
        }
        if (!file.exists()) file.writeText(defaultSketch(project.name))
        return file
    }

    fun read(project: Project): String {
        return ensureFile(project).readText()
    }

    fun write(project: Project, code: String): File {
        val file = ensureFile(project)
        file.writeText(code)
        return file
    }

    fun projectFiles(project: Project): List<File> {
        val root = projectDirectory(project)
        ensureFile(project)
        return root.walkTopDown().filter { it.isFile }.toList()
    }

    fun relativePath(project: Project, file: File): String {
        return file.relativeTo(projectDirectory(project)).invariantSeparatorsPath
    }

    fun saveAdditional(project: Project, requestedName: String, code: String): File {
        val original = requestedName.substringAfterLast('/').ifBlank { "Untitled.ino" }
        val extension = original.substringAfterLast('.', "ino")
            .lowercase()
            .takeIf { it in setOf("ino", "cpp", "c", "h") } ?: "ino"
        val stem = original.substringBeforeLast('.', original)
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifBlank { "Untitled" }
        val file = File(projectDirectory(project), "$stem.$extension")
        file.writeText(code)
        return file
    }

    fun firmwareBackupFile(project: Project, kind: String, extension: String): File {
        val directory = File(projectDirectory(project), "firmware_backups").apply { mkdirs() }
        val safeKind = kind.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "firmware" }
        return File(directory, "${safeKind}_${System.currentTimeMillis()}.$extension")
    }

    fun firmwareBackupFiles(project: Project): List<File> {
        val directory = File(projectDirectory(project), "firmware_backups")
        return directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun clearFirmwareBackups(project: Project): Int {
        return firmwareBackupFiles(project).count { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    fun readArdx(uri: Uri, resolver: android.content.ContentResolver): String? {
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                    ZipInputStream(bytes.inputStream()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.lowercase().endsWith(".ino")) {
                                return@runCatching zip.readBytes().toString(Charsets.UTF_8)
                            }
                            entry = zip.nextEntry
                        }
                    }
                    null
                } else {
                    bytes.toString(Charsets.UTF_8)
                }
            }
        }.getOrNull()
    }

    private fun migrateLegacyMainFileIfSafe(project: Project, destination: File) {
        // Preserve sketches from the previous release only when the stored filename
        // identifies the same legacy path. New writes always go to the project-ID folder.
        val legacyName = project.inoFileName?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: "${sanitize(project.name).ifBlank { "arduino_project" }}.ino"
        val legacy = File(projectsDirectory, sanitizeFileName(legacyName))
        if (legacy.isFile) {
            runCatching { legacy.copyTo(destination, overwrite = false) }
        }
    }

    private fun sanitizeFileName(value: String): String {
        val name = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .trim('_', '.')
        return if (name.isBlank()) "arduino_project.ino" else name
    }

    private fun sanitize(value: String): String {
        return value.trim()
            .replace(Regex("[^A-Za-z0-9_ -]"), "")
            .replace(Regex("\\s+"), "_")
    }

    private fun defaultSketch(projectName: String): String {
        return """// $projectName
// Luma Arduino IDE

void setup() {
  // ضع تعليمات التهيئة هنا
}

void loop() {
  // ضع الكود الذي يتكرر هنا
}
""".trimIndent() + "\n"
    }
}
