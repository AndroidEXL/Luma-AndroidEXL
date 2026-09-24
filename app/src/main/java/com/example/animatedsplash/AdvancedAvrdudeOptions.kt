package com.example.animatedsplash

import java.io.File

enum class AdvancedAvrdudeOperation(val label: String) {
    IDENTIFY("التعرّف على اللوحة والتوقيع"),
    READ("قراءة الذاكرة إلى ملف"),
    WRITE("كتابة ملف إلى الذاكرة"),
    ERASE("مسح ذاكرة الشريحة"),
    WRITE_VALUE("كتابة قيمة Fuse أو Lock")
}

enum class AdvancedAvrdudeMemory(val label: String, val avrdudeName: String, val defaultExtension: String) {
    FLASH("Flash", "flash", "hex"),
    EEPROM("EEPROM", "eeprom", "eep"),
    LOW_FUSE("Low Fuse", "lfuse", "bin"),
    HIGH_FUSE("High Fuse", "hfuse", "bin"),
    EXTENDED_FUSE("Extended Fuse", "efuse", "bin"),
    LOCK_BITS("Lock bits", "lock", "bin"),
    SIGNATURE("Device signature", "signature", "bin")
}

enum class AdvancedAvrdudeFormat(val label: String, val avrdudeName: String) {
    INTEL_HEX("Intel HEX (.hex)", "i"),
    RAW_BINARY("Raw binary (.bin)", "r")
}

data class AdvancedAvrdudeOptions(
    val operation: AdvancedAvrdudeOperation,
    val memory: AdvancedAvrdudeMemory = AdvancedAvrdudeMemory.FLASH,
    val format: AdvancedAvrdudeFormat = AdvancedAvrdudeFormat.INTEL_HEX,
    val dryRun: Boolean = false,
    val disableAutoErase: Boolean = false,
    val verbosity: Int = 1,
    val valueToWrite: String = ""
) {
    fun requiresInputFile(): Boolean = operation == AdvancedAvrdudeOperation.WRITE

    fun producesOutputFile(): Boolean = operation == AdvancedAvrdudeOperation.READ

    fun requiresDangerConfirmation(): Boolean = operation == AdvancedAvrdudeOperation.ERASE ||
        operation == AdvancedAvrdudeOperation.WRITE_VALUE ||
        (operation == AdvancedAvrdudeOperation.WRITE && memory !in setOf(AdvancedAvrdudeMemory.FLASH, AdvancedAvrdudeMemory.EEPROM))

    fun toArguments(inputFile: File? = null, outputFile: File? = null): List<String> {
        val result = mutableListOf<String>()
        repeat(verbosity.coerceIn(0, 3)) { result += "-v" }
        if (dryRun) result += "-n"
        if (disableAutoErase) result += "-D"
        when (operation) {
            AdvancedAvrdudeOperation.IDENTIFY -> Unit
            AdvancedAvrdudeOperation.ERASE -> result += "-e"
            AdvancedAvrdudeOperation.READ -> {
                requireNotNull(outputFile) { "Read operation needs an output file" }
                result += listOf("-U", "${memory.avrdudeName}:r:${outputFile.absolutePath}:${format.avrdudeName}")
            }
            AdvancedAvrdudeOperation.WRITE -> {
                requireNotNull(inputFile) { "Write operation needs an input file" }
                result += listOf("-U", "${memory.avrdudeName}:w:${inputFile.absolutePath}:${format.avrdudeName}")
            }
            AdvancedAvrdudeOperation.WRITE_VALUE -> {
                val normalized = valueToWrite.trim()
                require(memory in setOf(AdvancedAvrdudeMemory.LOW_FUSE, AdvancedAvrdudeMemory.HIGH_FUSE, AdvancedAvrdudeMemory.EXTENDED_FUSE, AdvancedAvrdudeMemory.LOCK_BITS)) {
                    "Value writes are limited to Fuses and Lock bits"
                }
                require(normalized.matches(Regex("0[xX][0-9a-fA-F]{1,2}|[0-9]{1,3}"))) { "Fuse or lock value must be a one-byte decimal or hexadecimal value" }
                result += listOf("-U", "${memory.avrdudeName}:w:$normalized:m")
            }
        }
        return result
    }

    fun preview(): String = buildString {
        append(operation.label)
        if (operation != AdvancedAvrdudeOperation.IDENTIFY && operation != AdvancedAvrdudeOperation.ERASE) {
            append(" • ").append(memory.label)
        }
        append(" • ").append(format.label)
        if (dryRun) append(" • Dry-run")
        if (disableAutoErase) append(" • No auto erase")
        if (verbosity > 0) append(" • Verbose ×").append(verbosity)
    }
}
