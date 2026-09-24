package com.example.animatedsplash

import android.content.Context
import java.io.File
import java.util.Locale

data class ProjectDependencyReport(
    val includes: List<String>,
    val linked: List<String>,
    val missing: List<String>,
    val installedAvailable: List<String>
)

object ProjectDependencyAnalyzer {
    private val includePattern = Regex("#include\\s*[<\\\"]([^>\\\"]+)[>\\\"]")
    private val coreHeaders = setOf(
        "arduino", "stdint", "stdio", "stdlib", "string", "math", "avr", "util", "wire", "spi", "eeprom"
    )

    fun analyze(context: Context, selectedLibraries: Set<String>, sourceCode: String): ProjectDependencyReport {
        val includes = includePattern.findAll(sourceCode)
            .map { match -> File(match.groupValues[1]).nameWithoutExtension }
            .filter { it.isNotBlank() && canonical(it) !in coreHeaders }
            .distinctBy(::canonical)
            .toList()
        val installed = ArduinoLibraryStorage.installedNames(context).toList()
        val linked = includes.filter { include -> selectedLibraries.any { matches(it, include) } }
        val installedAvailable = includes.filter { include -> installed.any { matches(it, include) } }
        val missing = includes.filter { include -> installed.none { matches(it, include) } }
        return ProjectDependencyReport(includes, linked, missing, installedAvailable)
    }

    private fun matches(left: String, right: String): Boolean {
        val a = canonical(left)
        val b = canonical(right)
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun canonical(value: String): String = value
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")
}
