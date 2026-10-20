package me.kylofz.fettle.optimizer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object JsonMinifier {
    fun minifyIfSmaller(bytes: ByteArray): ByteArray {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return bytes
        }

        val compact = StringBuilder(text.length)
        var inString = false
        var escaped = false

        for (character in text) {
            if (inString) {
                compact.append(character)
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
                continue
            }

            when {
                character == '"' -> {
                    inString = true
                    compact.append(character)
                }
                character != ' ' && character != '\n' && character != '\r' && character != '\t' -> {
                    compact.append(character)
                }
            }
        }

        // Malformed JSON is left byte-for-byte intact instead of being guessed at.
        if (inString || escaped) return bytes
        val compactBytes = compact.toString().toByteArray(StandardCharsets.UTF_8)
        return if (compactBytes.size < bytes.size) compactBytes else bytes
    }
}
