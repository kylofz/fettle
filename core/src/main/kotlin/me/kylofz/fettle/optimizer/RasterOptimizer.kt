package me.kylofz.fettle.optimizer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal object RasterOptimizer {
    // shortcut: TGA assets above 64M pixels stay unchanged; raise after scan-time benchmarks on real packs.
    private const val MAX_PIXELS = 16_000_000L
    private const val MAX_TGA_SCAN_PIXELS = 64_000_000L
    private const val MAX_SOURCE_DIMENSION = 32_768

    fun resizeJpeg(bytes: ByteArray, maxDimension: Int): ByteArray {
        if (maxDimension <= 0) return bytes
        val input = try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            return bytes
        } ?: return bytes
        return try {
            input.use { imageInput ->
                val reader = ImageIO.getImageReaders(imageInput).asSequence().firstOrNull() ?: return bytes
                try {
                    reader.input = imageInput
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION ||
                        maxOf(width, height) <= maxDimension
                    ) return bytes

                    val pixels = width.toLong() * height.toLong()
                    val sample = if (pixels > MAX_PIXELS) sourceSubsampling(width, height, maxDimension) else 1
                    val parameters = reader.defaultReadParam
                    if (sample > 1) parameters.setSourceSubsampling(sample, sample, 0, 0)
                    val image = reader.read(0, parameters) ?: return bytes
                    if (image.width.toLong() * image.height.toLong() > MAX_PIXELS) return bytes

                    val resized = if (maxOf(image.width, image.height) > maxDimension) {
                        PngOptimizer.resizeNearest(image, maxDimension)
                    } else {
                        image
                    }
                    val rgb = BufferedImage(resized.width, resized.height, BufferedImage.TYPE_INT_RGB)
                    val graphics = rgb.createGraphics()
                    graphics.drawImage(resized, 0, 0, null)
                    graphics.dispose()

                    val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return bytes
                    val output = ByteArrayOutputStream()
                    try {
                        ImageIO.createImageOutputStream(output).use { imageOutput ->
                            writer.output = imageOutput
                            val writeParameters = writer.defaultWriteParam
                            if (writeParameters.canWriteCompressed()) {
                                writeParameters.compressionMode = ImageWriteParam.MODE_EXPLICIT
                                writeParameters.compressionQuality = 0.95f
                            }
                            writer.write(null, IIOImage(rgb, null, null), writeParameters)
                            imageOutput.flush()
                        }
                        output.toByteArray()
                    } catch (_: Exception) {
                        bytes
                    } finally {
                        writer.dispose()
                    }
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: Exception) {
            bytes
        }
    }

    private fun sourceSubsampling(width: Int, height: Int, maxDimension: Int): Int =
        (maxOf(width, height) / maxDimension).coerceAtLeast(1)

    fun resizeTga(bytes: ByteArray, maxDimension: Int): ByteArray {
        if (maxDimension <= 0) return bytes
        val decoded = decodeTga(bytes, maxDimension) ?: return bytes
        if (decoded.subsampled) return encodeTga(decoded.image) ?: bytes
        if (maxOf(decoded.image.width, decoded.image.height) <= maxDimension) return bytes
        return encodeTga(PngOptimizer.resizeNearest(decoded.image, maxDimension)) ?: bytes
    }

    private fun decodeTga(bytes: ByteArray, maxDimension: Int): DecodedTga? {
        if (bytes.size < 18) return null
        val input = ByteArrayInputStream(bytes)
        val header = input.readNBytes(18)
        val imageType = header[2].toInt() and 0xff
        val colorMapType = header[1].toInt() and 0xff
        val depth = header[16].toInt() and 0xff
        val descriptor = header[17].toInt() and 0xff
        val grayscale = imageType == 3 || imageType == 11
        val rle = imageType == 10 || imageType == 11
        // shortcut: indexed and interleaved TGA are kept unchanged; add fixtures before extending the decoder.
        if (colorMapType != 0 || imageType !in setOf(2, 3, 10, 11) ||
            (descriptor and 0xc0) != 0 || (grayscale && depth != 8) || (!grayscale && depth !in setOf(24, 32))
        ) return null

        val width = littleEndianShort(header, 12)
        val height = littleEndianShort(header, 14)
        val pixelCount = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return null
        val subsampled = pixelCount > MAX_PIXELS
        if (subsampled && (pixelCount > MAX_TGA_SCAN_PIXELS || maxOf(width, height) <= maxDimension)) return null

        val outputWidth: Int
        val outputHeight: Int
        if (subsampled) {
            val scale = maxDimension.toDouble() / maxOf(width, height)
            outputWidth = maxOf(1, (width * scale).toInt())
            outputHeight = maxOf(1, (height * scale).toInt())
        } else {
            outputWidth = width
            outputHeight = height
        }

        try {
            input.skipNBytes((header[0].toInt() and 0xff).toLong())
        } catch (_: Exception) {
            return null
        }

        val image = BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB)
        val topOrigin = (descriptor and 0x20) != 0
        val rightOrigin = (descriptor and 0x10) != 0
        val hasAlpha = (descriptor and 0x0f) > 0
        var pixelIndex = 0

        fun placePixel(color: Int) {
            val fileX = pixelIndex % width
            val fileY = pixelIndex / width
            val x = if (rightOrigin) width - fileX - 1 else fileX
            val y = if (topOrigin) fileY else height - fileY - 1
            val targetX = if (subsampled) (x.toLong() * outputWidth / width).toInt() else x
            val targetY = if (subsampled) (y.toLong() * outputHeight / height).toInt() else y
            val selectedX = if (subsampled) {
                (((targetX.toLong() * 2 + 1) * width) / (outputWidth.toLong() * 2)).toInt()
            } else x
            val selectedY = if (subsampled) {
                (((targetY.toLong() * 2 + 1) * height) / (outputHeight.toLong() * 2)).toInt()
            } else y
            if (x == selectedX && y == selectedY) image.setRGB(targetX, targetY, color)
            pixelIndex++
        }

        fun readPixel(): Int? {
            val first = input.read()
            if (first < 0) return null
            if (grayscale) return 0xff000000.toInt() or (first shl 16) or (first shl 8) or first
            val green = input.read()
            val red = input.read()
            if (green < 0 || red < 0) return null
            val alphaByte = if (depth == 32) input.read() else 0xff
            if (alphaByte < 0) return null
            val alpha = if (depth == 32 && hasAlpha) alphaByte else 0xff
            return (alpha shl 24) or (red shl 16) or (green shl 8) or first
        }

        while (pixelIndex < pixelCount) {
            if (!rle) {
                placePixel(readPixel() ?: return null)
                continue
            }
            val packet = input.read()
            if (packet < 0) return null
            val runLength = (packet and 0x7f) + 1
            if (pixelIndex + runLength > pixelCount) return null
            if (packet and 0x80 != 0) {
                val pixel = readPixel() ?: return null
                repeat(runLength) { placePixel(pixel) }
            } else {
                repeat(runLength) { placePixel(readPixel() ?: return null) }
            }
        }
        return DecodedTga(image, subsampled)
    }

    private fun encodeTga(image: BufferedImage): ByteArray? {
        if (image.width > 0xffff || image.height > 0xffff) return null
        val output = ByteArrayOutputStream(18 + image.width * image.height * 4)
        val header = ByteArray(18)
        header[2] = 2
        header[12] = image.width.toByte()
        header[13] = (image.width ushr 8).toByte()
        header[14] = image.height.toByte()
        header[15] = (image.height ushr 8).toByte()
        header[16] = 32
        header[17] = 0x28
        output.write(header)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = image.getRGB(x, y)
                output.write(color and 0xff)
                output.write(color ushr 8 and 0xff)
                output.write(color ushr 16 and 0xff)
                output.write(color ushr 24 and 0xff)
            }
        }
        return output.toByteArray()
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private data class DecodedTga(val image: BufferedImage, val subsampled: Boolean)
}
