package me.kylofz.fettle.optimizer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal class PngAtlasBuilder(private val width: Int, private val height: Int) {
    private val image: BufferedImage

    init {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_ATLAS_PIXELS)
        image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    }

    fun add(bytes: ByteArray, x: Int, y: Int, gutter: Int): Boolean {
        val source = try {
            ImageIO.read(ByteArrayInputStream(bytes)) ?: return false
        } catch (_: Exception) {
            return false
        }
        if (gutter !in 0..1 || x < gutter || y < gutter ||
            x.toLong() + source.width + gutter > width || y.toLong() + source.height + gutter > height
        ) return false

        val row = IntArray(source.width)
        for (sourceY in 0 until source.height) {
            source.getRGB(0, sourceY, source.width, 1, row, 0, source.width)
            image.setRGB(x, y + sourceY, source.width, 1, row, 0, source.width)
        }
        if (gutter == 1) {
            source.getRGB(0, 0, source.width, 1, row, 0, source.width)
            image.setRGB(x, y - 1, source.width, 1, row, 0, source.width)
            source.getRGB(0, source.height - 1, source.width, 1, row, 0, source.width)
            image.setRGB(x, y + source.height, source.width, 1, row, 0, source.width)
            val column = IntArray(source.height)
            for (sourceX in 0 until source.width) {
                source.getRGB(sourceX, 0, 1, source.height, column, 0, 1)
                image.setRGB(x - 1, y, 1, source.height, column, 0, 1)
                source.getRGB(source.width - 1, 0, 1, source.height, column, 0, 1)
                image.setRGB(x + source.width, y, 1, source.height, column, 0, 1)
            }
            source.getRGB(0, 0, 1, 1, row, 0, 1)
            image.setRGB(x - 1, y - 1, row[0])
            source.getRGB(source.width - 1, 0, 1, 1, row, 0, 1)
            image.setRGB(x + source.width, y - 1, row[0])
            source.getRGB(0, source.height - 1, 1, 1, row, 0, 1)
            image.setRGB(x - 1, y + source.height, row[0])
            source.getRGB(source.width - 1, source.height - 1, 1, 1, row, 0, 1)
            image.setRGB(x + source.width, y + source.height, row[0])
        }
        return true
    }

    fun encode(): ByteArray? {
        val bytes = ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(image, "png", output)) return null
            output.toByteArray()
        }
        val decoded = try {
            ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        } catch (_: Exception) {
            return null
        }
        if (decoded.width != width || decoded.height != height) return null
        val expected = IntArray(width)
        val actual = IntArray(width)
        for (row in 0 until height) {
            image.getRGB(0, row, width, 1, expected, 0, width)
            decoded.getRGB(0, row, width, 1, actual, 0, width)
            if (!expected.contentEquals(actual)) return null
        }
        val optimized = PngOptimizer.optimizeIfSmaller(bytes)
        val optimizedImage = try {
            ImageIO.read(ByteArrayInputStream(optimized)) ?: return null
        } catch (_: Exception) {
            return null
        }
        if (optimizedImage.width != width || optimizedImage.height != height) return null
        for (row in 0 until height) {
            image.getRGB(0, row, width, 1, expected, 0, width)
            optimizedImage.getRGB(0, row, width, 1, actual, 0, width)
            if (!expected.contentEquals(actual)) return null
        }
        return optimized
    }

    private companion object {
        const val MAX_ATLAS_PIXELS = 4_194_304L
    }
}
