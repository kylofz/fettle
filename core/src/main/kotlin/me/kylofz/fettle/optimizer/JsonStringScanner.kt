package me.kylofz.fettle.optimizer

internal object JsonStringScanner {
    fun read(text: String): List<String>? = Parser(text).read()

    fun countObjectsInArraysNamed(text: String, property: String): Int? =
        Parser(text, property).countObjects()

    private class Parser(
        private val text: String,
        private val countedArrayProperty: String? = null,
    ) {
        private val strings = ArrayList<String>()
        private var countedObjects = 0
        private var index = 0

        fun read(): List<String>? = parse().takeIf { it }?.let { strings }

        fun countObjects(): Int? = parse().takeIf { it }?.let { countedObjects }

        private fun parse(): Boolean = try {
            whitespace()
            value(0)
            whitespace()
            if (index != text.length) invalid()
            true
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun value(depth: Int, countObjectsInArray: Boolean = false) {
            if (depth > MAX_DEPTH) invalid()
            whitespace()
            when (peek()) {
                '"' -> {
                    val value = string(capture = countedArrayProperty == null)
                    if (countedArrayProperty == null) strings += value ?: invalid()
                }
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1, countObjectsInArray)
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun objectValue(depth: Int) {
            expect('{')
            whitespace()
            if (consume('}')) return
            while (true) {
                whitespace()
                if (peek() != '"') invalid()
                val key = string(capture = true) ?: invalid()
                if (countedArrayProperty == null) strings += key
                whitespace()
                expect(':')
                value(depth, key == countedArrayProperty)
                whitespace()
                when {
                    consume(',') -> Unit
                    consume('}') -> return
                    else -> invalid()
                }
            }
        }

        private fun arrayValue(depth: Int, countObjects: Boolean) {
            expect('[')
            whitespace()
            if (consume(']')) return
            while (true) {
                if (countObjects && peek() == '{' && countedObjects < Int.MAX_VALUE) countedObjects++
                value(depth)
                whitespace()
                when {
                    consume(',') -> Unit
                    consume(']') -> return
                    else -> invalid()
                }
            }
        }

        private fun string(capture: Boolean): String? {
            expect('"')
            val value = if (capture) StringBuilder() else null
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> return value?.toString()
                    character == '\\' -> {
                        if (index == text.length) invalid()
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> value?.append(escaped)
                            'b' -> value?.append('\b')
                            'f' -> value?.append('\u000C')
                            'n' -> value?.append('\n')
                            'r' -> value?.append('\r')
                            't' -> value?.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) invalid()
                                val codeUnit = text.substring(index, index + 4).toIntOrNull(16) ?: invalid()
                                value?.append(codeUnit.toChar())
                                index += 4
                            }
                            else -> invalid()
                        }
                    }
                    character.code < 0x20 -> invalid()
                    else -> value?.append(character)
                }
            }
            invalid()
        }

        private fun number() {
            consume('-')
            when {
                consume('0') -> Unit
                peek() in '1'..'9' -> digits()
                else -> invalid()
            }
            if (consume('.')) {
                if (peek() !in '0'..'9') invalid()
                digits()
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-')
                if (peek() !in '0'..'9') invalid()
                digits()
            }
        }

        private fun digits() {
            while (peek() in '0'..'9') index++
        }

        private fun literal(expected: String) {
            if (!text.startsWith(expected, index)) invalid()
            index += expected.length
        }

        private fun whitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++
        }

        private fun expect(character: Char) {
            if (!consume(character)) invalid()
        }

        private fun consume(character: Char): Boolean {
            if (peek() != character) return false
            index++
            return true
        }

        private fun peek(): Char = text.getOrNull(index) ?: '\u0000'

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid JSON")

        private companion object {
            const val MAX_DEPTH = 64
        }
    }
}
