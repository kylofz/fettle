package me.kylofz.fettle.optimizer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
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
        return add(source, x, y, gutter)
    }

    fun add(source: BufferedImage, x: Int, y: Int, gutter: Int): Boolean {
        if (gutter !in 0..1 || x < gutter || y < gutter ||
            x.toLong() + source.width + gutter > width || y.toLong() + source.height + gutter > height
        ) return false

        val row = IntArray(source.width)
        var topLeft = 0
        var topRight = 0
        var bottomLeft = 0
        var bottomRight = 0
        for (sourceY in 0 until source.height) {
            source.getRGB(0, sourceY, source.width, 1, row, 0, source.width)
            image.setRGB(x, y + sourceY, source.width, 1, row, 0, source.width)
            if (gutter == 1) {
                if (sourceY == 0) {
                    topLeft = row[0]
                    topRight = row[source.width - 1]
                    image.setRGB(x, y - 1, source.width, 1, row, 0, source.width)
                }
                if (sourceY == source.height - 1) {
                    bottomLeft = row[0]
                    bottomRight = row[source.width - 1]
                    image.setRGB(x, y + source.height, source.width, 1, row, 0, source.width)
                }
                image.setRGB(x - 1, y + sourceY, row[0])
                image.setRGB(x + source.width, y + sourceY, row[source.width - 1])
            }
        }
        if (gutter == 1) {
            image.setRGB(x - 1, y - 1, topLeft)
            image.setRGB(x + source.width, y - 1, topRight)
            image.setRGB(x - 1, y + source.height, bottomLeft)
            image.setRGB(x + source.width, y + source.height, bottomRight)
        }
        return true
    }

    fun encode(): ByteArray? = PngOptimizer.encodeLossless(image)

    private companion object {
        const val MAX_ATLAS_PIXELS = 4_194_304L
    }
}
