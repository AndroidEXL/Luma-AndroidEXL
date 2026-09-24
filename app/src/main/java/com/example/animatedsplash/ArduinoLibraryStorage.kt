package com.example.animatedsplash

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

/** Filesystem support for downloaded Arduino libraries used by AVR-GCC. */
object ArduinoLibraryStorage {
    private const val ROOT_NAME = "arduino-libraries"
    private const val MAX_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 8_000
    private val includePattern = Regex("#include\\s*[<\\\"]([^>\\\"]+)[>\\\"]")

    fun root(context: Context): File = File(context.filesDir, ROOT_NAME)

    fun installDirectory(context: Context, libraryName: String): File =
        File(root(context), safeName(libraryName))

    fun installedNames(context: Context): Set<String> = root(context).listFiles()
        ?.filter { it.isDirectory && File(it, ".luma-library-installed").isFile }
        ?.map { it.name }
        ?.toSet()
        .orEmpty()

    fun deleteInstalledLibrary(context: Context, libraryName: String): Boolean {
        val libraryRoot = root(context)
        val safe = safeName(libraryName)
        val directory = File(libraryRoot, safe)
        val deletedDirectory = !directory.exists() || directory.deleteRecursively()
        libraryRoot.listFiles()?.filter { file ->
            file.isFile && file.name.startsWith(safe, ignoreCase = true) &&
                file.extension.lowercase(Locale.US) in setOf("zip", "part")
        }?.forEach { it.delete() }
        return deletedDirectory
    }

    fun installArchive(archive: File, context: Context, libraryName: String): File {
        val destination = installDirectory(context, libraryName)
        val temporary = File(destination.parentFile, ".${destination.name}.installing")
        temporary.deleteRecursively()
        temporary.mkdirs()
        var totalBytes = 0L
        var entries = 0
        try {
            ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) throw IOException("Library archive has too many files")
                    val target = File(temporary, entry.name).canonicalFile
                    val base = temporary.canonicalFile
                    if (target != base && !target.path.startsWith(base.path + File.separator)) {
                        throw IOException("Unsafe library archive path")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count <= 0) break
                                totalBytes += count
                                if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                    throw IOException("Library archive is too large")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val marker = File(temporary, ".luma-library-installed")
            marker.writeText("name=$libraryName\n")
            destination.deleteRecursively()
            if (!temporary.renameTo(destination)) {
                throw IOException("Unable to install library ${libraryName}")
            }
            return destination
        } catch (error: Exception) {
            temporary.deleteRecursively()
            throw error
        }
    }

    /** Returns installed library examples grouped by library name and file name. */
    fun exampleGroups(context: Context): Map<String, List<Pair<String, String>>> {
        val result = linkedMapOf<String, List<Pair<String, String>>>()
        root(context).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase(Locale.US) }
            ?.take(80)
            ?.forEach { libraryDirectory ->
                val examples = libraryDirectory.walkTopDown()
                    .filter { file ->
                        file.isFile && file.extension.lowercase(Locale.US) in setOf("ino", "cpp") &&
                            isExamplePath(libraryDirectory, file)
                    }
                    .take(80)
                    .mapNotNull { file ->
                        val code = runCatching {
                            if (file.length() > MAX_EXAMPLE_BYTES) return@runCatching null
                            file.readText()
                        }.getOrNull() ?: return@mapNotNull null
                        file.name to code
                    }
                    .toList()
                if (examples.isNotEmpty()) result[libraryDirectory.name] = examples
            }
        return result
    }

    private fun isExamplePath(root: File, file: File): Boolean {
        val relative = runCatching { file.relativeTo(root).path }.getOrDefault("")
        return relative.split(File.separatorChar).any { it.equals("examples", true) || it.equals("example", true) }
    }

    /** Returns only selected libraries and libraries referenced by the sketch. */
    fun directoriesFor(context: Context, selectedNames: Set<String>, sourceCode: String): List<File> {
        val installed = root(context).listFiles()?.filter { it.isDirectory }.orEmpty()
        if (installed.isEmpty()) return emptyList()
        val selected = selectedNames.map(::safeName).toSet()
        val includedBases = includePattern.findAll(sourceCode)
            .map { match -> File(match.groupValues[1]).nameWithoutExtension }
            .map(::safeName)
            .toSet()
        return installed.filter { directory ->
            val name = safeName(directory.name)
            name in selected || includedBases.any { base ->
                base == name || base.contains(name) || name.contains(base)
            }
        }.sortedBy { it.name.lowercase(Locale.US) }
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.')
        .ifBlank { "library" }

    private const val BUFFER_SIZE = 32 * 1024
    private const val MAX_EXAMPLE_BYTES = 512L * 1024L
}
