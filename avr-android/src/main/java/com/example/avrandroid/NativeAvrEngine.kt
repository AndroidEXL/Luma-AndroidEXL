package com.example.avrandroid

internal class NativeAvrEngine {

    val isAvailable: Boolean
        get() = loaded

    fun version(): String = if (loaded) nativeVersion() else "native-engine-unavailable"

    fun quickScan(code: String): String = if (loaded) nativeQuickScan(code) else "OK|0|0|native scan skipped"

    private val loaded: Boolean

    init {
        loaded = runCatching {
            System.loadLibrary("avr_android")
            true
        }.getOrDefault(false)
    }

    private external fun nativeVersion(): String
    private external fun nativeQuickScan(code: String): String
}
