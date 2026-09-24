package com.example.animatedsplash

/** A lightweight, deterministic formatter for Arduino sketches that only normalizes indentation. */
object ArduinoCodeFormatter {
    fun format(source: String): String {
        if (source.isBlank()) return source
        var depth = 0
        val output = source.replace("\r\n", "\n").lines().map { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@map ""
            val leadingClose = if (line.startsWith("}")) 1 else 0
            val indentDepth = (depth - leadingClose).coerceAtLeast(0)
            val formatted = "  ".repeat(indentDepth) + line
            depth = (depth + line.count { it == '{' } - line.count { it == '}' }).coerceAtLeast(0)
            formatted
        }
        return output.joinToString("\n").trimEnd() + if (source.endsWith("\n")) "\n" else ""
    }
}
