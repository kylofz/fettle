package me.kylofz.fettle.device

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

public enum class MemoryTierClaimStatus {
    VALID,
    MISSING,
    INVALID,
    UNDECODABLE,
}

/** Client-reported Bedrock capability hints, not verified hardware measurements. */
public data class BedrockDeviceCapabilities @JvmOverloads constructor(
    public val platform: String?,
    public val deviceOs: Int?,
    public val deviceModel: String?,
    public val memoryTier: Int?,
    public val maxViewDistance: Int?,
    public val graphicsMode: Int?,
    public val compatibleWithClientSideChunkGen: Boolean?,
    public val gameVersion: String?,
    public val clientDataDecoded: Boolean = false,
    public val deviceOsLabel: String? = null,
    public val memoryTierClaimPresent: Boolean = false,
) {
    public val memoryTierDescription: String
        get() = when (memoryTier) {
            0 -> "SuperLow (roughly <1.5 GB RAM)"
            1 -> "Low (roughly <2 GB RAM)"
            2 -> "Mid (roughly <4 GB RAM)"
            3 -> "High (roughly <8 GB RAM)"
            4 -> "SuperHigh (roughly >8 GB RAM)"
            else -> "unknown"
        }

    public val memoryTierClaimStatus: MemoryTierClaimStatus
        get() = when {
            memoryTier != null -> MemoryTierClaimStatus.VALID
            !clientDataDecoded -> MemoryTierClaimStatus.UNDECODABLE
            !memoryTierClaimPresent -> MemoryTierClaimStatus.MISSING
            else -> MemoryTierClaimStatus.INVALID
        }

    /** Fills identity fields Geyser already parsed when the original client-data string is unavailable. */
    public fun withGeyserParsedIdentity(
        geyserDeviceOs: Int?,
        geyserDeviceModel: String?,
        geyserGameVersion: String?,
    ): BedrockDeviceCapabilities = withGeyserParsedIdentity(
        geyserDeviceOs, geyserDeviceModel, geyserGameVersion, null
    )

    public fun withGeyserParsedIdentity(
        geyserDeviceOs: Int?,
        geyserDeviceModel: String?,
        geyserGameVersion: String?,
        geyserDeviceOsLabel: String?,
    ): BedrockDeviceCapabilities {
        val parsedDeviceOs = geyserDeviceOs?.takeIf { it in 1..15 }
        return copy(
            deviceOs = deviceOs ?: parsedDeviceOs,
            deviceModel = deviceModel ?: validatedClientString(geyserDeviceModel, 128),
            gameVersion = gameVersion ?: validatedClientString(geyserGameVersion, 64),
            deviceOsLabel = deviceOsLabel ?: validatedClientString(geyserDeviceOsLabel, 32)
                ?.takeIf { deviceOs == null || deviceOs == parsedDeviceOs },
        )
    }

    public companion object {
        private const val MAX_JWT_CHARS = 2 * 1024 * 1024
        private const val MAX_PAYLOAD_BYTES = 1024 * 1024
        /** Parses client data after Geyser verifies its signature; capability fields remain client-reported claims. */
        @JvmStatic
        public fun fromGeyserClientDataJwt(jwt: String?, platform: String?): BedrockDeviceCapabilities {
            val safePlatform = platform?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= 64 && it.none { character -> Character.isISOControl(character) }
            }
            val unknown = BedrockDeviceCapabilities(safePlatform, null, null, null, null, null, null, null)
            if (jwt.isNullOrBlank() || jwt.length > MAX_JWT_CHARS) return unknown

            val firstDot = jwt.indexOf('.')
            val secondDot = jwt.indexOf('.', firstDot + 1)
            if (firstDot <= 0 || secondDot <= firstDot + 1 || secondDot == jwt.lastIndex ||
                jwt.indexOf('.', secondDot + 1) >= 0
            ) return unknown

            val payload = try {
                Base64.getUrlDecoder().decode(jwt.substring(firstDot + 1, secondDot))
            } catch (_: IllegalArgumentException) {
                return unknown
            }
            if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) return unknown

            val json = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString()
            } catch (_: CharacterCodingException) {
                return unknown
            }
            val fields = JsonScalarReader(json).read() ?: return unknown

            return BedrockDeviceCapabilities(
                platform = safePlatform,
                deviceOs = fields.integer("DeviceOS", 1..15),
                deviceModel = validatedClientString(fields.string("DeviceModel"), 128),
                memoryTier = fields.integer("MemoryTier", 0..4),
                maxViewDistance = fields.integer("MaxViewDistance", 1..512),
                graphicsMode = fields.integer("GraphicsMode", 0..32),
                compatibleWithClientSideChunkGen = fields.boolean("CompatibleWithClientSideChunkGen"),
                gameVersion = validatedClientString(fields.string("GameVersion"), 64),
                clientDataDecoded = true,
                memoryTierClaimPresent = fields.containsKey("MemoryTier"),
            )
        }

        private fun validatedClientString(value: String?, maxLength: Int): String? = value?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= maxLength && it.none(Character::isISOControl)
        }

        private fun Map<String, JsonScalar>.integer(name: String, range: IntRange): Int? {
            val raw = (this[name] as? JsonScalar.NumberValue)?.raw ?: return null
            val value = try {
                BigDecimal(raw).intValueExact()
            } catch (_: ArithmeticException) {
                return null
            } catch (_: NumberFormatException) {
                return null
            }
            return value.takeIf { it in range }
        }

        private fun Map<String, JsonScalar>.string(name: String): String? =
            (this[name] as? JsonScalar.StringValue)?.value

        private fun Map<String, JsonScalar>.boolean(name: String): Boolean? =
            (this[name] as? JsonScalar.BooleanValue)?.value
    }
}

