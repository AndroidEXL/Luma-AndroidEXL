package com.example.avrandroid

internal object PtyBridge {
    private val loaded = runCatching {
        System.loadLibrary("avr_android")
        true
    }.getOrDefault(false)

    fun open(): Pair<Int, String>? {
        if (!loaded) return null
        val result = nativeOpen() ?: return null
        if (result.size < 2) return null
        return result[0].toIntOrNull()?.let { it to result[1] }
    }

    fun read(fd: Int, buffer: ByteArray, timeoutMs: Int): Int =
        if (loaded) nativeRead(fd, buffer, timeoutMs) else -1

    fun write(fd: Int, buffer: ByteArray, length: Int): Int =
        if (loaded) nativeWrite(fd, buffer, length) else -1

    fun close(fd: Int) {
        if (loaded) nativeClose(fd)
    }

    private external fun nativeOpen(): Array<String>?
    private external fun nativeRead(fd: Int, destination: ByteArray, timeoutMs: Int): Int
    private external fun nativeWrite(fd: Int, source: ByteArray, length: Int): Int
    private external fun nativeClose(fd: Int)
}
