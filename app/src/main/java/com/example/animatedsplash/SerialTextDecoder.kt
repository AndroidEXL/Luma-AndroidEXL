package com.example.animatedsplash

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decodes USB serial chunks without breaking multi-byte Arabic UTF-8 sequences. */
class SerialTextDecoder(private var charset: Charset) {
    private val pending = ByteArrayOutputStream()

    fun setCharset(newCharset: Charset) {
        charset = newCharset
        pending.reset()
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        pending.write(bytes)
        val data = pending.toByteArray()
        val input = ByteBuffer.wrap(data)
        val output = CharBuffer.allocate((data.size * 2).coerceAtLeast(64))
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            val result = decoder.decode(input, output, false)
            if (result.isError) result.throwException()
            output.flip()
            val decoded = output.toString()
            pending.reset()
            if (input.hasRemaining()) {
                pending.write(data, input.position(), input.remaining())
            }
            decoded
        } catch (_: CharacterCodingException) {
            // Malformed bytes are decoded visibly, but valid UTF-8 fragments remain protected above.
            val replacement = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            val fallback = replacement.decode(ByteBuffer.wrap(data)).toString()
            pending.reset()
            fallback
        }
    }

    fun clear() = pending.reset()
}
