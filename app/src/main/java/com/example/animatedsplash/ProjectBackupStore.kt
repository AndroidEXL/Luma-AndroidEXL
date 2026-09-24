package com.example.animatedsplash

import android.content.Context
import java.io.File

/** Small rolling backup store isolated by project id. */
class ProjectBackupStore(context: Context) {
    private val root = File(context.filesDir, "project_backups").apply { mkdirs() }
    private val maxBackups = 12

    fun save(project: Project, code: String, reason: String = "autosave"): File {
        val directory = File(root, project.id.toString()).apply { mkdirs() }
        val safeReason = reason.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(directory, "${System.currentTimeMillis()}_$safeReason.ino.bak")
        file.writeText(code)
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(maxBackups)
            ?.forEach { it.delete() }
        return file
    }

    fun latest(project: Project): File? {
        return File(root, project.id.toString()).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".ino.bak") }
            ?.maxByOrNull { it.lastModified() }
    }

    fun restoreLatest(project: Project): String? {
        return latest(project)?.takeIf { it.isFile }?.readText()
    }
}
