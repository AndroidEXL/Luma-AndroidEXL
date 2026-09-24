package com.example.animatedsplash

import android.content.Context
import android.util.JsonReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class LibraryIndexService(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val cacheFile = File(appContext.filesDir, "arduino_library_index.json")

    fun loadAsync(
        onSuccess: (List<ArduinoLibrary>, Boolean) -> Unit,
        onFailure: (String) -> Unit
    ) {
        executor.execute {
            try {
                val connection = (URL(INDEX_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "gzip")
                    setRequestProperty("User-Agent", "Luma-Android-Arduino-IDE/1.0")
                }
                try {
                    ensureSuccessful(connection)
                    openResponseStream(connection).use { input -> copyToCache(input) }
                } finally {
                    connection.disconnect()
                }
                val libraries = FileInputStream(cacheFile).use(::parseStream)
                onSuccess(libraries, false)
            } catch (error: Exception) {
                val cached = runCatching {
                    if (cacheFile.exists()) FileInputStream(cacheFile).use(::parseStream) else null
                }.getOrNull()
                when {
                    cached != null && cached.isNotEmpty() -> onSuccess(cached, true)
                    else -> {
                        val registryFallback = runCatching { loadRegistryFallback() }.getOrNull()
                        if (!registryFallback.isNullOrEmpty()) onSuccess(registryFallback, true)
                        else onFailure(error.message ?: "Unable to load Arduino library index")
                    }
                }
            }
        }
    }

    fun downloadAsync(
        libraries: List<ArduinoLibrary>,
        onProgress: (ArduinoLibrary) -> Unit,
        onComplete: (List<ArduinoLibrary>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        executor.execute {
            val destination = File(appContext.filesDir, "arduino-libraries").apply { mkdirs() }
            val completed = mutableListOf<ArduinoLibrary>()
            try {
                libraries.forEach { library ->
                    val url = library.downloadUrl ?: return@forEach
                    val fileName = library.archiveFileName ?: "${library.name}-${library.version}.zip"
                    val target = File(destination, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    downloadArchiveWithRetry(url, target)
                    ArduinoLibraryStorage.installArchive(target, appContext, library.name)
                    completed.add(library)
                    onProgress(library)
                }
                onComplete(completed)
            } catch (error: Exception) {
                onFailure(error.message ?: "Library download failed")
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun downloadArchiveWithRetry(url: String, target: File) {
        val candidates = linkedSetOf(url)
        if (url.contains("/archive/refs/heads/main.zip")) {
            candidates.add(url.replace("/archive/refs/heads/main.zip", "/archive/refs/heads/master.zip"))
        }
        if (url.endsWith(".git")) candidates.add(url.removeSuffix(".git"))
        var lastError: Exception? = null
        for (candidate in candidates) {
            val temporary = File(target.parentFile, "${target.name}.part")
            temporary.delete()
            val connection = (URL(candidate).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 120_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/zip, application/octet-stream")
                setRequestProperty("User-Agent", "Luma-Android-Arduino-IDE/1.0")
            }
            try {
                ensureSuccessful(connection)
                var total = 0L
                openResponseStream(connection).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            total += count
                            if (total > MAX_ARCHIVE_BYTES) throw IOException("Library archive download is too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                return
            } catch (error: Exception) {
                lastError = error
                temporary.delete()
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("Library archive download failed")
    }

    private fun loadRegistryFallback(): List<ArduinoLibrary> {
        val connection = (URL(REGISTRY_REPOSITORIES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "Luma-Android-Arduino-IDE/1.0")
        }
        return try {
            ensureSuccessful(connection)
            openResponseStream(connection).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val repository = line.trim().removeSuffix("/")
                    if (!repository.startsWith("https://github.com/")) return@mapNotNull null
                    val clean = repository.removeSuffix(".git")
                    val name = clean.substringAfterLast('/').ifBlank { return@mapNotNull null }
                    ArduinoLibrary(
                        name = name,
                        description = "Arduino library from the official Arduino registry",
                        category = "Arduino Library",
                        version = "registry",
                        downloadUrl = "$clean/archive/refs/heads/main.zip",
                        archiveFileName = "${name}-registry.zip",
                        official = true
                    )
                }.distinctBy { it.name.lowercase() }.sortedBy { it.name.lowercase() }.take(MAX_FALLBACK_LIBRARIES).toList()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureSuccessful(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status !in 200..299) {
            throw IOException("HTTP $status from ${connection.url}")
        }
    }

    private fun openResponseStream(connection: HttpURLConnection): InputStream {
        val input = connection.inputStream
        return if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(input, BUFFER_SIZE)
        } else {
            input
        }
    }

    private fun copyToCache(input: InputStream) {
        val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        var total = 0L
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_INDEX_BYTES) throw IOException("Arduino library index is too large")
                    output.write(buffer, 0, count)
                }
            }
            if (!temporary.renameTo(cacheFile)) {
                temporary.copyTo(cacheFile, overwrite = true)
                temporary.delete()
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun parseStream(input: InputStream): List<ArduinoLibrary> {
        val latest = linkedMapOf<String, ArduinoLibrary>()
        JsonReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "libraries" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            readLibrary(reader)?.let { candidate ->
                                val previous = latest[candidate.name]
                                if (previous == null || compareVersions(candidate.version, previous.version) > 0) {
                                    latest[candidate.name] = candidate
                                }
                            }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return latest.values.sortedBy { it.name.lowercase() }
    }

    private fun readLibrary(reader: JsonReader): ArduinoLibrary? {
        var name = ""
        var description = ""
        var category = "Arduino Library"
        var version = ""
        var downloadUrl: String? = null
        var archiveFileName: String? = null
        var author = ""
        var repository = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "sentence" -> if (description.isBlank()) description = reader.nextString()
                "paragraph" -> if (description.isBlank()) description = reader.nextString()
                "category" -> category = reader.nextString()
                "version" -> version = reader.nextString()
                "url" -> downloadUrl = reader.nextString()
                "archiveFileName" -> archiveFileName = reader.nextString()
                "author" -> author = reader.nextString()
                "repository" -> repository = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (name.isBlank()) return null
        return ArduinoLibrary(
            name = name,
            description = description.replace("<br />", " ").trim(),
            category = category,
            version = version,
            downloadUrl = downloadUrl,
            archiveFileName = archiveFileName,
            official = author.contains("Arduino", true) || repository.contains("arduino", true)
        )
    }

    private fun compareVersions(first: String, second: String): Int {
        val left = first.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        val right = second.split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }.map { it.toIntOrNull() ?: 0 }
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val result = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return first.compareTo(second, ignoreCase = true)
    }

    companion object {
        const val INDEX_URL = "https://downloads.arduino.cc/libraries/library_index.json"
        private const val REGISTRY_REPOSITORIES_URL = "https://raw.githubusercontent.com/arduino/library-registry/main/repositories.txt"
        private const val BUFFER_SIZE = 16 * 1024
        private const val MAX_INDEX_BYTES = 128L * 1024L * 1024L
        private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
        private const val MAX_FALLBACK_LIBRARIES = 10_000
    }
}