private sealed class JsonScalar {
    data class StringValue(val value: String) : JsonScalar()
    data class NumberValue(val raw: String) : JsonScalar()
    data class BooleanValue(val value: Boolean) : JsonScalar()
    object NullValue : JsonScalar()
    object NonScalarValue : JsonScalar()
    object IgnoredValue : JsonScalar()
}

private class JsonScalarReader(private val text: String) {
    private companion object {
        const val MAX_DEPTH = 64
        val CAPTURED_FIELDS = setOf(
            "DeviceOS",
            "DeviceModel",
            "GameVersion",
            "MemoryTier",
            "MaxViewDistance",
            "GraphicsMode",
            "CompatibleWithClientSideChunkGen",
        )
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }

    private var index = 0

    fun read(): Map<String, JsonScalar>? = try {
        skipWhitespace()
        val result = readRootObject()
        skipWhitespace()
        if (index != text.length) invalid()
        result
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readRootObject(): Map<String, JsonScalar> {
        expect('{')
        skipWhitespace()
        val result = HashMap<String, JsonScalar>()
        if (consume('}')) return result

        while (true) {
            skipWhitespace()
            val key = readString(capture = true) ?: invalid()
            skipWhitespace()
            expect(':')
            val capture = key in CAPTURED_FIELDS
            val value = readValue(depth = 1, capture = capture)
            if (capture && result.put(key, value) != null) invalid()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume('}') -> return result
                else -> invalid()
            }
        }
    }

    private fun readValue(depth: Int, capture: Boolean): JsonScalar {
        if (depth > MAX_DEPTH) invalid()
        skipWhitespace()
        return when (peek()) {
            '"' -> {
                val value = readString(capture)
                if (capture) JsonScalar.StringValue(value ?: invalid()) else JsonScalar.IgnoredValue
            }
            '{' -> {
                skipObject(depth + 1)
                JsonScalar.NonScalarValue
            }
            '[' -> {
                skipArray(depth + 1)
                JsonScalar.NonScalarValue
            }
            't' -> {
                readLiteral("true")
                if (capture) JsonScalar.BooleanValue(true) else JsonScalar.IgnoredValue
            }
            'f' -> {
                readLiteral("false")
                if (capture) JsonScalar.BooleanValue(false) else JsonScalar.IgnoredValue
            }
            'n' -> {
                readLiteral("null")
                if (capture) JsonScalar.NullValue else JsonScalar.IgnoredValue
            }
            else -> {
                val raw = readNumber()
                if (capture) JsonScalar.NumberValue(raw) else JsonScalar.IgnoredValue
            }
        }
    }

    private fun skipObject(depth: Int) {
        if (depth > MAX_DEPTH) invalid()
        expect('{')
        skipWhitespace()
        if (consume('}')) return
        while (true) {
            skipWhitespace()
            readString(capture = false)
            skipWhitespace()
            expect(':')
            readValue(depth + 1, capture = false)
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume('}') -> return
                else -> invalid()
            }
        }
    }

    private fun skipArray(depth: Int) {
        if (depth > MAX_DEPTH) invalid()
        expect('[')
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            readValue(depth + 1, capture = false)
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume(']') -> return
                else -> invalid()
            }
        }
    }

    private fun readString(capture: Boolean): String? {
        expect('"')
        val result = if (capture) StringBuilder() else null
        while (index < text.length) {
            val character = text[index++]
            when {
                character == '"' -> return result?.toString()
                character == '\\' -> {
                    if (index >= text.length) invalid()
                    val escaped = text[index++]
                    when (escaped) {
                        '"', '\\', '/' -> result?.append(escaped)
                        'b' -> result?.append('\b')
                        'f' -> result?.append('\u000C')
                        'n' -> result?.append('\n')
                        'r' -> result?.append('\r')
                        't' -> result?.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) invalid()
                            val codeUnit = text.substring(index, index + 4).toIntOrNull(16) ?: invalid()
                            result?.append(codeUnit.toChar())
                            index += 4
                        }
                        else -> invalid()
                    }
                }
                character.code < 0x20 -> invalid()
                else -> result?.append(character)
            }
        }
        invalid()
    }

    private fun readNumber(): String {
        val start = index
        while (index < text.length && !isValueDelimiter(text[index])) index++
        val raw = text.substring(start, index)
        if (!NUMBER.matches(raw)) invalid()
        return raw
    }

    private fun readLiteral(literal: String) {
        if (!text.startsWith(literal, index)) invalid()
        index += literal.length
    }

    private fun skipWhitespace() {
        while (index < text.length && isJsonWhitespace(text[index])) index++
    }

    private fun isValueDelimiter(character: Char): Boolean =
        character == ',' || character == '}' || character == ']' || isJsonWhitespace(character)

    private fun isJsonWhitespace(character: Char): Boolean =
        character == ' ' || character == '\t' || character == '\r' || character == '\n'

    private fun expect(character: Char) {
        if (!consume(character)) invalid()
    }

    private fun consume(character: Char): Boolean {
        if (index >= text.length || text[index] != character) return false
        index++
        return true
    }

    private fun peek(): Char? = text.getOrNull(index)

    private fun invalid(): Nothing = throw IllegalArgumentException("Malformed client data JSON")
}
