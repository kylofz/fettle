package me.kylofz.fettle.optimizer

import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class TextureCrop(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/** Re-encodes unchanged PNGs losslessly and applies a bounded resize when requested. */
internal object PngOptimizer {
    private data class ChunkInfo(val type: String, val start: Int, val dataLength: Int)

    private const val MAX_PIXELS = 20_000_000L
    private const val MAX_SOURCE_DIMENSION = 32_768
    private const val SRGB_GAMMA = 45_455L
    private const val LOSSLESS_PNG_COMPRESSION_QUALITY = 0.2f

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val SAFE_CHUNKS = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS")
    private val COPYABLE_METADATA_CHUNKS = setOf("sRGB", "gAMA", "cHRM", "pHYs", "tEXt", "zTXt", "iTXt", "tIME")
    private val SAFE_COLOR_TYPES = setOf(2, 3, 6)

    fun planTopLeftCropOrFull(bytes: ByteArray, requiredWidth: Int, requiredHeight: Int): TextureCrop? =
        planCropOrFull(bytes, 0, 0, requiredWidth, requiredHeight)

    fun planCropOrFull(bytes: ByteArray, x: Int, y: Int, width: Int, height: Int): TextureCrop? {
        if (!bytes.isPng() || !hasOnlyPixelChunks(bytes)) return null
        val dimensions = pngDimensions(bytes) ?: return null
        if (dimensions.width.toLong() * dimensions.height > MAX_PIXELS ||
            x < 0 || y < 0 || width <= 0 || height <= 0 ||
            x.toLong() + width > dimensions.width || y.toLong() + height > dimensions.height
        ) return null
        // The pack scanner calls this only after proving the geometry UVs are the texture's only use.
        return TextureCrop(x, y, width, height, dimensions.width, dimensions.height)
    }

    fun dimensions(bytes: ByteArray): Pair<Int, Int>? = pngDimensions(bytes)?.let { it.width to it.height }

    fun dimensions(input: InputStream): Pair<Int, Int>? = dimensions(input.readNBytes(33))

    fun supportsResize(bytes: ByteArray): Boolean = resizeMetadata(bytes) != null

    fun decodedPixelHash(bytes: ByteArray): ByteArray? {
        val image = readPixelImage(bytes) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(byteArrayOf(
            (image.width ushr 24).toByte(), (image.width ushr 16).toByte(),
            (image.width ushr 8).toByte(), image.width.toByte(),
            (image.height ushr 24).toByte(), (image.height ushr 16).toByte(),
            (image.height ushr 8).toByte(), image.height.toByte(),
        ))
        val pixels = IntArray(image.width)
        val row = ByteArray(image.width * 4)
        for (y in 0 until image.height) {
            image.getRGB(0, y, image.width, 1, pixels, 0, image.width)
            for (x in pixels.indices) {
                val pixel = pixels[x]
                val offset = x * 4
                row[offset] = (pixel ushr 24).toByte()
                row[offset + 1] = (pixel ushr 16).toByte()
                row[offset + 2] = (pixel ushr 8).toByte()
                row[offset + 3] = pixel.toByte()
            }
            digest.update(row)
        }
        return digest.digest()
    }

    fun sameDecodedPixels(first: ByteArray, second: ByteArray): Boolean {
        if (first.contentEquals(second)) return true
        val left = readPixelImage(first) ?: return false
        val right = readPixelImage(second) ?: return false
        if (left.width != right.width || left.height != right.height) return false
        val leftRow = IntArray(left.width)
        val rightRow = IntArray(right.width)
        for (y in 0 until left.height) {
            left.getRGB(0, y, left.width, 1, leftRow, 0, left.width)
            right.getRGB(0, y, right.width, 1, rightRow, 0, right.width)
            if (!leftRow.contentEquals(rightRow)) return false
        }
        return true
    }

    fun cropRegion(bytes: ByteArray, crop: TextureCrop): ByteArray? {
        if (!bytes.isPng() || !hasOnlyPixelChunks(bytes)) return null
        val dimensions = pngDimensions(bytes) ?: return null
        val pixelCount = dimensions.width.toLong() * dimensions.height.toLong()
        if (pixelCount > MAX_PIXELS || dimensions.width != crop.sourceWidth || dimensions.height != crop.sourceHeight ||
            crop.x < 0 || crop.y < 0 || crop.width <= 0 || crop.height <= 0 ||
            crop.x.toLong() + crop.width > dimensions.width || crop.y.toLong() + crop.height > dimensions.height
        ) return null
        val source = readPngRegion(bytes, dimensions, crop.x, crop.y, crop.width, crop.height) ?: return null
        if (source.width != crop.width || source.height != crop.height) return null

        val encoded = encode(source) ?: return null
        val decoded = try {
            ImageIO.read(ByteArrayInputStream(encoded)) ?: return null
        } catch (_: Exception) {
            return null
        }
        if (decoded.width != crop.width || decoded.height != crop.height) return null
        val expected = IntArray(crop.width)
        val actual = IntArray(crop.width)
        for (y in 0 until crop.height) {
            source.getRGB(0, y, crop.width, 1, expected, 0, crop.width)
            decoded.getRGB(0, y, crop.width, 1, actual, 0, crop.width)
            if (!expected.contentEquals(actual)) return null
        }
        return encoded
    }

    private fun readPngRegion(
        bytes: ByteArray,
        dimensions: ImageDimensions,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use { imageInput ->
            val reader = ImageIO.getImageReaders(imageInput).asSequence().firstOrNull() ?: return null
            try {
                reader.input = imageInput
                if (reader.getWidth(0) != dimensions.width || reader.getHeight(0) != dimensions.height) return null
                return reader.read(0, reader.defaultReadParam.apply {
                    setSourceRegion(Rectangle(x, y, width, height))
                })
            } catch (_: Exception) {
                return null
            } finally {
                reader.dispose()
            }
        }
    }

    private fun readPixelImage(bytes: ByteArray): BufferedImage? {
        if (!bytes.isPng() || !hasOnlyPixelChunks(bytes)) return null
        val dimensions = pngDimensions(bytes) ?: return null
        if (dimensions.width.toLong() * dimensions.height > MAX_PIXELS) return null
        return readPngRegion(bytes, dimensions, 0, 0, dimensions.width, dimensions.height)
    }

    fun optimizeIfSmaller(bytes: ByteArray, maxDimension: Int = 0, fontGlyphAtlas: Boolean = false): ByteArray {
        val metadata = resizeMetadata(bytes) ?: return bytes
        if (!bytes.isPng()) return bytes
        val dimensions = pngDimensions(bytes) ?: return bytes
        val sourcePixels = dimensions.width.toLong() * dimensions.height.toLong()
        val needsResize = maxDimension > 0 && maxOf(dimensions.width, dimensions.height) > maxDimension
        if (sourcePixels > MAX_PIXELS && !needsResize) return bytes
        val subsampling = if (needsResize) sourceSubsampling(dimensions, maxDimension) else 1
        // Point sampling can erase small glyphs that share a large atlas page.
        // shortcut: glyph atlases above MAX_PIXELS use point sampling; add tiled area filtering if such pages are measured.
        val areaResize = fontGlyphAtlas && needsResize && sourcePixels <= MAX_PIXELS

        val source = if (sourcePixels > MAX_PIXELS || subsampling > 1 && !areaResize) {
            readSubsampledPng(bytes, dimensions, subsampling) ?: return bytes
        } else {
            try {
                ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
            } catch (_: Exception) {
                return bytes
            }
        }
        if (source.width.toLong() * source.height.toLong() > MAX_PIXELS) return bytes

        val pixels = IntArray(source.width * source.height)
        source.getRGB(0, 0, source.width, source.height, pixels, 0, source.width)
        val targetDimensions = targetDimensions(dimensions.width, dimensions.height, maxDimension)
        val candidate = when {
            !needsResize || source.width == targetDimensions.first && source.height == targetDimensions.second ->
                exactCandidate(source, pixels)
            areaResize -> resizeArea(source.width, source.height, pixels, targetDimensions.first, targetDimensions.second)
            else -> resizeNearest(source, targetDimensions.first, targetDimensions.second)
        }
        val encoded = encode(candidate)?.let { addMetadata(it, metadata) } ?: return bytes
        val resized = needsResize || candidate.width != dimensions.width || candidate.height != dimensions.height
        if (!resized && encoded.size >= bytes.size) return bytes
        if (resized && (candidate.width <= 0 || candidate.height <= 0)) return bytes

        val roundTrip = try {
            ImageIO.read(ByteArrayInputStream(encoded)) ?: return bytes
        } catch (_: Exception) {
            return bytes
        }
        if (roundTrip.width != candidate.width || roundTrip.height != candidate.height) return bytes
        if (resized) return encoded

        val roundTripPixels = IntArray(pixels.size)
        roundTrip.getRGB(0, 0, roundTrip.width, roundTrip.height, roundTripPixels, 0, roundTrip.width)
        return if (pixels.contentEquals(roundTripPixels)) encoded else bytes
    }

    private fun pngDimensions(bytes: ByteArray): ImageDimensions? {
        if (!bytes.isPng() || bytes.size < 33 || unsignedInt(bytes, 8) != 13L ||
            bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) return null
        val width = unsignedInt(bytes, 16)
        val height = unsignedInt(bytes, 20)
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return null
        return ImageDimensions(width.toInt(), height.toInt())
    }

    private fun readSubsampledPng(bytes: ByteArray, dimensions: ImageDimensions, factor: Int): BufferedImage? {
        val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
        input.use { imageInput ->
            val reader = ImageIO.getImageReaders(imageInput).asSequence().firstOrNull() ?: return null
            try {
                reader.input = imageInput
                if (reader.getWidth(0) != dimensions.width || reader.getHeight(0) != dimensions.height) return null
                val parameter = reader.defaultReadParam
                parameter.setSourceSubsampling(factor, factor, 0, 0)
                return reader.read(0, parameter)
            } catch (_: Exception) {
                return null
            } finally {
                reader.dispose()
            }
        }
    }

    private fun sourceSubsampling(dimensions: ImageDimensions, maxDimension: Int): Int =
        (maxOf(dimensions.width, dimensions.height) / maxDimension).coerceAtLeast(1)

    fun targetDimensions(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (maxDimension <= 0 || maxOf(width, height) <= maxDimension) return width to height
        val scale = maxDimension.toDouble() / maxOf(width, height)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    private data class ImageDimensions(val width: Int, val height: Int)

    internal fun resizeNearest(source: BufferedImage, maxDimension: Int): BufferedImage {
        val (width, height) = targetDimensions(source.width, source.height, maxDimension)
        return resizeNearest(source, width, height)
    }

    private fun resizeNearest(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics: Graphics2D = target.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        return target
    }

    private fun resizeArea(sourceWidth: Int, sourceHeight: Int, sourcePixels: IntArray, width: Int, height: Int): BufferedImage {
        val targetPixels = IntArray(width * height)
        val scaleX = sourceWidth.toDouble() / width
        val scaleY = sourceHeight.toDouble() / height
        for (targetY in 0 until height) {
            val top = targetY * scaleY
            val bottom = (targetY + 1) * scaleY
            val firstY = floor(top).toInt()
            val lastY = ceil(bottom).toInt()
            for (targetX in 0 until width) {
                val left = targetX * scaleX
                val right = (targetX + 1) * scaleX
                val firstX = floor(left).toInt()
                val lastX = ceil(right).toInt()
                var alphaSum = 0.0
                var redSum = 0.0
                var greenSum = 0.0
                var blueSum = 0.0
                for (sourceY in firstY until lastY) {
                    val weightY = minOf(bottom, sourceY + 1.0) - maxOf(top, sourceY.toDouble())
                    for (sourceX in firstX until lastX) {
                        val weight = weightY * (minOf(right, sourceX + 1.0) - maxOf(left, sourceX.toDouble()))
                        val color = sourcePixels[sourceY * sourceWidth + sourceX]
                        val alpha = (color ushr 24) and 0xff
                        if (alpha == 0) continue
                        alphaSum += alpha * weight
                        redSum += ((color ushr 16) and 0xff) * alpha * weight
                        greenSum += ((color ushr 8) and 0xff) * alpha * weight
                        blueSum += (color and 0xff) * alpha * weight
                    }
                }
                val area = (right - left) * (bottom - top)
                val alpha = (alphaSum / area).roundToInt().coerceIn(0, 255)
                val red = if (alphaSum == 0.0) 0 else (redSum / alphaSum).roundToInt().coerceIn(0, 255)
                val green = if (alphaSum == 0.0) 0 else (greenSum / alphaSum).roundToInt().coerceIn(0, 255)
                val blue = if (alphaSum == 0.0) 0 else (blueSum / alphaSum).roundToInt().coerceIn(0, 255)
                targetPixels[targetY * width + targetX] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, targetPixels, 0, width)
        }
    }

    private fun exactCandidate(source: BufferedImage, pixels: IntArray): BufferedImage {
        val colors = LinkedHashSet<Int>()
        for (pixel in pixels) {
            colors += pixel
            if (colors.size > 256) break
        }

        if (colors.size <= 256) {
            val palette = colors.toIntArray()
            val red = ByteArray(palette.size)
            val green = ByteArray(palette.size)
            val blue = ByteArray(palette.size)
            val alpha = ByteArray(palette.size)
            for (index in palette.indices) {
                val color = palette[index]
                red[index] = (color ushr 16).toByte()
                green[index] = (color ushr 8).toByte()
                blue[index] = color.toByte()
                alpha[index] = (color ushr 24).toByte()
            }

            val colorModel = IndexColorModel(8, palette.size, red, green, blue, alpha)
            return BufferedImage(source.width, source.height, BufferedImage.TYPE_BYTE_INDEXED, colorModel).also {
                it.setRGB(0, 0, source.width, source.height, pixels, 0, source.width)
            }
        }

        if (pixels.all { it ushr 24 == 255 }) {
            return BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB).also {
                it.setRGB(0, 0, source.width, source.height, pixels, 0, source.width)
            }
        }
        return source
    }

    private fun encode(image: BufferedImage): ByteArray? {
        val writer = ImageIO.getImageWritersByFormatName("png").asSequence().firstOrNull() ?: return null
        val output = ByteArrayOutputStream()
        return try {
            ImageIO.createImageOutputStream(output).use { imageOutput ->
                writer.output = imageOutput
                val parameter = writer.defaultWriteParam
                if (parameter.canWriteCompressed()) {
                    parameter.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    // The JDK 21 PNG writer maps this to Deflater level 7; PNG pixels remain lossless.
                    parameter.compressionQuality = LOSSLESS_PNG_COMPRESSION_QUALITY
                }
                writer.write(null, IIOImage(image, null, null), parameter)
                imageOutput.flush()
            }
            output.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            writer.dispose()
        }
    }

    private fun ByteArray.isPng(): Boolean {
        if (size < PNG_SIGNATURE.size) return false
        return PNG_SIGNATURE.indices.all { this[it] == PNG_SIGNATURE[it] }
    }

    private fun resizeMetadata(bytes: ByteArray): List<ByteArray>? {
        // shortcut: unsupported or color-managed PNG chunks stay byte-identical until a safe conversion is implemented.
        val chunks = readChunks(bytes) ?: return null
        if (chunks.firstOrNull()?.type != "IHDR" || chunks.first().dataLength != 13) return null

        val metadata = ArrayList<ByteArray>()
        val uniqueMetadata = HashSet<String>()
        var sawImageData = false
        var sawSrgb = false
        var sawGamma = false
        var sawChromaticities = false
        for (chunk in chunks) {
            val dataStart = chunk.start + 8
            when (chunk.type) {
                "IHDR" -> if (chunk.start != PNG_SIGNATURE.size || chunk.dataLength != 13) return null
                "PLTE", "IDAT", "IEND", "tRNS" -> Unit
                in COPYABLE_METADATA_CHUNKS -> {
                    val isColorChunk = chunk.type in setOf("sRGB", "gAMA", "cHRM", "pHYs")
                    if ((!isColorChunk && !validCrc(bytes, chunk)) ||
                        (isColorChunk && (sawImageData || !validCrc(bytes, chunk) || !uniqueMetadata.add(chunk.type)))
                    ) return null
                    val data = bytes.copyOfRange(dataStart, dataStart + chunk.dataLength)
                    when (chunk.type) {
                        "sRGB" -> {
                            if (data.size != 1 || (data[0].toInt() and 0xff) > 3) return null
                            sawSrgb = true
                        }
                        "gAMA" -> {
                            if (data.size != 4 || unsignedInt(data, 0) != SRGB_GAMMA) return null
                            sawGamma = true
                        }
                        "cHRM" -> {
                            if (data.size != 32 || !isSrgbChromaticities(data)) return null
                            sawChromaticities = true
                        }
                        "pHYs" -> if (data.size != 9) return null
                    }
                    metadata += bytes.copyOfRange(chunk.start, chunk.start + 12 + chunk.dataLength)
                }
                else -> return null
            }
            if (chunk.type == "IDAT") sawImageData = true
        }
        if ((sawGamma || sawChromaticities) && !sawSrgb) return null
        return metadata
    }

    private fun addMetadata(encoded: ByteArray, metadata: List<ByteArray>): ByteArray? {
        if (metadata.isEmpty()) return encoded
        val chunks = readChunks(encoded) ?: return null
        val needed = ArrayList<ByteArray>()
        for (sourceChunk in metadata) {
            val type = String(sourceChunk, 4, 4, StandardCharsets.US_ASCII)
            val sourceLength = unsignedInt(sourceChunk, 0).toInt()
            val sourceData = sourceChunk.copyOfRange(8, 8 + sourceLength)
            val targetChunks = chunks.filter { it.type == type }
            if (targetChunks.any { target ->
                    encoded.copyOfRange(target.start + 8, target.start + 8 + target.dataLength).contentEquals(sourceData)
                }) continue
            if (targetChunks.isNotEmpty()) return null
            needed += sourceChunk
        }
        if (needed.isEmpty()) return encoded

        val insertionPoint = chunks.firstOrNull { it.type == "PLTE" || it.type == "IDAT" }?.start ?: return null
        val output = ByteArrayOutputStream(encoded.size + needed.sumOf { it.size })
        output.write(encoded, 0, insertionPoint)
        needed.forEach { output.write(it) }
        output.write(encoded, insertionPoint, encoded.size - insertionPoint)
        return output.toByteArray()
    }

    private fun readChunks(bytes: ByteArray): List<ChunkInfo>? {
        if (!bytes.isPng()) return null
        val chunks = ArrayList<ChunkInfo>()
        var offset = PNG_SIGNATURE.size
        while (offset + 12 <= bytes.size) {
            val length = unsignedInt(bytes, offset)
            if (length > Int.MAX_VALUE || offset.toLong() + 12L + length > bytes.size) return null
            val dataLength = length.toInt()
            val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)
            chunks += ChunkInfo(type, offset, dataLength)
            offset += 12 + dataLength
            if (type == "IEND") return chunks.takeIf { dataLength == 0 && offset == bytes.size }
        }
        return null
    }

    private fun validCrc(bytes: ByteArray, chunk: ChunkInfo): Boolean {
        val crc = CRC32()
        crc.update(bytes, chunk.start + 4, chunk.dataLength + 4)
        return crc.value == unsignedInt(bytes, chunk.start + 8 + chunk.dataLength)
    }

    private fun isSrgbChromaticities(data: ByteArray): Boolean {
        val srgb = longArrayOf(31_270, 32_900, 64_000, 33_000, 30_000, 60_000, 15_000, 6_000)
        return srgb.indices.all { index -> unsignedInt(data, index * 4) == srgb[index] }
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    private fun hasOnlyPixelChunks(bytes: ByteArray): Boolean {
        var offset = PNG_SIGNATURE.size
        var sawHeader = false
        var sawEnd = false

        while (offset + 12 <= bytes.size) {
            val length = ((bytes[offset].toLong() and 0xffL) shl 24) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
                (bytes[offset + 3].toLong() and 0xffL)
            if (length > Int.MAX_VALUE || offset.toLong() + 12L + length > bytes.size) return false

            val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)
            if (type !in SAFE_CHUNKS) return false
            if (type == "IHDR") {
                if (sawHeader || length != 13L) return false
                val bitDepth = bytes[offset + 16].toInt() and 0xff
                val colorType = bytes[offset + 17].toInt() and 0xff
                val supportedBitDepth = bitDepth == 8 || colorType == 3 && bitDepth in setOf(1, 2, 4)
                if (!supportedBitDepth || colorType !in SAFE_COLOR_TYPES) return false
                sawHeader = true
            } else if (!sawHeader) {
                return false
            }

            offset += 12 + length.toInt()
            if (type == "IEND") {
                sawEnd = true
                break
            }
        }

        return sawHeader && sawEnd && offset == bytes.size
    }
}
