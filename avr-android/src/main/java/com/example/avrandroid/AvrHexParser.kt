package com.example.avrandroid

import java.io.File
import java.util.TreeMap

internal data class AvrHexPage(val byteAddress: Int, val bytes: ByteArray, val usedBytes: Int)

internal class AvrHexImage private constructor(private val memory: TreeMap<Int, Int>) {
    fun pages(pageSize: Int): List<AvrHexPage> {
        if (memory.isEmpty()) return emptyList()
        val result = mutableListOf<AvrHexPage>()
        var pageAddress = memory.firstKey() / pageSize * pageSize
        val lastPage = memory.lastKey() / pageSize * pageSize
        while (pageAddress <= lastPage) {
            val page = ByteArray(pageSize) { 0xFF.toByte() }
            var used = 0
            for (offset in 0 until pageSize) {
                val value = memory[pageAddress + offset] ?: continue
                page[offset] = value.toByte()
                used++
            }
            if (used > 0) result += AvrHexPage(pageAddress, page, used)
            pageAddress += pageSize
        }
        return result
    }

    companion object {
        fun parse(file: File): AvrHexImage {
            val memory = TreeMap<Int, Int>()
            var upperAddress = 0
            file.forEachLine(Charsets.US_ASCII) { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                require(line[0] == ':') { "Intel HEX line does not start with ':'" }
                require(line.length >= 11 && (line.length - 1) % 2 == 0) { "Invalid Intel HEX line length" }
                val bytes = line.substring(1).chunked(2).map { it.toInt(16) }
                val count = bytes[0]
                require(bytes.size == count + 5) { "Intel HEX byte count mismatch" }
                val checksum = bytes.sum() and 0xFF
                require(checksum == 0) { "Intel HEX checksum mismatch" }
                val address = (bytes[1] shl 8) or bytes[2]
                when (bytes[3]) {
                    0x00 -> for (index in 0 until count) {
                        memory[upperAddress + address + index] = bytes[4 + index]
                    }
                    0x01 -> return@forEachLine
                    0x02 -> {
                        require(count == 2) { "Invalid extended segment address record" }
                        upperAddress = ((bytes[4] shl 8) or bytes[5]) shl 4
                    }
                    0x04 -> {
                        require(count == 2) { "Invalid extended linear address record" }
                        upperAddress = ((bytes[4] shl 8) or bytes[5]) shl 16
                    }
                    else -> Unit
                }
            }
            require(memory.isNotEmpty()) { "Intel HEX contains no program data" }
            return AvrHexImage(memory)
        }
    }
}
