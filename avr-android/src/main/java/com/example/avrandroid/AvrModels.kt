package com.example.avrandroid

import java.io.File

enum class AvrBoard(
    val fqbn: String,
    val mcu: String,
    val variant: String,
    val cpuHz: Long,
    val programmer: String,
    val uploadBaudRate: Int,
    val flashPageSize: Int,
    val uses1200Touch: Boolean = false
) {
    UNO("arduino:avr:uno", "atmega328p", "standard", 16_000_000L, "arduino", 115200, 128),
    NANO("arduino:avr:nano", "atmega328p", "standard", 16_000_000L, "arduino", 57600, 128),
    MEGA("arduino:avr:mega", "atmega2560", "mega", 16_000_000L, "wiring", 115200, 256),
    LEONARDO("arduino:avr:leonardo", "atmega32u4", "leonardo", 16_000_000L, "avr109", 57600, 128, true),
    MICRO("arduino:avr:micro", "atmega32u4", "micro", 16_000_000L, "avr109", 57600, 128, true),
    PRO_MINI("arduino:avr:pro", "atmega328p", "standard", 16_000_000L, "arduino", 57600, 128);
    val memoryCapacity: AvrMemoryCapacity
        get() = when (mcu) {
            "atmega2560" -> AvrMemoryCapacity(flashBytes = 262_144, sramBytes = 8_192, eepromBytes = 4_096)
            "atmega32u4" -> AvrMemoryCapacity(flashBytes = 32_768, sramBytes = 2_560, eepromBytes = 1_024)
            else -> AvrMemoryCapacity(flashBytes = 32_768, sramBytes = 2_048, eepromBytes = 1_024)
        }
}

data class AvrMemoryCapacity(
    val flashBytes: Int,
    val sramBytes: Int,
    val eepromBytes: Int
)

data class AvrMemoryUsage(
    val flashBytes: Int,
    val sramBytes: Int,
    val sections: Map<String, Int> = emptyMap()
)

data class AvrCompileRequest(
    val projectName: String,
    val code: String,
    val board: AvrBoard = AvrBoard.UNO,
    val toolchainRoot: String? = null,
    val libraryDirectories: List<String> = emptyList()
)

data class AvrDiagnostic(
    val line: Int,
    val column: Int,
    val severity: Severity,
    val message: String
) {
    enum class Severity { ERROR, WARNING, NOTE }
}

data class AvrUploadRequest(
    val hexFile: File,
    val board: AvrBoard,
    val usbDevice: android.hardware.usb.UsbDevice
)

data class AvrUploadResult(
    val success: Boolean,
    val output: String,
    val bytesWritten: Int = 0
)

data class AvrReadRequest(
    val flashOutputFile: File,
    val eepromOutputFile: File? = null,
    val board: AvrBoard,
    val usbDevice: android.hardware.usb.UsbDevice
)

data class AvrReadResult(
    val success: Boolean,
    val output: String,
    val flashOutputFile: File,
    val eepromOutputFile: File? = null
)

data class AvrdudeCommandRequest(
    val board: AvrBoard,
    val usbDevice: android.hardware.usb.UsbDevice,
    val arguments: List<String>
)

data class AvrdudeCommandResult(
    val success: Boolean,
    val output: String
)

sealed class AvrCompileResult {
    data class Success(
        val output: String,
        val nativeEngineVersion: String,
        val hexFilePath: String? = null,
        val memoryUsage: AvrMemoryUsage? = null
    ) : AvrCompileResult()

    data class Failure(
        val output: String,
        val diagnostics: List<AvrDiagnostic>,
        val nativeEngineVersion: String
    ) : AvrCompileResult()

    data class ToolchainUnavailable(val message: String) : AvrCompileResult()
}

typealias AvrCompileCallback = (AvrCompileResult) -> Unit
typealias AvrUploadCallback = (AvrUploadResult) -> Unit
typealias AvrReadCallback = (AvrReadResult) -> Unit
typealias AvrdudeCommandCallback = (AvrdudeCommandResult) -> Unit
