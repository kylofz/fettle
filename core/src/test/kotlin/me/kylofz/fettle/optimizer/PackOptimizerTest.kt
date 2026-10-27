package me.kylofz.fettle.optimizer

import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackOptimizerTest {
    @Test
    fun packSetRewriteParallelismRequiresCpuAndHeapHeadroom() {
        val heapLimit = 3L * 1024L * 1024L * 1024L

        assertEquals(2, packSetRewriteParallelism(6, true, 4, heapLimit))
        assertEquals(1, packSetRewriteParallelism(6, false, 4, heapLimit))
        assertEquals(1, packSetRewriteParallelism(6, true, 3, heapLimit))
        assertEquals(1, packSetRewriteParallelism(6, true, 4, heapLimit - 1))
        assertEquals(1, packSetRewriteParallelism(1, true, 4, heapLimit))
    }

    @Test
    fun decodedPixelHashMatchesEquivalentPngEncodings() {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            image.setRGB(x, y, when ((x + y) % 4) {
                0 -> 0x00000000
                1 -> 0x80ff0000.toInt()
                2 -> 0xff00ff00.toInt()
                else -> 0xff0000ff.toInt()
            })
        }
        val rgba = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val indexed = indexedPng(image)

        assertFalse(rgba.contentEquals(indexed))
        assertEquals(8, indexed[24].toInt() and 0xff)
        assertEquals(3, indexed[25].toInt() and 0xff)
        val rgbaHash = PngOptimizer.decodedPixelHash(rgba)
        assertTrue(rgbaHash != null && rgbaHash.contentEquals(PngOptimizer.decodedPixelHash(indexed)))
        assertTrue(PngOptimizer.sameDecodedPixels(rgba, indexed))
        assertNull(PngOptimizer.decodedPixelHash(pngWithSafeMetadata(image)))
    }

    @Test
    fun decodedPixelHashSupportsFourBitPalettePngs() {
        val rgba = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        rgba.setRGB(0, 0, 0x00000000)
        rgba.setRGB(1, 0, 0xffff0000.toInt())
        rgba.setRGB(0, 1, 0xffff0000.toInt())
        rgba.setRGB(1, 1, 0x00000000)
        val rgbaPng = ByteArrayOutputStream().also { ImageIO.write(rgba, "png", it) }.toByteArray()
        val palettePng = fourBitPalettePng()

        assertEquals(4, palettePng[24].toInt() and 0xff)
        val hash = PngOptimizer.decodedPixelHash(rgbaPng)
        assertTrue(hash != null && hash.contentEquals(PngOptimizer.decodedPixelHash(palettePng)))
        assertTrue(PngOptimizer.sameDecodedPixels(rgbaPng, palettePng))
    }

    @Test
    fun pngReencodingKeepsExactPixelsForHighColorOpaqueAndAlphaImages() {
        for (transparent in listOf(false, true)) {
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val alpha = if (transparent && (x + y) % 3 == 0) 0x80 else 0xff
                image.setRGB(x, y, (alpha shl 24) or (((x * 31) and 0xff) shl 16) or
                        (((y * 29) and 0xff) shl 8) or ((x * 17 + y * 13) and 0xff))
            }
            val source = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val optimized = PngOptimizer.optimizeIfSmaller(source)

            assertTrue(PngOptimizer.sameDecodedPixels(source, optimized), "transparent=$transparent")
        }
    }

    @Test
    fun losslessPaletteEncodingPreservesAllBytePaletteIndices() {
        val image = BufferedImage(256, 1, BufferedImage.TYPE_INT_ARGB)
        for (index in 0..255) image.setRGB(index, 0, 0xff000000.toInt() or index)
        val source = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        val encoded = PngOptimizer.encodeLossless(image)

        assertTrue(encoded != null)
        assertTrue(PngOptimizer.sameDecodedPixels(source, encoded))
    }

    @Test
    fun decodedCropPixelHashMatchesEquivalentPngEncodings() {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            image.setRGB(x, y, when ((x + y) % 4) {
                0 -> 0x00000000
                1 -> 0x80ff0000.toInt()
                2 -> 0xff00ff00.toInt()
                else -> 0xff0000ff.toInt()
            })
        }
        val rgba = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val indexed = indexedPng(image)
        val crop = TextureCrop(4, 6, 16, 12, image.width, image.height)
        val rgbaHash = PngOptimizer.decodedCropPixelHash(rgba, crop) ?: error("Could not hash RGBA crop")
        val indexedHash = PngOptimizer.decodedCropPixelHash(indexed, crop) ?: error("Could not hash indexed crop")
        val encodedCrop = PngOptimizer.cropRegion(rgba, crop) ?: error("Could not encode crop")
        val encodedHash = PngOptimizer.decodedPixelHash(encodedCrop) ?: error("Could not hash encoded crop")

        assertContentEquals(rgbaHash, indexedHash)
        assertContentEquals(rgbaHash, encodedHash)
        val rgbaCropImage = PngOptimizer.cropImage(rgba, crop) ?: error("Could not read RGBA crop")
        val croppedImage = PngOptimizer.cropImage(indexed, crop) ?: error("Could not read indexed crop")
        assertTrue(PngOptimizer.samePixels(rgbaCropImage, croppedImage))
        assertEquals(crop.width, croppedImage.width)
        assertEquals(crop.height, croppedImage.height)
        for (y in 0 until crop.height) for (x in 0 until crop.width) {
            assertEquals(image.getRGB(crop.x + x, crop.y + y), croppedImage.getRGB(x, y))
        }
    }

    @Test
    fun losslessRewriteCropsFourBitPaletteTextureWithExactPixels() {
        withTempDirectory { root ->
            val input = root.resolve("four-bit.mcpack")
            val output = root.resolve("four-bit-optimized.mcpack")
            val png = fourBitPalettePng(32, 32)
            val geometry = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"geometry.scf.four_bit","texture_width":32,"texture_height":32},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[10,10],"uv_size":[4,4]}}}]}]}]}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/four-bit.json" to """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.four_bit"},"textures":{"default":"textures/entity/four_bit"}}}}""".toByteArray(),
                "models/entity/four-bit.geo.json" to geometry.toByteArray(),
                "textures/entity/four_bit.png" to png,
            ))

            val report = PackOptimizer().optimizeForLowEnd(input, output)

            assertEquals(1, report.pngEntriesOptimized)
            val source = ImageIO.read(png.inputStream())
            ZipFile(output.toFile()).use { zip ->
                val cropped = ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/four_bit.png")))
                assertEquals(12, cropped.width)
                assertEquals(12, cropped.height)
                val expected = IntArray(12 * 12)
                val actual = IntArray(12 * 12)
                source.getRGB(6, 6, 12, 12, expected, 0, 12)
                cropped.getRGB(0, 0, 12, 12, actual, 0, 12)
                assertContentEquals(expected, actual)
            }
        }
    }

    @Test
    fun losslessAtlasPreservesVariantPixelsAndReducesTextureHandles() {
        withTempDirectory { root ->
            val input = root.resolve("variants.mcpack")
            val output = root.resolve("variants-atlas.mcpack")
            fun png(seed: Int): ByteArray {
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    image.setRGB(x, y, Color(seed + x, seed + y, seed + x + y).rgb)
                }
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            val textures = mapOf(
                "textures/entity/a0.png" to png(10),
                "textures/entity/a1.png" to png(40),
                "textures/entity/b0.png" to png(70),
                "textures/entity/b1.png" to png(100),
            )
            fun geometry(identifier: String) =
                """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":32,"texture_height":32},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[4,4]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) =
                """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            val entries = linkedMapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/a.geo.json" to geometry("geometry.scf.a").toByteArray(),
                "models/entity/b.geo.json" to geometry("geometry.scf.b").toByteArray(),
                "attachables/a0.json" to attachable("geometry.scf.a", "textures/entity/a0").toByteArray(),
                "attachables/a1.json" to attachable("geometry.scf.a", "textures/entity/a1").toByteArray(),
                "attachables/b0.json" to attachable("geometry.scf.b", "textures/entity/b0").toByteArray(),
                "attachables/b1.json" to attachable("geometry.scf.b", "textures/entity/b1").toByteArray(),
            ) + textures
            writePack(input, entries)

            PackOptimizer().optimizeForLowEnd(input, output)

            val sourceAudit = PackAuditor().audit(input)
            val outputAudit = PackAuditor().audit(output)
            assertEquals(4, sourceAudit.estimatedTextureHandleCount)
            assertEquals(2, outputAudit.estimatedTextureHandleCount)
            assertTrue(outputAudit.textureMemoryBytes < sourceAudit.textureMemoryBytes)
            ZipFile(output.toFile()).use { zip ->
                val atlasPaths = zip.entries().asSequence().map { it.name }
                    .filter { it.startsWith("textures/entity/fettle/atlases/") }.toList()
                assertEquals(2, atlasPaths.size)
                assertTrue(atlasPaths.all { it.length < 80 })
                for ((model, attachable, originalTexture) in listOf(
                    Triple("models/entity/a.geo.json", "attachables/a0.json", "textures/entity/a0.png"),
                    Triple("models/entity/a.geo.json", "attachables/a1.json", "textures/entity/a1.png"),
                    Triple("models/entity/b.geo.json", "attachables/b0.json", "textures/entity/b0.png"),
                    Triple("models/entity/b.geo.json", "attachables/b1.json", "textures/entity/b1.png"),
                )) {
                    val sourceImage = ImageIO.read(ByteArrayInputStream(textures.getValue(originalTexture)))
                    val attachmentText = zip.getInputStream(zip.getEntry(attachable)).readBytes().decodeToString()
                    val atlasStem = Regex("""textures/entity/fettle/atlases/[^"]+""")
                        .find(attachmentText)?.value ?: error("Atlas path missing from $attachable")
                    val atlasPath = if (atlasStem.endsWith(".png")) atlasStem else "$atlasStem.png"
                    val geometryText = zip.getInputStream(zip.getEntry(model)).readBytes().decodeToString()
                    val uv = Regex(""""uv":\[(\d+),(\d+)\]""")
                        .find(geometryText) ?: error("UV missing from $model")
                    val u = uv.groupValues[1].toInt()
                    val v = uv.groupValues[2].toInt()
                    val atlas = ImageIO.read(zip.getInputStream(zip.getEntry(atlasPath)))
                    assertEquals(sourceImage.getRGB(8, 8), atlas.getRGB(u, v))
                    assertEquals(null, zip.getEntry(originalTexture))
                }
            }
        }
    }

    @Test
    fun splitAtlasHandlesUnusedFallbackAliasesButRejectsExternalTextureReferences() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val animation = root.resolve("animation.mcpack")
            val baseOutput = root.resolve("base-output.mcpack")
            val animationOutput = root.resolve("animation-output.mcpack")
            val sourceTextures = LinkedHashMap<String, ByteArray>()
            fun png(seed: Int): ByteArray {
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    image.setRGB(x, y, Color(seed + x, seed + y, seed + x + y).rgb)
                }
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            fun texture(path: String, seed: Int) {
                sourceTextures[path] = png(seed)
            }
            fun geometry(identifier: String) =
                """{"minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":32,"texture_height":32},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[4,4]}}}]}]}]}"""
            fun staticAttachable(identifier: String, texture: String) =
                """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            fun animatedAttachable(identifier: String, fallback: String, frame0: String, frame1: String) =
                """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"$fallback","frame_0":"$frame0","frame_1":"$frame1"},"render_controllers":["controller.render.frames"]}}}"""

            val baseEntries = linkedMapOf("manifest.json" to "{}".toByteArray())
            val animationEntries = linkedMapOf(
                "manifest.json" to "{}".toByteArray(),
                "render_controllers/frames.json" to
                    """{"render_controllers":{"controller.render.frames":{"arrays":{"textures":{"Array.frames":["Texture.frame_0","Texture.frame_1"]}},"geometry":"Geometry.default","textures":["Array.frames[query.variant]"]}}}""".toByteArray(),
                "other/reference.json" to """{"texture":"textures/entity/frame-c-0"}""".toByteArray(),
            )
            for ((index, id) in listOf("a", "b", "c").withIndex()) {
                val geometryId = "geometry.scf.$id"
                baseEntries["models/entity/$id.geo.json"] = geometry(geometryId).toByteArray()
                baseEntries["attachables/static-$id.json"] =
                    staticAttachable(geometryId, "textures/entity/static-$id").toByteArray()
                texture("textures/entity/static-$id.png", index * 30 + 10)
                texture("textures/entity/frame-$id-0.png", index * 30 + 15)
                texture("textures/entity/frame-$id-1.png", index * 30 + 20)
                animationEntries["attachables/animated-$id.json"] = animatedAttachable(
                    geometryId,
                    "textures/entity/static-$id",
                    "textures/entity/frame-$id-0",
                    "textures/entity/frame-$id-1",
                ).toByteArray()
            }
            baseEntries.putAll(sourceTextures.filterKeys { it.contains("static-") })
            animationEntries.putAll(sourceTextures.filterKeys { !it.contains("static-") })
            writePack(base, baseEntries)
            writePack(animation, animationEntries)

            PackOptimizer().optimizeForLowEndSet(listOf(base, animation), listOf(baseOutput, animationOutput))

            val atlases = ZipFile(baseOutput.toFile()).use { zip ->
                zip.entries().asSequence().map { it.name }
                    .filter { it.startsWith("textures/entity/fettle/atlases/") }.toList()
            }
            assertEquals(3, atlases.size)
            ZipFile(baseOutput.toFile()).use { zip ->
                assertNull(zip.getEntry("textures/entity/static-a.png"))
                assertNull(zip.getEntry("textures/entity/static-b.png"))
                assertTrue(
                    zip.getEntry("textures/entity/static-c.png") != null,
                    "entries=${zip.entries().asSequence().map { it.name }.filter { it.contains("static-c") || it.contains("frame-c") }.toList()}, " +
                        "attachment=${zip.getInputStream(zip.getEntry("attachables/static-c.json")).use { it.readBytes().decodeToString()}}",
                )
                val geometryC = zip.getInputStream(zip.getEntry("models/entity/c.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(geometryC.contains("\"texture_width\":32"))
            }
            ZipFile(animationOutput.toFile()).use { zip ->
                for (id in listOf("a", "b")) {
                    for (alias in listOf("frame_0", "frame_1")) {
                        val texture = "textures/entity/frame-$id-${alias.last()}.png"
                        assertNull(zip.getEntry(texture))
                    }
                }
                assertTrue(zip.getEntry("textures/entity/frame-c-0.png") != null)
                assertTrue(zip.getEntry("textures/entity/frame-c-1.png") != null)
                val external = zip.getInputStream(zip.getEntry("other/reference.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(external.contains("textures/entity/frame-c-0"))
                val controller = zip.getInputStream(zip.getEntry("render_controllers/frames.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(controller.contains("Array.frames[query.variant]"))
            }

            ZipFile(baseOutput.toFile()).use { baseZip ->
                ZipFile(animationOutput.toFile()).use { animationZip ->
                    for (id in listOf("a", "b")) {
                        val geometry = baseZip.getInputStream(baseZip.getEntry("models/entity/$id.geo.json"))
                            .use { it.readBytes().decodeToString() }
                        val uv = Regex(""""uv":\[(\d+),(\d+)\]""")
                            .find(geometry) ?: error("Atlas UV missing for geometry $id")
                        val (u, v) = uv.groupValues.drop(1).map(String::toInt)
                        fun assertAtlasPixels(
                            attachableZip: ZipFile,
                            atlasZip: ZipFile,
                            attachablePath: String,
                            alias: String,
                            sourcePath: String,
                        ) {
                            val attachment = attachableZip.getInputStream(attachableZip.getEntry(attachablePath))
                                .use { it.readBytes().decodeToString() }
                            val atlasStem = Regex(""""$alias":"(textures/entity/fettle/atlases/[^\"]+)"""")
                                .find(attachment)?.groupValues?.get(1)
                                ?: error("Atlas mapping missing for $id/$alias")
                            val atlasPath = if (atlasStem.endsWith(".png")) atlasStem else "$atlasStem.png"
                            val sourceImage = ImageIO.read(ByteArrayInputStream(sourceTextures.getValue(sourcePath)))
                            val atlas = ImageIO.read(atlasZip.getInputStream(atlasZip.getEntry(atlasPath)))
                            assertEquals(sourceImage.getRGB(8, 8), atlas.getRGB(u, v), "$id/$alias pixels changed")
                        }
                        assertAtlasPixels(baseZip, baseZip, "attachables/static-$id.json", "default", "textures/entity/static-$id.png")
                        assertAtlasPixels(animationZip, baseZip, "attachables/animated-$id.json", "default", "textures/entity/static-$id.png")
                        assertAtlasPixels(animationZip, baseZip, "attachables/animated-$id.json", "frame_0", "textures/entity/frame-$id-0.png")
                        assertAtlasPixels(animationZip, baseZip, "attachables/animated-$id.json", "frame_1", "textures/entity/frame-$id-1.png")
                    }
                }
            }
        }
    }

    @Test
    fun losslessAtlasUsesPerGeometryUvScaleForMixedDimensionComponents() {
        withTempDirectory { root ->
            val input = root.resolve("mixed-geometry-dimensions.mcpack")
            val output = root.resolve("mixed-geometry-dimensions-atlas.mcpack")
            fun png(seed: Int): ByteArray {
                val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    image.setRGB(x, y, Color(seed + x, seed + y, seed + x + y).rgb)
                }
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            val textures = linkedMapOf(
                "textures/entity/ab0.png" to png(10),
                "textures/entity/ab1.png" to png(40),
                "textures/entity/cd0.png" to png(70),
                "textures/entity/cd1.png" to png(100),
            )
            fun geometry(identifier: String, dimension: Int, u: Int, uvSize: Int) =
                """{"minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":$dimension,"texture_height":$dimension},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[$u,$u],"uv_size":[$uvSize,$uvSize]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) =
                """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""

            val entries = linkedMapOf("manifest.json" to "{}".toByteArray())
            data class GeometrySpec(val id: String, val dimension: Int, val uv: Int, val uvSize: Int)
            for (spec in listOf(
                GeometrySpec("a", 32, 4, 2),
                GeometrySpec("b", 64, 8, 4),
                GeometrySpec("c", 32, 4, 2),
                GeometrySpec("d", 64, 8, 4),
            )) {
                val id = spec.id
                val model = "models/entity/$id.geo.json"
                val identifier = "geometry.scf.$id"
                entries[model] = geometry(identifier, spec.dimension, spec.uv, spec.uvSize).toByteArray()
                val texturesForModel = if (id == "a" || id == "b") listOf("ab0", "ab1") else listOf("cd0", "cd1")
                for (texture in texturesForModel) {
                    entries["attachables/$id-$texture.json"] =
                        attachable(identifier, "textures/entity/$texture").toByteArray()
                }
            }
            entries.putAll(textures)
            writePack(input, entries)

            PackOptimizer().optimizeForLowEnd(input, output)

            assertEquals(4, PackAuditor().audit(input).estimatedTextureHandleCount)
            assertEquals(2, PackAuditor().audit(output).estimatedTextureHandleCount)
            ZipFile(output.toFile()).use { zip ->
                assertEquals(2, zip.entries().asSequence().count {
                    it.name.startsWith("textures/entity/fettle/atlases/") && it.name.endsWith(".png")
                })
                for (id in listOf("a", "b", "c", "d")) {
                    val geometry = zip.getInputStream(zip.getEntry("models/entity/$id.geo.json"))
                        .use { it.readBytes().decodeToString() }
                    val uv = Regex(""""uv":\[(\d+),(\d+)\]""")
                        .find(geometry) ?: error("Atlas UV missing for geometry $id")
                    val (u, v) = uv.groupValues.drop(1).map(String::toInt)
                    val texturePaths = if (id == "a" || id == "b") listOf("ab0", "ab1") else listOf("cd0", "cd1")
                    for (texture in texturePaths) {
                        val attachablePath = "attachables/$id-$texture.json"
                        val attachable = zip.getInputStream(zip.getEntry(attachablePath))
                            .use { it.readBytes().decodeToString() }
                        val atlasStem = Regex(""""default":"(textures/entity/fettle/atlases/[^\"]+)"""")
                            .find(attachable)?.groupValues?.get(1) ?: error("Atlas mapping missing for $id/$texture")
                        val atlasPath = if (atlasStem.endsWith(".png")) atlasStem else "$atlasStem.png"
                        val source = ImageIO.read(ByteArrayInputStream(textures.getValue("textures/entity/$texture.png")))
                        val atlas = ImageIO.read(zip.getInputStream(zip.getEntry(atlasPath)))
                        assertEquals(source.getRGB(8, 8), atlas.getRGB(u, v), "$id/$texture pixels changed")
                    }
                }
                for (texture in textures.keys) assertNull(zip.getEntry(texture))
            }
        }
    }

    @Test
    fun pngAtlasAddsExtrudedBorderWithoutChangingSourcePixels() {
        val source = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, Color.RED.rgb)
            setRGB(1, 0, Color.GREEN.rgb)
            setRGB(0, 1, Color.BLUE.rgb)
            setRGB(1, 1, Color.WHITE.rgb)
        }
        val sourcePng = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
        val atlasBuilder = PngAtlasBuilder(4, 4)

        assertTrue(atlasBuilder.add(sourcePng, 1, 1, 1))
        val atlas = ImageIO.read(ByteArrayInputStream(atlasBuilder.encode() ?: error("Atlas encoding failed")))

        assertEquals(source.getRGB(0, 0), atlas.getRGB(1, 1))
        assertEquals(source.getRGB(1, 0), atlas.getRGB(2, 1))
        assertEquals(source.getRGB(0, 1), atlas.getRGB(1, 2))
        assertEquals(source.getRGB(1, 1), atlas.getRGB(2, 2))
        assertEquals(Color.RED.rgb, atlas.getRGB(1, 0))
        assertEquals(Color.GREEN.rgb, atlas.getRGB(2, 0))
        assertEquals(Color.BLUE.rgb, atlas.getRGB(1, 3))
        assertEquals(Color.WHITE.rgb, atlas.getRGB(2, 3))
        assertEquals(Color.RED.rgb, atlas.getRGB(0, 1))
        assertEquals(Color.BLUE.rgb, atlas.getRGB(0, 2))
        assertEquals(Color.GREEN.rgb, atlas.getRGB(3, 1))
        assertEquals(Color.WHITE.rgb, atlas.getRGB(3, 2))
        assertEquals(Color.RED.rgb, atlas.getRGB(0, 0))
        assertEquals(Color.GREEN.rgb, atlas.getRGB(3, 0))
        assertEquals(Color.BLUE.rgb, atlas.getRGB(0, 3))
        assertEquals(Color.WHITE.rgb, atlas.getRGB(3, 3))
    }

    @Test
    fun pngAtlasCopiesIndexedTransparentPixelsAndTheirGuttersExactly() {
        val source = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0x00000000)
            setRGB(1, 0, 0x80ff0000.toInt())
            setRGB(0, 1, 0xff00ff00.toInt())
            setRGB(1, 1, 0xff0000ff.toInt())
        }
        val indexed = indexedPng(source)
        val decodedSource = ImageIO.read(ByteArrayInputStream(indexed))
        val atlasBuilder = PngAtlasBuilder(4, 4)

        assertEquals(3, indexed[25].toInt() and 0xff)
        assertTrue(atlasBuilder.add(indexed, 1, 1, 1))
        val atlas = ImageIO.read(ByteArrayInputStream(atlasBuilder.encode() ?: error("Atlas encoding failed")))

        for (y in 0..3) for (x in 0..3) {
            val sourceX = (x - 1).coerceIn(0, 1)
            val sourceY = (y - 1).coerceIn(0, 1)
            assertEquals(decodedSource.getRGB(sourceX, sourceY), atlas.getRGB(x, y), "pixel $x,$y changed")
        }
    }

    @Test
    fun atlasStillTransformsGeometryWhenItsSourceTextureWasAlreadyUvTight() {
        withTempDirectory { root ->
            val input = root.resolve("atlas-tight-uv.mcpack")
            val output = root.resolve("atlas-tight-uv-optimized.mcpack")
            fun png(width: Int, height: Int, seed: Int): ByteArray {
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until height) for (x in 0 until width) {
                    image.setRGB(x, y, Color(seed + x, seed + y, seed + x + y).rgb)
                }
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            val full = png(16, 16, 10)
            val cropped = png(32, 32, 80)
            fun geometry(identifier: String, dimension: Int, uv: String) =
                """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":$dimension,"texture_height":$dimension},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":$uv}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) =
                """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/full.json" to attachable("geometry.scf.full", "textures/entity/full").toByteArray(),
                "attachables/cropped.json" to attachable("geometry.scf.cropped", "textures/entity/cropped").toByteArray(),
                "models/entity/full.geo.json" to geometry("geometry.scf.full", 16, "[0,0],\"uv_size\":[16,16]").toByteArray(),
                "models/entity/cropped.geo.json" to geometry("geometry.scf.cropped", 32, "[8,8],\"uv_size\":[4,4]").toByteArray(),
                "textures/entity/full.png" to full,
                "textures/entity/cropped.png" to cropped,
            ))

            PackOptimizer().optimizeForLowEnd(input, output)
            val firstAtlasPath = ZipFile(output.toFile()).use { zip ->
                Regex("""textures/entity/fettle/atlases/[^"]+""")
                    .find(zip.getInputStream(zip.getEntry("attachables/full.json"))
                        .use { it.readBytes().decodeToString() })?.value ?: error("Atlas path missing")
            }

            ZipFile(output.toFile()).use { zip ->
                val atlas = ImageIO.read(zip.getInputStream(zip.getEntry("$firstAtlasPath.png")))
                assertEquals(32, atlas.width)
                assertEquals(18, atlas.height)
                val fullGeometry = zip.getInputStream(zip.getEntry("models/entity/full.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(fullGeometry.contains("\"texture_width\":32"))
                assertTrue(fullGeometry.contains("\"texture_height\":18"))
                assertTrue(fullGeometry.contains("\"uv\":[1,1]"))
                val croppedGeometry = zip.getInputStream(zip.getEntry("models/entity/cropped.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(croppedGeometry.contains("\"uv\":[23,5]"))
                assertEquals(ImageIO.read(ByteArrayInputStream(full)).getRGB(0, 0), atlas.getRGB(1, 1))
                assertEquals(ImageIO.read(ByteArrayInputStream(cropped)).getRGB(8, 8), atlas.getRGB(23, 5))
            }

            val protectedOutput = root.resolve("atlas-fixed-path-collision.mcpack")
            PackOptimizer().optimizeForLowEndSet(
                listOf(input), listOf(protectedOutput), 0, emptyList(), setOf("$firstAtlasPath.png"),
            )
            ZipFile(protectedOutput.toFile()).use { zip ->
                val attachable = zip.getInputStream(zip.getEntry("attachables/full.json"))
                    .use { it.readBytes().decodeToString() }
                val rewrittenAtlas = Regex("""textures/entity/fettle/atlases/[^"]+""")
                    .find(attachable)?.value ?: error("Protected atlas path missing")
                assertTrue(rewrittenAtlas != firstAtlasPath)
                assertTrue(zip.getEntry("$rewrittenAtlas.png") != null)
            }
        }
    }

    @Test
    fun keepsPackContentAndCompactsJson() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val binary = byteArrayOf(0, 1, 2, 3, -1)
            writePack(input, mapOf(
                "manifest.json" to "{\n  \"format_version\": 2, \"header\": { \"name\": \"demo\" }\n}".toByteArray(),
                "models/demo.json" to "{ \"bones\": [ { \"name\": \"root\" } ] }".toByteArray(),
                "textures/demo.png" to binary,
            ))

            val report = PackOptimizer().optimize(input, output)

            assertEquals(3, report.entriesRead)
            assertEquals(2, report.jsonEntriesMinified)
            assertTrue(report.outputBytes < report.inputBytes)
            ZipFile(output.toFile()).use { zip ->
                assertEquals("{\"format_version\":2,\"header\":{\"name\":\"demo\"}}",
                    zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString())
                assertContentEquals(binary, zip.getInputStream(zip.getEntry("textures/demo.png")).readBytes())
            }
        }
    }

    @Test
    fun filteredRewriteRetainsManifestAndRequestedFiles() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{\"old\":true}".toByteArray(),
                "font/demo.json" to "{ \"glyph\": 1 }".toByteArray(),
                "models/demo.json" to "{ \"model\": 1 }".toByteArray(),
            ))

            val report = PackOptimizer().optimizeKeeping(
                input,
                output,
                listOf("font/"),
                "{ \"new\": true }",
            )

            assertEquals(1, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("manifest.json") != null)
                assertTrue(zip.getEntry("font/demo.json") != null)
                assertTrue(zip.getEntry("models/demo.json") == null)
                assertEquals("{\"new\":true}",
                    zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString())
            }
        }
    }

    @Test
    fun packSetRewriteChangesOnlyTheRootHeaderUuid() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val sourceUuid = "2b2de2dd-aea9-4f60-92d4-1a99622b1c8f"
            val moduleUuid = "5a9a160a-d25e-4e26-9d99-2952d387f7a6"
            val replacementUuid = UUID.fromString("3c3de3ee-beb9-5f70-a3e5-2b99622b1c9f")
            val manifest = """{"format_version":2,"header":{"name":"demo","uuid":"$sourceUuid","version":[1,0,0]},"modules":[{"uuid":"$moduleUuid"}]}"""
            val texture = byteArrayOf(1, 2, 3, 4)
            writePack(input, mapOf("manifest.json" to manifest.toByteArray(), "textures/demo.png" to texture))

            PackOptimizer().optimizeResizedPackSet(listOf(input), listOf(output), 0, listOf(replacementUuid))

            ZipFile(output.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString()
                assertTrue(rewritten.contains("\"uuid\":\"$replacementUuid\""))
                assertTrue(rewritten.contains("\"uuid\":\"$moduleUuid\""))
                assertContentEquals(texture, zip.getInputStream(zip.getEntry("textures/demo.png")).readBytes())
            }
        }
    }

    @Test
    fun packSetRewriteRejectsAmbiguousHeaderUuid() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{\"header\":{\"uuid\":\"2b2de2dd-aea9-4f60-92d4-1a99622b1c8f\",\"uuid\":\"3c3de3ee-beb9-5f70-a3e5-2b99622b1c9f\"}}".toByteArray(),
            ))

            assertFailsWith<IOException> {
                PackOptimizer().optimizeResizedPackSet(
                    listOf(input), listOf(output), 0,
                    listOf(UUID.fromString("4d4de4ff-cf09-6f80-b4f6-3c99622b1d0f")),
                )
            }
            assertFalse(Files.exists(output))
        }
    }

    @Test
    fun losslessRewritePrunesOnlyUnreferencedGeneratedAssets() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/tool.json" to """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.used"},"textures":{"default":"textures/stitched/shared"}}}}""".toByteArray(),
                "models/entity/used.geo.json" to """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.used"}}]}""".toByteArray(),
                "models/entity/orphan.geo.json" to """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.orphan"}}]}""".toByteArray(),
                "models/entity/no-identifier.geo.json" to """{"minecraft:geometry":[{"description":{"texture_width":16}}]}""".toByteArray(),
                "textures/stitched/shared.png" to byteArrayOf(1),
                "textures/stitched/orphan.png" to byteArrayOf(2),
                "textures/items/icon.png" to byteArrayOf(3),
            ))

            val report = PackOptimizer().optimize(input, output)

            assertEquals(2, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("models/entity/used.geo.json") != null)
                assertTrue(zip.getEntry("models/entity/no-identifier.geo.json") != null)
                assertTrue(zip.getEntry("models/entity/orphan.geo.json") == null)
                assertTrue(zip.getEntry("textures/stitched/shared.png") != null)
                assertTrue(zip.getEntry("textures/stitched/orphan.png") == null)
                assertTrue(zip.getEntry("textures/items/icon.png") != null)
            }
        }
    }

    @Test
    fun losslessRewritePrunesUnreferencedRootStitchedTexturesAndTheirMetadata() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/tool.json" to """{"minecraft:attachable":{"description":{"textures":{"default":"textures/axe_stitched"}}}}""".toByteArray(),
                "textures/axe_stitched.png" to byteArrayOf(1),
                "textures/bow_stitched.png" to byteArrayOf(2),
                "textures/bow_stitched.png.mcmeta" to """{"animation":{"frametime":1}}""".toByteArray(),
                "textures/plain.png" to byteArrayOf(3),
            ))

            val report = PackOptimizer().optimize(input, output)

            assertEquals(2, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/axe_stitched.png") != null)
                assertTrue(zip.getEntry("textures/bow_stitched.png") == null)
                assertTrue(zip.getEntry("textures/bow_stitched.png.mcmeta") == null)
                assertTrue(zip.getEntry("textures/plain.png") != null)
            }
        }
    }

    @Test
    fun materialAssetReferencesDisableUnprovenPruning() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/material-only.geo.json" to """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.material_only"}}]}""".toByteArray(),
                "materials/model.material" to """{"geometry":"geometry.scf.material_only","texture":"textures/stitched/material_only"}""".toByteArray(),
                "textures/stitched/material_only.png" to byteArrayOf(1, 2, 3),
            ))

            val report = PackOptimizer().optimize(input, output)

            assertEquals(0, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("models/entity/material-only.geo.json") != null)
                assertTrue(zip.getEntry("textures/stitched/material_only.png") != null)
            }
        }
    }

    @Test
    fun losslessRewriteDeduplicatesExactItemAtlasImagesAndRemapsIds() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            for (y in 2 until 14) for (x in 3 until 13) image.setRGB(x, y, 0xFF4A72C8.toInt())
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/item_texture.json" to """{"texture_data":{"demo:first":{"textures":"textures/items/java_rendered/first"},"demo:second":{"textures":"textures/items/java_rendered/second"}}}""".toByteArray(),
                "textures/items/java_rendered/first.png" to png,
                "textures/items/java_rendered/second.png" to png,
                "attachables/third.json" to """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.third"},"textures":{"default":"textures/items/java_rendered/third"}}}}""".toByteArray(),
                "models/entity/third.geo.json" to """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.third"}}]}""".toByteArray(),
                "textures/items/java_rendered/third.png" to png,
            ))

            val report = PackOptimizer().optimize(input, output)

            assertEquals(1, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/items/java_rendered/first.png") != null)
                assertTrue(zip.getEntry("textures/items/java_rendered/second.png") == null)
                assertTrue(zip.getEntry("textures/items/java_rendered/third.png") != null)
                val itemTexture = zip.getInputStream(zip.getEntry("textures/item_texture.json")).use { it.readBytes().decodeToString() }
                assertTrue(itemTexture.contains("\"demo:first\":{\"textures\":\"textures/items/java_rendered/first\"}"))
                assertTrue(itemTexture.contains("\"demo:second\":{\"textures\":\"textures/items/java_rendered/first\"}"))
            }
        }
    }

    @Test
    fun losslessRewriteDeduplicatesExactModelTexturesBeforeSharedUvCropping() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 128) for (x in 0 until 128) {
                image.setRGB(x, y, if ((x + y) % 2 == 0) 0xff34aabb.toInt() else 0xffbd7331.toInt())
            }
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            fun geometry(identifier: String, uvX: Int) = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[$uvX,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) = """{"minecraft:attachable":{"description":{"identifier":"$identifier","geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/first.json" to attachable("geometry.scf.first", "textures/modelengine/first").toByteArray(),
                "attachables/second.json" to attachable("geometry.scf.second", "textures/modelengine/second").toByteArray(),
                "models/entity/first.geo.json" to geometry("geometry.scf.first", 8).toByteArray(),
                "models/entity/second.geo.json" to geometry("geometry.scf.second", 16).toByteArray(),
                "textures/modelengine/first.png" to png,
                "textures/modelengine/second.png" to png,
            ))

            val report = PackOptimizer().optimizeForLowEnd(input, output)

            assertEquals(1, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/modelengine/first.png") != null)
                assertNull(zip.getEntry("textures/modelengine/second.png"))
                val rewritten = zip.getInputStream(zip.getEntry("attachables/second.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("textures/modelengine/first"))
                for (model in listOf("first", "second")) {
                    val geometryText = zip.getInputStream(zip.getEntry("models/entity/$model.geo.json"))
                        .use { it.readBytes().decodeToString() }
                    assertTrue(geometryText.contains("\"texture_width\":40"))
                    assertTrue(geometryText.contains("\"texture_height\":32"))
                }
                val cropped = ImageIO.read(zip.getInputStream(zip.getEntry("textures/modelengine/first.png")))
                assertEquals(40, cropped.width)
                assertEquals(32, cropped.height)
                val expected = IntArray(40 * 32)
                val actual = IntArray(40 * 32)
                image.getRGB(4, 4, 40, 32, expected, 0, 40)
                cropped.getRGB(0, 0, 40, 32, actual, 0, 40)
                assertContentEquals(expected, actual)
            }
            val audit = PackAuditor().audit(output)
            assertEquals(1, audit.textureFileCount)
            assertEquals(1, audit.estimatedTextureHandleCount)
            assertEquals(40L * 32L * 4L, audit.textureMemoryBytes)
        }
    }

    @Test
    fun losslessRewriteAtlasesTexturesThatBecomeIdenticalAfterUvCropping() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val first = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            val second = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 128) for (x in 0 until 128) {
                val pixel = if ((x + y) % 2 == 0) 0xff34aabb.toInt() else 0xffbd7331.toInt()
                first.setRGB(x, y, if (x < 40 && y < 40) pixel else Color.RED.rgb)
                second.setRGB(x, y, if (x < 40 && y < 40) pixel else Color.BLUE.rgb)
            }
            val firstPng = ByteArrayOutputStream().also { ImageIO.write(first, "png", it) }.toByteArray()
            val secondPng = ByteArrayOutputStream().also { ImageIO.write(second, "png", it) }.toByteArray()
            assertFalse(firstPng.contentEquals(secondPng))
            fun geometry(identifier: String) =
                """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) =
                """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/first.json" to attachable("geometry.scf.first", "textures/modelengine/first").toByteArray(),
                "attachables/second.json" to attachable("geometry.scf.second", "textures/modelengine/second").toByteArray(),
                "models/entity/first.geo.json" to geometry("geometry.scf.first").toByteArray(),
                "models/entity/second.geo.json" to geometry("geometry.scf.second").toByteArray(),
                "textures/modelengine/first.png" to firstPng,
                "textures/modelengine/second.png" to secondPng,
            ))

            val report = PackOptimizer().optimizeForLowEnd(input, output)

            assertEquals(2, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertNull(zip.getEntry("textures/modelengine/first.png"))
                assertNull(zip.getEntry("textures/modelengine/second.png"))
                val secondAttachable = zip.getInputStream(zip.getEntry("attachables/second.json"))
                    .use { it.readBytes().decodeToString() }
                val atlasPath = Regex("""textures/entity/fettle/atlases/[^"]+""")
                    .find(secondAttachable)?.value ?: error("Atlas path missing")
                val atlas = ImageIO.read(zip.getInputStream(zip.getEntry("$atlasPath.png")))
                assertEquals(first.getRGB(8, 8), atlas.getRGB(5, 5))
                for (model in listOf("first", "second")) {
                    val modelJson = zip.getInputStream(zip.getEntry("models/entity/$model.geo.json"))
                        .use { it.readBytes().decodeToString() }
                    assertTrue(modelJson.contains("\"texture_width\":34"))
                    assertTrue(modelJson.contains("\"texture_height\":34"))
                }
            }
            assertEquals(34L * 34L * 4L, PackAuditor().audit(output).textureMemoryBytes)
        }
    }

    @Test
    fun losslessRewriteMergesIdenticalEntityTextureMapPathsWithoutGeometryAliasMatch() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                image.setRGB(x, y, if ((x + y) % 2 == 0) Color.RED.rgb else Color.BLUE.rgb)
            }
            val firstPng = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val secondPng = indexedPng(image)
            assertFalse(firstPng.contentEquals(secondPng))
            fun clientEntity(texture: String) =
                """{"minecraft:client_entity":{"description":{"identifier":"demo:entity","geometry":{"unused":"geometry.scf.entity"},"textures":{"default":"$texture"}}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "entity/first.json" to clientEntity("textures/entity/first").toByteArray(),
                "entity/second.json" to clientEntity("textures/entity/second").toByteArray(),
                "textures/entity/first.png" to firstPng,
                "textures/entity/second.png" to secondPng,
            ))

            val report = PackOptimizer().optimizeForLowEnd(input, output)

            assertEquals(1, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/entity/first.png") != null)
                assertNull(zip.getEntry("textures/entity/second.png"))
                val rewritten = zip.getInputStream(zip.getEntry("entity/second.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("textures/entity/first"))
            }
            assertEquals(1, PackAuditor().audit(output).textureFileCount)
        }
    }

    @Test
    fun splitRewriteMergesDifferentEncodingsWithIdenticalPixels() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val support = root.resolve("support.mcpack")
            val baseOutput = root.resolve("base-output.mcpack")
            val supportOutput = root.resolve("support-output.mcpack")
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) {
                image.setRGB(x, y, if ((x + y) % 2 == 0) Color.RED.rgb else Color.BLUE.rgb)
            }
            val firstPng = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val secondPng = indexedPng(image)
            fun clientEntity(texture: String) =
                """{"minecraft:client_entity":{"description":{"identifier":"demo:entity","geometry":{"unused":"geometry.scf.entity"},"textures":{"default":"$texture"}}}}"""
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "entity/first.json" to clientEntity("textures/entity/first").toByteArray(),
                "entity/second.json" to clientEntity("textures/entity/second").toByteArray(),
                "textures/entity/first.png" to firstPng,
                "textures/entity/second.png" to secondPng,
            ))
            writePack(support, mapOf("manifest.json" to "{}".toByteArray()))

            val reports = PackOptimizer().optimizeForLowEndSet(
                listOf(base, support), listOf(baseOutput, supportOutput)
            )

            assertEquals(1, reports[0].entriesFiltered)
            ZipFile(baseOutput.toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/entity/first.png") != null)
                assertNull(zip.getEntry("textures/entity/second.png"))
                val rewritten = zip.getInputStream(zip.getEntry("entity/second.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("textures/entity/first"))
            }
            assertEquals(1, PackAuditor().audit(baseOutput).textureFileCount)
        }
    }

    @Test
    fun splitRewriteAtlasesModelTexturesAndRewritesKnownCrossPackReferences() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val overlay = root.resolve("overlay.mcpack")
            val baseOutput = root.resolve("base-optimized.mcpack")
            val overlayOutput = root.resolve("overlay-optimized.mcpack")
            fun texture(background: Int): ByteArray {
                val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 128) for (x in 0 until 128) {
                    image.setRGB(x, y, if (x in 4 until 52 && y in 4 until 36) Color.WHITE.rgb else background)
                }
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            val firstPng = texture(Color.RED.rgb)
            val secondPng = texture(Color.BLUE.rgb)
            val thirdPng = texture(Color.GREEN.rgb)
            assertFalse(firstPng.contentEquals(thirdPng))
            fun geometry(identifier: String, uvX: Int) = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[$uvX,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) = """{"minecraft:attachable":{"description":{"identifier":"$identifier","geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/first.json" to attachable("geometry.scf.first", "textures/modelengine/first").toByteArray(),
                "attachables/second.json" to attachable("geometry.scf.second", "textures/modelengine/second").toByteArray(),
                "attachables/third.json" to attachable("geometry.scf.third", "textures/modelengine/third").toByteArray(),
                "models/entity/first.geo.json" to geometry("geometry.scf.first", 8).toByteArray(),
                "models/entity/second.geo.json" to geometry("geometry.scf.second", 8).toByteArray(),
                "models/entity/third.geo.json" to geometry("geometry.scf.third", 8).toByteArray(),
                "textures/modelengine/first.png" to firstPng,
                "textures/modelengine/second.png" to secondPng,
                "textures/modelengine/third.png" to thirdPng,
            ))
            writePack(overlay, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/external.json" to attachable("geometry.scf.external", "textures/modelengine/second").toByteArray(),
                "models/entity/external.geo.json" to geometry("geometry.scf.external", 8).toByteArray(),
            ))

            val reports = PackOptimizer().optimizeForLowEndSet(
                listOf(base, overlay), listOf(baseOutput, overlayOutput), 0
            )

            assertEquals(3, reports[0].entriesFiltered)
            assertEquals(0, reports[1].entriesFiltered)
            ZipFile(baseOutput.toFile()).use { zip ->
                assertNull(zip.getEntry("textures/modelengine/first.png"))
                assertNull(zip.getEntry("textures/modelengine/second.png"))
                assertNull(zip.getEntry("textures/modelengine/third.png"))
                val atlasPath = Regex("""textures/entity/fettle/atlases/[^"]+""")
                    .find(zip.getInputStream(zip.getEntry("attachables/third.json"))
                        .use { it.readBytes().decodeToString() })?.value ?: error("Atlas path missing")
                assertTrue(zip.getEntry("$atlasPath.png") != null)
                assertEquals(1, zip.entries().asSequence().count {
                    it.name.startsWith("textures/entity/fettle/atlases/")
                })
            }
            ZipFile(overlayOutput.toFile()).use { zip ->
                val external = zip.getInputStream(zip.getEntry("attachables/external.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(external.contains("textures/entity/fettle/atlases/"))
            }
        }
    }

    @Test
    fun splitRewriteDeduplicatesIdenticalGeometryDefinitionsWithLocalReferences() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val support = root.resolve("support.mcpack")
            val baseOutput = root.resolve("base-optimized.mcpack")
            val supportOutput = root.resolve("support-optimized.mcpack")
            fun geometry(identifier: String) = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String) = """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{}}}}"""
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/first.json" to attachable("geometry.scf.first").toByteArray(),
                "attachables/second.json" to attachable("geometry.scf.second").toByteArray(),
                "models/entity/first.geo.json" to geometry("geometry.scf.first").toByteArray(),
                "models/entity/second.geo.json" to geometry("geometry.scf.second").toByteArray(),
            ))
            writePack(support, mapOf("manifest.json" to "{}".toByteArray()))

            val reports = PackOptimizer().optimizeForLowEndSet(
                listOf(base, support), listOf(baseOutput, supportOutput)
            )

            assertEquals(1, reports[0].entriesFiltered)
            assertEquals(0, reports[1].entriesFiltered)
            ZipFile(baseOutput.toFile()).use { zip ->
                assertTrue(zip.getEntry("models/entity/first.geo.json") != null)
                assertNull(zip.getEntry("models/entity/second.geo.json"))
                val second = zip.getInputStream(zip.getEntry("attachables/second.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(second.contains("geometry.scf.first"))
            }
            assertEquals(1, PackAuditor().audit(baseOutput).modelFileCount)
        }
    }

    @Test
    fun splitRewriteKeepsDuplicateGeometryWhenTextureTransformsDiffer() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val support = root.resolve("support.mcpack")
            val baseOutput = root.resolve("base-optimized.mcpack")
            val supportOutput = root.resolve("support-optimized.mcpack")
            val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 128) for (x in 0 until 128) image.setRGB(x, y, Color.WHITE.rgb)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            fun geometry(identifier: String) = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) = """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/static.json" to attachable("geometry.scf.static", "textures/modelengine/static").toByteArray(),
                "attachables/animated.json" to attachable("geometry.scf.animated", "textures/modelengine/animated").toByteArray(),
                "models/entity/static.geo.json" to geometry("geometry.scf.static").toByteArray(),
                "models/entity/animated.geo.json" to geometry("geometry.scf.animated").toByteArray(),
                "textures/modelengine/static.png" to png,
                "textures/modelengine/animated.png" to png,
                "textures/modelengine/animated.png.mcmeta" to """{"animation":{"frames":[0]}}""".toByteArray(),
            ))
            writePack(support, mapOf("manifest.json" to "{}".toByteArray()))

            val reports = PackOptimizer().optimizeForLowEndSet(
                listOf(base, support), listOf(baseOutput, supportOutput)
            )

            assertEquals(0, reports[0].entriesFiltered)
            ZipFile(baseOutput.toFile()).use { zip ->
                val staticGeometry = zip.getInputStream(zip.getEntry("models/entity/static.geo.json"))
                    .use { it.readBytes().decodeToString() }
                val animatedGeometry = zip.getInputStream(zip.getEntry("models/entity/animated.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(staticGeometry.contains("\"texture_width\":32"))
                assertTrue(animatedGeometry.contains("\"texture_width\":128"))
                assertTrue(zip.getEntry("textures/modelengine/animated.png.mcmeta") != null)
            }
        }
    }

    @Test
    fun splitRewriteKeepsGeometryDefinitionReferencedFromAnotherPack() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val overlay = root.resolve("overlay.mcpack")
            val baseOutput = root.resolve("base-optimized.mcpack")
            val overlayOutput = root.resolve("overlay-optimized.mcpack")
            fun geometry(identifier: String) = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String) = """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{}}}}"""
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/first.json" to attachable("geometry.scf.first").toByteArray(),
                "attachables/second.json" to attachable("geometry.scf.second").toByteArray(),
                "models/entity/first.geo.json" to geometry("geometry.scf.first").toByteArray(),
                "models/entity/second.geo.json" to geometry("geometry.scf.second").toByteArray(),
            ))
            writePack(overlay, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/external.json" to attachable("geometry.scf.second").toByteArray(),
            ))

            val reports = PackOptimizer().optimizeForLowEndSet(
                listOf(base, overlay), listOf(baseOutput, overlayOutput)
            )

            assertEquals(0, reports[0].entriesFiltered)
            assertEquals(0, reports[1].entriesFiltered)
            ZipFile(baseOutput.toFile()).use { zip ->
                assertTrue(zip.getEntry("models/entity/first.geo.json") != null)
                assertTrue(zip.getEntry("models/entity/second.geo.json") != null)
            }
            ZipFile(overlayOutput.toFile()).use { zip ->
                val external = zip.getInputStream(zip.getEntry("attachables/external.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(external.contains("geometry.scf.second"))
            }
        }
    }

    @Test
    fun splitRewriteKeepsGeometryDefinitionWithUnmodeledJsonReference() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val support = root.resolve("support.mcpack")
            val baseOutput = root.resolve("base-optimized.mcpack")
            val supportOutput = root.resolve("support-optimized.mcpack")
            fun geometry(identifier: String) = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String) = """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{}}}}"""
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/first.json" to attachable("geometry.scf.first").toByteArray(),
                "attachables/second.json" to attachable("geometry.scf.second").toByteArray(),
                "models/entity/first.geo.json" to geometry("geometry.scf.first").toByteArray(),
                "models/entity/second.geo.json" to geometry("geometry.scf.second").toByteArray(),
                "render_controllers/other.json" to """{"custom_geometry":"geometry.scf.second"}""".toByteArray(),
            ))
            writePack(support, mapOf("manifest.json" to "{}".toByteArray()))

            val reports = PackOptimizer().optimizeForLowEndSet(
                listOf(base, support), listOf(baseOutput, supportOutput)
            )

            assertEquals(0, reports[0].entriesFiltered)
            ZipFile(baseOutput.toFile()).use { zip ->
                assertTrue(zip.getEntry("models/entity/first.geo.json") != null)
                assertTrue(zip.getEntry("models/entity/second.geo.json") != null)
                val other = zip.getInputStream(zip.getEntry("render_controllers/other.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(other.contains("geometry.scf.second"))
            }
        }
    }

    @Test
    fun splitRewriteMovesDirectGeometryReferencesToAnAtlasAcrossPacks() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val overlay = root.resolve("overlay.mcpack")
            val baseOutput = root.resolve("base-optimized.mcpack")
            val overlayOutput = root.resolve("overlay-optimized.mcpack")
            val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 32) for (x in 0 until 32) image.setRGB(x, y, Color.WHITE.rgb)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            fun geometry(identifier: String) = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) = """{"minecraft:attachable":{"description":{"identifier":"$identifier","geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/first.json" to attachable("geometry.scf.first", "textures/modelengine/first").toByteArray(),
                "attachables/second.json" to attachable("geometry.scf.second", "textures/modelengine/second").toByteArray(),
                "models/entity/first.geo.json" to geometry("geometry.scf.first").toByteArray(),
                "models/entity/second.geo.json" to geometry("geometry.scf.second").toByteArray(),
                "textures/modelengine/first.png" to png,
                "textures/modelengine/second.png" to png,
            ))
            writePack(overlay, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/external.json" to attachable("geometry.scf.external", "textures/modelengine/second").toByteArray(),
                "models/entity/external.geo.json" to geometry("geometry.scf.external").toByteArray(),
            ))

            PackOptimizer().optimizeForLowEndSet(listOf(base, overlay), listOf(baseOutput, overlayOutput))

            ZipFile(baseOutput.toFile()).use { zip ->
                assertNull(zip.getEntry("textures/modelengine/first.png"))
                assertNull(zip.getEntry("textures/modelengine/second.png"))
                assertTrue(zip.entries().asSequence().any { it.name.startsWith("textures/entity/fettle/atlases/") })
            }
            ZipFile(overlayOutput.toFile()).use { zip ->
                val external = zip.getInputStream(zip.getEntry("attachables/external.json"))
                    .use { it.readBytes().decodeToString() }
                val atlasPath = Regex("""textures/entity/fettle/atlases/[^"]+""").find(external)?.value
                    ?: error("Cross-pack atlas path missing")
                assertTrue(ZipFile(baseOutput.toFile()).use { it.getEntry("$atlasPath.png") != null })
            }
        }
    }

    @Test
    fun itemAtlasDedupDoesNotRewriteTexturePathKeys() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/item_texture.json" to """{"texture_data":{"textures/items/java_rendered/first":{"textures":"textures/items/java_rendered/first"},"demo:second":{"textures":"textures/items/java_rendered/second"}}}""".toByteArray(),
                "textures/items/java_rendered/first.png" to png,
                "textures/items/java_rendered/second.png" to png,
            ))

            val report = PackOptimizer().optimize(input, output)

            assertEquals(0, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/items/java_rendered/first.png") != null)
                assertTrue(zip.getEntry("textures/items/java_rendered/second.png") != null)
            }
        }
    }

    @Test
    fun losslessRewriteCropsTransparentModelTextureMarginsExactly() {
        withTempDirectory { root ->
            val paletteTexture = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until paletteTexture.height) for (x in 0 until paletteTexture.width) {
                paletteTexture.setRGB(x, y, if ((x + y) % 2 == 0) 0xFF34AABB.toInt() else 0xFFBD7331.toInt())
            }
            val unchangedPng = ByteArrayOutputStream().also { ImageIO.write(paletteTexture, "png", it) }.toByteArray()
            assertFalse(PngOptimizer.optimizeIfSmaller(unchangedPng).contentEquals(unchangedPng))
            val input = writeUvTexturePack(
                root,
                """{"north":{"uv":[8,8],"uv_size":[24,24]}}""",
                mapOf("textures/misc/unchanged.png" to unchangedPng),
            )
            val output = root.resolve("output.mcpack")

            val report = PackOptimizer().optimizeForLowEnd(input, output)

            assertEquals(1, report.pngEntriesOptimized)
            ZipFile(input.toFile()).use { originalZip ->
                ZipFile(output.toFile()).use { optimizedZip ->
                    val original = ImageIO.read(originalZip.getInputStream(originalZip.getEntry("textures/stitched/shared.png")))
                    val optimized = ImageIO.read(optimizedZip.getInputStream(optimizedZip.getEntry("textures/stitched/shared.png")))
                    assertEquals(32, optimized.width)
                    assertEquals(32, optimized.height)
                    val expected = IntArray(32 * 32)
                    val actual = IntArray(32 * 32)
                    original.getRGB(4, 4, 32, 32, expected, 0, 32)
                    optimized.getRGB(0, 0, 32, 32, actual, 0, 32)
                    assertContentEquals(expected, actual)
                    val geometry = optimizedZip.getInputStream(optimizedZip.getEntry("models/entity/item.geo.json"))
                        .use { it.readBytes().decodeToString() }
                    assertTrue(geometry.contains("\"texture_width\":32"))
                    assertTrue(geometry.contains("\"texture_height\":32"))
                    val originalUnchanged = originalZip.getEntry("textures/misc/unchanged.png")
                    val optimizedUnchanged = optimizedZip.getEntry("textures/misc/unchanged.png")
                    assertContentEquals(unchangedPng, optimizedZip.getInputStream(optimizedUnchanged).readBytes())
                    assertTrue(optimizedUnchanged.compressedSize <= originalUnchanged.compressedSize)
                }
            }
        }
    }

    @Test
    fun losslessCropScalesDeclaredUvsToHigherResolutionPngPixels() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val localOutput = root.resolve("local-output.mcpack")
            val setOutput = root.resolve("set-output.mcpack")
            val support = root.resolve("support.mcpack")
            val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 128) for (x in 0 until 128) {
                image.setRGB(x, y, Color(x, y, (x + y) and 0xff).rgb)
            }
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val geometry = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"geometry.scf.high_resolution","texture_width":16,"texture_height":16},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[4,3],"uv_size":[4,5]}}}]}]}]}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/item.json" to """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.high_resolution"},"textures":{"default":"textures/modelengine/high_resolution"}}}}""".toByteArray(),
                "models/entity/item.geo.json" to geometry.toByteArray(),
                "textures/modelengine/high_resolution.png" to png,
            ))
            writePack(support, mapOf("manifest.json" to "{}".toByteArray()))

            PackOptimizer().optimizeForLowEnd(input, localOutput)
            PackOptimizer().optimizeForLowEndSet(listOf(input, support), listOf(setOutput, root.resolve("support-output.mcpack")))

            for (output in listOf(localOutput, setOutput)) {
                ZipFile(output.toFile()).use { zip ->
                    val cropped = ImageIO.read(zip.getInputStream(zip.getEntry("textures/modelengine/high_resolution.png")))
                    assertEquals(96, cropped.width)
                    assertEquals(96, cropped.height)
                    val expected = IntArray(96 * 96)
                    val actual = IntArray(96 * 96)
                    image.getRGB(0, 0, 96, 96, expected, 0, 96)
                    cropped.getRGB(0, 0, 96, 96, actual, 0, 96)
                    assertContentEquals(expected, actual)

                    val rewritten = zip.getInputStream(zip.getEntry("models/entity/item.geo.json"))
                        .use { it.readBytes().decodeToString() }
                    assertTrue(rewritten.contains("\"texture_width\":96"))
                    assertTrue(rewritten.contains("\"texture_height\":96"))
                    assertTrue(rewritten.contains("\"uv\":[32,24],\"uv_size\":[32,40]"))
                }
                assertEquals(96L * 96L * 4L, PackAuditor().audit(output).textureMemoryBytes)
            }
        }
    }

    @Test
    fun losslessCropDropsOpaquePixelsOutsideTheDeclaredGeometryUvs() {
        withTempDirectory { root ->
            val texture = BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB)
            for (y in 8 until 32) for (x in 8 until 32) texture.setRGB(x, y, 0xFF34AABB.toInt())
            texture.setRGB(2000, 2000, 0xFFFF0000.toInt())
            val png = ByteArrayOutputStream().also { ImageIO.write(texture, "png", it) }.toByteArray()
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/item.json" to """{"minecraft:attachable":{"description":{"identifier":"scf:item","geometry":{"default":"geometry.scf.item"},"textures":{"default":"textures/stitched/shared"}}}}""".toByteArray(),
                "models/entity/item.geo.json" to """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"geometry.scf.item","texture_width":2048,"texture_height":2048},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}""".toByteArray(),
                "textures/stitched/shared.png" to png,
            ))

            PackOptimizer().optimizeForLowEnd(input, output)

            ZipFile(output.toFile()).use { zip ->
                val optimized = ImageIO.read(zip.getInputStream(zip.getEntry("textures/stitched/shared.png")))
                assertEquals(32, optimized.width)
                assertEquals(32, optimized.height)
                val expected = IntArray(32 * 32)
                val actual = IntArray(32 * 32)
                texture.getRGB(4, 4, 32, 32, expected, 0, 32)
                optimized.getRGB(0, 0, 32, 32, actual, 0, 32)
                assertContentEquals(expected, actual)
            }
        }
    }

    @Test
    fun losslessRewriteKeepsStoredPngEntriesStored() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val png = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()
            ZipOutputStream(Files.newOutputStream(input)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
                val entry = ZipEntry("textures/misc/stored.png").apply {
                    method = ZipEntry.STORED
                    size = png.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(png) }.value
                }
                zip.putNextEntry(entry)
                zip.write(png)
                zip.closeEntry()
            }

            PackOptimizer().optimizeForLowEnd(input, output)

            ZipFile(input.toFile()).use { source ->
                ZipFile(output.toFile()).use { rewritten ->
                    val sourcePng = source.getEntry("textures/misc/stored.png")
                    val rewrittenPng = rewritten.getEntry("textures/misc/stored.png")
                    assertEquals(ZipEntry.STORED, rewrittenPng.method)
                    assertContentEquals(
                        source.getInputStream(sourcePng).readBytes(),
                        rewritten.getInputStream(rewrittenPng).readBytes(),
                    )
                }
            }
        }
    }

    @Test
    fun rawZipCompressionOnlyDeflatesWhenItShrinksTheEntryAndPreservesBytes() {
        withTempDirectory { root ->
            val stored = root.resolve("stored.zip")
            val compressed = root.resolve("compressed.zip")
            val bytes = ByteArray(16 * 1024)

            RawZipBuilder(stored).use { zip ->
                zip.addStored("textures/test.png", ByteArrayInputStream(bytes))
                zip.finish()
            }
            RawZipBuilder(compressed).use { zip ->
                zip.add("textures/test.png", ByteArrayInputStream(bytes))
                zip.finish()
            }

            assertTrue(Files.size(compressed) < Files.size(stored))
            ZipFile(compressed.toFile()).use { zip ->
                assertContentEquals(bytes, zip.getInputStream(zip.getEntry("textures/test.png")).readAllBytes())
            }
        }
    }

    @Test
    fun losslessRewriteUpdatesEveryFaceInLargeGeometry() {
        withTempDirectory { root ->
            val faceCount = 512
            val cubes = List(faceCount) {
                """{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}"""
            }.joinToString(",")
            val geometry = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"geometry.scf.item","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[$cubes]}]}]}"""
            val texture = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            for (y in 8 until 32) for (x in 8 until 32) texture.setRGB(x, y, 0xFF34AABB.toInt())
            val png = ByteArrayOutputStream().also { ImageIO.write(texture, "png", it) }.toByteArray()
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/item.json" to """{"minecraft:attachable":{"description":{"identifier":"scf:item","geometry":{"default":"geometry.scf.item"},"textures":{"default":"textures/stitched/shared"}}}}""".toByteArray(),
                "models/entity/item.geo.json" to geometry.toByteArray(),
                "textures/stitched/shared.png" to png,
            ))

            PackOptimizer().optimizeMini(input, output, emptyList(), 32)

            ZipFile(output.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/item.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":32"))
                assertEquals(faceCount, Regex("\"uv\":\\[4,4\\]").findAll(rewritten).count())
                assertEquals(faceCount, Regex("\"uv_size\":\\[24,24\\]").findAll(rewritten).count())
            }
        }
    }

    @Test
    fun modelTextureCropSkipsUnknownUvAndSharedIconReferences() {
        withTempDirectory { root ->
            val unsupportedInput = writeUvTexturePack(root.resolve("unsupported"), "[8,8]")
            val unsupportedOutput = root.resolve("unsupported-output.mcpack")
            PackOptimizer().optimize(unsupportedInput, unsupportedOutput)
            ZipFile(unsupportedOutput.toFile()).use { zip ->
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/stitched/shared.png"))).width)
                val geometry = zip.getInputStream(zip.getEntry("models/entity/item.geo.json")).use { it.readBytes().decodeToString() }
                assertTrue(geometry.contains("\"texture_width\":128"))
            }

            val sharedInput = writeUvTexturePack(
                root.resolve("shared"),
                """{"north":{"uv":[8,8],"uv_size":[24,24]}}""",
                mapOf("textures/item_texture.json" to """{"texture_data":{"icon":{"textures":"textures/stitched/shared"}}}""".toByteArray()),
            )
            val sharedOutput = root.resolve("shared-output.mcpack")
            PackOptimizer().optimize(sharedInput, sharedOutput)
            ZipFile(sharedOutput.toFile()).use { zip ->
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/stitched/shared.png"))).width)
                val geometry = zip.getInputStream(zip.getEntry("models/entity/item.geo.json")).use { it.readBytes().decodeToString() }
                assertTrue(geometry.contains("\"texture_width\":128"))
            }

            val enchantedInput = writeUvTexturePack(
                root.resolve("enchanted"),
                """{"north":{"uv":[8,8],"uv_size":[24,24]}}""",
                mapOf(
                    "attachables/item.json" to """{"minecraft:attachable":{"description":{"identifier":"scf:item","geometry":{"default":"geometry.scf.item"},"textures":{"default":"textures/stitched/shared","enchanted":"textures/misc/enchanted_item_glint"},"render_controllers":["controller.render.item_default"]}}}""".toByteArray(),
                    "textures/misc/enchanted_item_glint.png" to ByteArrayOutputStream().also {
                        ImageIO.write(BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB), "png", it)
                    }.toByteArray(),
                ),
            )
            val enchantedOutput = root.resolve("enchanted-output.mcpack")
            PackOptimizer().optimize(enchantedInput, enchantedOutput)
            ZipFile(enchantedOutput.toFile()).use { zip ->
                assertEquals(32, ImageIO.read(zip.getInputStream(zip.getEntry("textures/stitched/shared.png"))).width)
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/misc/enchanted_item_glint.png"))).width)
                val geometry = zip.getInputStream(zip.getEntry("models/entity/item.geo.json")).use { it.readBytes().decodeToString() }
                assertTrue(geometry.contains("\"texture_width\":32"))
            }

            val materialInput = writeUvTexturePack(
                root.resolve("material"),
                """{"north":{"uv":[8,8],"uv_size":[24,24],"material_instance":"default"}}""",
            )
            val materialOutput = root.resolve("material-output.mcpack")
            PackOptimizer().optimize(materialInput, materialOutput)
            ZipFile(materialOutput.toFile()).use { zip ->
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/stitched/shared.png"))).width)
                val geometry = zip.getInputStream(zip.getEntry("models/entity/item.geo.json")).use { it.readBytes().decodeToString() }
                assertTrue(geometry.contains("\"texture_width\":128"))
            }
        }
    }

    @Test
    fun cropsStaticTextureBesideUvAnimatedControllerAndLeavesAnimatedTextureUnchanged() {
        withTempDirectory { root ->
            val animatedPng = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()
            val input = writeUvTexturePack(
                root,
                """{"north":{"uv":[8,8],"uv_size":[24,24]}}""",
                mapOf(
                    "materials/entity.material" to """{"materials":{"animated":{"+defines":["USE_UV_ANIM"]}}}""".toByteArray(),
                    "render_controllers/animated.json" to """{"render_controllers":{"controller.render.animated":{"textures":["Texture.default"],"uv_anim":{"offset":[0,0],"scale":[1,1]}}}}""".toByteArray(),
                    "attachables/animated.json" to """{"minecraft:attachable":{"description":{"identifier":"scf:animated","geometry":{"default":"geometry.scf.animated"},"textures":{"default":"textures/animated"},"render_controllers":["controller.render.animated"]}}}""".toByteArray(),
                    "models/entity/animated.geo.json" to """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.animated","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}""".toByteArray(),
                    "textures/animated.png" to animatedPng,
                ),
            )
            val output = root.resolve("output.mcpack")

            PackOptimizer().optimize(input, output)

            ZipFile(output.toFile()).use { zip ->
                assertEquals(32, ImageIO.read(zip.getInputStream(zip.getEntry("textures/stitched/shared.png"))).width)
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/animated.png"))).width)
                val animatedGeometry = zip.getInputStream(zip.getEntry("models/entity/animated.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(animatedGeometry.contains("\"texture_width\":128"))
            }
        }
    }

    @Test
    fun miniProfileKeepsTexturesUnchangedWhenUvAnimationScanIsIncomplete() {
        withTempDirectory { root ->
            val image = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()
            val input = root.resolve("unknown-uv-controller.mcpack")
            val output = root.resolve("unknown-uv-controller-mini.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "render_controllers/unknown.json" to
                    """{"unknown":{"uv_anim":{"texture":"textures/animated"}}}""".toByteArray(),
                "textures/animated.png" to image,
            ))

            val rewrite = PackOptimizer().optimizeMini(input, output, emptyList(), 128)

            assertEquals(0, rewrite.pngEntriesOptimized)
            ZipFile(output.toFile()).use { zip ->
                assertEquals(512, ImageIO.read(zip.getInputStream(zip.getEntry("textures/animated.png"))).width)
            }
        }
    }

    @Test
    fun uvControllerScanStopsAtItsByteBudget() {
        withTempDirectory { root ->
            val input = root.resolve("uv-scan-budget.mcpack")
            val json = ByteArray(512 * 1024) { ' '.code.toByte() }.also {
                it[0] = '{'.code.toByte()
                it[1] = '}'.code.toByte()
            }
            writePack(input, (0 until 3).associate { index ->
                "render_controllers/controller_$index.json" to json
            })

            val scan = ZipFile(input.toFile()).use {
                PackOptimizer().findUvAnimationControllers(it, 1024L * 1024L)
            }

            assertFalse(scan.complete)
            assertEquals(1024L * 1024L, scan.bytesScanned)
        }
    }

    @Test
    fun miniProfileKeepsTexturesUnchangedWhenUvAnimationIsUnclassified() {
        withTempDirectory { root ->
            val image = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()
            val input = root.resolve("unknown-uv-controller.mcpack")
            val output = root.resolve("unknown-uv-controller-mini.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "render_controllers/unknown.json" to
                    """{"unknown":{"uv_anim":{"texture":"textures/animated"}}}""".toByteArray(),
                "textures/animated.png" to image,
            ))

            val rewrite = PackOptimizer().optimizeMini(input, output, emptyList(), 128)

            assertEquals(0, rewrite.pngEntriesOptimized)
            ZipFile(output.toFile()).use { zip ->
                assertEquals(512, ImageIO.read(zip.getInputStream(zip.getEntry("textures/animated.png"))).width)
            }
        }
    }

    @Test
    fun losslessSetSharesPixelIdenticalAttachableTexturesOnlyInsideLinkedPackGroups() {
        withTempDirectory { root ->
            val base = root.resolve("base.mcpack")
            val overlay = root.resolve("overlay.mcpack")
            val independent = root.resolve("independent.mcpack")
            val textureSet = root.resolve("texture-set.mcpack")
            val outputs = listOf("base-out.mcpack", "overlay-out.mcpack", "independent-out.mcpack", "texture-set-out.mcpack")
                .map(root::resolve)
            val argb = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val rgb = BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR)
            for (y in 0 until 16) for (x in 0 until 16) {
                val pixel = 0xff000000.toInt() or ((x * 13) shl 16) or ((y * 11) shl 8) or (x + y)
                argb.setRGB(x, y, pixel)
                rgb.setRGB(x, y, pixel)
            }
            val argbPng = ByteArrayOutputStream().also { ImageIO.write(argb, "png", it) }.toByteArray()
            val rgbPng = ByteArrayOutputStream().also { ImageIO.write(rgb, "png", it) }.toByteArray()
            assertFalse(argbPng.contentEquals(rgbPng))
            assertTrue(PngOptimizer.sameDecodedPixels(argbPng, rgbPng))

            fun model(identifier: String) =
                """{"minecraft:geometry":[{"description":{"identifier":"$identifier"}}]}"""
            fun attachable(identifier: String, texture: String) =
                """{"minecraft:attachable":{"description":{"identifier":"$identifier","geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""

            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/shared.geo.json" to model("geometry.scf.shared").toByteArray(),
                "attachables/base.json" to attachable("geometry.scf.shared", "textures/model/base").toByteArray(),
                "textures/model/base.png" to argbPng,
            ))
            writePack(overlay, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/overlay.json" to attachable("geometry.scf.shared", "textures/model/frame").toByteArray(),
                "textures/model/frame.png" to rgbPng,
            ))
            writePack(independent, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/independent.geo.json" to model("geometry.scf.independent").toByteArray(),
                "attachables/independent.json" to attachable("geometry.scf.independent", "textures/model/independent").toByteArray(),
                "textures/model/independent.png" to rgbPng,
            ))
            writePack(textureSet, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/texture-set.json" to attachable("geometry.scf.shared", "textures/model/pbr").toByteArray(),
                "textures/model/pbr.png" to rgbPng,
                "textures/model/pbr.texture_set.json" to """{"format_version":"1.21.30","minecraft:texture_set":{"color":[1.0,1.0,1.0,1.0]}}""".toByteArray(),
            ))

            val reports = PackOptimizer().optimizeForLowEndSet(
                listOf(base, overlay, independent, textureSet), outputs,
            )

            assertEquals(1, reports[1].entriesFiltered)
            ZipFile(outputs[1].toFile()).use { zip ->
                assertNull(zip.getEntry("textures/model/frame.png"))
                val rewritten = zip.getInputStream(zip.getEntry("attachables/overlay.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("textures/model/base"))
            }
            ZipFile(outputs[2].toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/model/independent.png") != null)
            }
            ZipFile(outputs[3].toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/model/pbr.png") != null)
                assertTrue(zip.getEntry("textures/model/pbr.texture_set.json") != null)
            }

            val withoutLinkGroups = outputs.indices.map { root.resolve("unlinked-$it.mcpack") }
            PackOptimizer().optimizeForLowEndSet(
                listOf(base, overlay, independent, textureSet), withoutLinkGroups, 0, emptyList(),
            )
            ZipFile(withoutLinkGroups[1].toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/model/frame.png") != null)
                val unchanged = zip.getInputStream(zip.getEntry("attachables/overlay.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(unchanged.contains("textures/model/frame"))
            }

            val shadowProtectedOutputs = outputs.indices.map { root.resolve("shadow-protected-$it.mcpack") }
            PackOptimizer().optimizeForLowEndSet(
                listOf(base, overlay, independent, textureSet), shadowProtectedOutputs, 0,
                PackOptimizer().linkedPackGroups(listOf(base, overlay, independent, textureSet)),
                setOf("textures/model/base.png"),
            )
            ZipFile(shadowProtectedOutputs[1].toFile()).use { zip ->
                assertTrue(zip.getEntry("textures/model/frame.png") != null)
            }
        }
    }

    @Test
    fun deduplicatesByteIdenticalTexturesUsedByUvAnimatedControllers() {
        withTempDirectory { root ->
            val input = root.resolve("uv-animated-duplicates.mcpack")
            val singleOutput = root.resolve("single.mcpack")
            val setOutput = root.resolve("set.mcpack")
            val supportInput = root.resolve("support.mcpack")
            val supportOutput = root.resolve("support-optimized.mcpack")
            val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB).apply {
                for (y in 8 until 32) for (x in 8 until 32) setRGB(x, y, 0xff34aabb.toInt())
            }
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            fun geometry(identifier: String) =
                """{"minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) =
                """{"minecraft:attachable":{"description":{"identifier":"$identifier","geometry":{"uv_128_128":"$identifier"},"textures":{"tail":"$texture"},"render_controllers":["controller.render.animated"]}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "render_controllers/animated.json" to """{"render_controllers":{"controller.render.animated":{"textures":["Texture.tail"],"uv_anim":{"offset":[0,"query.life_time"],"scale":[1,1]}}}}""".toByteArray(),
                "attachables/first.json" to attachable("geometry.scf.first", "textures/entity/first").toByteArray(),
                "attachables/second.json" to attachable("geometry.scf.second", "textures/entity/second").toByteArray(),
                "models/entity/first.geo.json" to geometry("geometry.scf.first").toByteArray(),
                "models/entity/second.geo.json" to geometry("geometry.scf.second").toByteArray(),
                "textures/entity/first.png" to png,
                "textures/entity/second.png" to png,
            ))
            writePack(supportInput, mapOf("manifest.json" to "{}".toByteArray()))

            val singleReport = PackOptimizer().optimizeForLowEnd(input, singleOutput)
            val setReports = PackOptimizer().optimizeForLowEndSet(
                listOf(input, supportInput), listOf(setOutput, supportOutput),
            )

            assertEquals(1, singleReport.entriesFiltered)
            assertEquals(1, setReports[0].entriesFiltered)
            for (output in listOf(singleOutput, setOutput)) {
                ZipFile(output.toFile()).use { zip ->
                    assertTrue(zip.getEntry("textures/entity/first.png") != null)
                    assertNull(zip.getEntry("textures/entity/second.png"))
                    assertEquals(1, PackAuditor().audit(output).estimatedTextureHandleCount)
                    for (attachable in listOf("first", "second")) {
                        val text = zip.getInputStream(zip.getEntry("attachables/$attachable.json"))
                            .use { it.readBytes().decodeToString() }
                        assertTrue(text.contains("textures/entity/first"))
                        assertFalse(text.contains("textures/entity/second"))
                    }
                    for (geometry in listOf("first", "second")) {
                        val text = zip.getInputStream(zip.getEntry("models/entity/$geometry.geo.json"))
                            .use { it.readBytes().decodeToString() }
                        assertTrue(text.contains("\"texture_width\":128"))
                    }
                }
            }
        }
    }

    @Test
    fun losslessSetRewriteCropsSplitFramesWithTheirSharedBaseGeometry() {
        withTempDirectory { root ->
            val frame = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            for (y in 8 until 32) for (x in 8 until 32) frame.setRGB(x, y, 0xFF34AABB.toInt())
            val framePng = ByteArrayOutputStream().also { ImageIO.write(frame, "png", it) }.toByteArray()
            val smallPng = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()
            val geometry = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"geometry.scf.shared","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[24,24]}}}]}]}]}"""
            val alternateGeometry = geometry.replace("geometry.scf.shared", "geometry.scf.alternate")
            val attachable = """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.shared","frame_0":"geometry.scf.shared","frame_1":"geometry.scf.shared","alternate":"geometry.scf.alternate"},"textures":{"default":"textures/stitched/default","frame_0":"textures/stitched/frame_0","frame_1":"textures/stitched/frame_1","alternate":"textures/stitched/default","enchanted":"textures/misc/glint"},"materials":{"default":"entity_alphatest","enchanted":"entity_alphatest_glint"}}}}"""
            val base = root.resolve("base.mcpack")
            val overlay = root.resolve("overlay.mcpack")
            val baseOutput = root.resolve("base-optimized.mcpack")
            val overlayOutput = root.resolve("overlay-optimized.mcpack")
            val baseMiniOutput = root.resolve("base-mini.mcpack")
            val overlayMiniOutput = root.resolve("overlay-mini.mcpack")
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/base-reference.json" to """{"geometry":{"default":"geometry.scf.shared"},"textures":{"default":"textures/stitched/default"}}""".toByteArray(),
                "models/entity/shared.geo.json" to geometry.toByteArray(),
                "models/entity/alternate.geo.json" to alternateGeometry.toByteArray(),
            ))
            writePack(overlay, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/frames.json" to attachable.toByteArray(),
                "textures/stitched/default.png" to framePng,
                "textures/stitched/frame_0.png" to framePng,
                "textures/stitched/frame_1.png" to framePng,
                "textures/misc/small.png" to smallPng,
                "textures/misc/glint.png" to framePng,
            ))

            PackOptimizer().optimizeForLowEndSet(listOf(base, overlay), listOf(baseOutput, overlayOutput))

            ZipFile(baseOutput.toFile()).use { zip ->
                val geometryFiles = zip.entries().asSequence()
                    .filter { it.name.startsWith("models/entity/") && it.name.endsWith(".geo.json") }
                    .toList()
                assertTrue(geometryFiles.isNotEmpty())
                for (geometry in geometryFiles) {
                    val rewritten = zip.getInputStream(geometry).use { it.readBytes().decodeToString() }
                    assertTrue(rewritten.contains("\"texture_width\":34"))
                    assertTrue(rewritten.contains("\"texture_height\":34"))
                }
                val atlasPath = zip.entries().asSequence().map { it.name }
                    .first { it.startsWith("textures/entity/fettle/atlases/") }
                val atlas = ImageIO.read(zip.getInputStream(zip.getEntry(atlasPath)))
                assertEquals(frame.getRGB(8, 8), atlas.getRGB(5, 5))
            }
            ZipFile(overlayOutput.toFile()).use { zip ->
                assertNull(zip.getEntry("textures/stitched/default.png"))
                assertNull(zip.getEntry("textures/stitched/frame_0.png"))
                assertNull(zip.getEntry("textures/stitched/frame_1.png"))
                val frames = zip.getInputStream(zip.getEntry("attachables/frames.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(frames.contains("textures/entity/fettle/atlases/"))
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/misc/glint.png"))).width)
            }

            PackOptimizer().optimizeForLowEndSet(
                listOf(base, overlay), listOf(baseMiniOutput, overlayMiniOutput), 32
            )
            ZipFile(baseMiniOutput.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/shared.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":32"))
                assertTrue(rewritten.contains("\"uv\":[4,4]"))
                assertTrue(rewritten.contains("\"uv_size\":[24,24]"))
                val alternate = zip.getInputStream(zip.getEntry("models/entity/alternate.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(alternate.contains("\"texture_width\":32"))
            }
            ZipFile(overlayMiniOutput.toFile()).use { zip ->
                for (path in listOf("default", "frame_0")) {
                    val image = ImageIO.read(zip.getInputStream(zip.getEntry("textures/stitched/$path.png")))
                    assertEquals(32, image.width)
                    assertEquals(32, image.height)
                }
                assertNull(zip.getEntry("textures/stitched/frame_1.png"))
                val frames = zip.getInputStream(zip.getEntry("attachables/frames.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(frames.contains("\"frame_0\":\"textures/stitched/frame_0\""))
                assertTrue(frames.contains("\"frame_1\":\"textures/stitched/frame_0\""))
                assertEquals(32, ImageIO.read(zip.getInputStream(zip.getEntry("textures/misc/glint.png"))).width)
            }
            ZipFile(overlayOutput.toFile()).use { fullZip ->
                ZipFile(overlayMiniOutput.toFile()).use { miniZip ->
                    val losslessSmall = fullZip.getInputStream(fullZip.getEntry("textures/misc/small.png")).readBytes()
                    val miniSmall = miniZip.getInputStream(miniZip.getEntry("textures/misc/small.png")).readBytes()
                    assertContentEquals(losslessSmall, miniSmall)
                }
            }
        }
    }

    @Test
    fun resizesStaticRenderControllerGeometryTextureAliasesInSingleAndSplitPacks() {
        withTempDirectory { root ->
            val geometryId = "geometry.scf.alias"
            val geometry = """{"minecraft:geometry":[{"description":{"identifier":"$geometryId","texture_width":256,"texture_height":256},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[0,0],"uv_size":[256,256]}}}]}]}]}"""
            val entity = """{"minecraft:client_entity":{"description":{"identifier":"scf:alias","geometry":{"uv_512_512":"$geometryId"},"textures":{"default":"textures/entity/alias"},"render_controllers":["controller.render.alias"]}}}"""
            val controller = """{"render_controllers":{"controller.render.alias":{"geometry":"Geometry.uv_512_512","textures":["Texture.default"]}}}"""
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB).apply {
                for (y in 0 until height) for (x in 0 until width) setRGB(x, y, Color(x, y, (x + y) % 256).rgb)
            }
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val single = root.resolve("single.mcpack")
            val singleOutput = root.resolve("single-safe.mcpack")
            val base = root.resolve("base.mcpack")
            val overlay = root.resolve("overlay.mcpack")
            val baseOutput = root.resolve("base-safe.mcpack")
            val overlayOutput = root.resolve("overlay-safe.mcpack")
            writePack(single, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/alias.geo.json" to geometry.toByteArray(),
                "entity/alias.entity.json" to entity.toByteArray(),
                "render_controllers/alias.json" to controller.toByteArray(),
                "textures/entity/alias.png" to png,
            ))
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/alias.geo.json" to geometry.toByteArray(),
            ))
            writePack(overlay, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "entity/alias.entity.json" to entity.toByteArray(),
                "render_controllers/alias.json" to controller.toByteArray(),
                "textures/entity/alias.png" to png,
            ))

            PackOptimizer().optimizeMini(single, singleOutput, emptyList(), 128)
            assertTextureControllerResize(singleOutput)

            val linkedGroups = PackOptimizer().linkedPackGroups(listOf(base, overlay))
            assertTrue(setOf(0, 1) in linkedGroups)
            PackOptimizer().optimizeResizedPackSet(
                listOf(base, overlay), listOf(baseOutput, overlayOutput), 128,
            )
            ZipFile(baseOutput.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/alias.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":128"))
                assertTrue(rewritten.contains("\"uv_size\":[128,128]"))
            }
            ZipFile(overlayOutput.toFile()).use { zip ->
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/alias.png"))).width)
            }
        }
    }

    @Test
    fun resizesFlipbookFramesSelectedFromStaticGeometryController() {
        withTempDirectory { root ->
            val input = root.resolve("flipbook.mcpack")
            val output = root.resolve("flipbook-safe.mcpack")
            val geometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.flipbook","texture_width":256,"texture_height":256},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[0,0],"uv_size":[256,256]}}}]}]}]}"""
            val attachable = """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.flipbook"},"textures":{"frame_0":"textures/entity/frame_0","frame_1":"textures/entity/frame_1","enchanted":"textures/misc/enchanted_item_glint"},"render_controllers":["controller.render.flipbook"]}}}"""
            val controller = """{"render_controllers":{"controller.render.flipbook":{"arrays":{"textures":{"Array.frames":["Texture.frame_0","Texture.frame_1"]}},"geometry":"Geometry.default","textures":["Array.frames[math.mod(math.floor(q.life_time * 20 / 3), 2)]","Texture.enchanted"]}}}"""
            fun png(dimension: Int, color: Int): ByteArray = ByteArrayOutputStream().also {
                val image = BufferedImage(dimension, dimension, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, color)
                ImageIO.write(image, "png", it)
            }.toByteArray()
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/flipbook.geo.json" to geometry.toByteArray(),
                "attachables/flipbook.json" to attachable.toByteArray(),
                "render_controllers/flipbook.json" to controller.toByteArray(),
                "textures/entity/frame_0.png" to png(256, Color.RED.rgb),
                "textures/entity/frame_1.png" to png(256, Color.BLUE.rgb),
                "textures/misc/enchanted_item_glint.png" to png(64, Color.WHITE.rgb),
            ))

            PackOptimizer().optimizeMini(input, output, emptyList(), 128)

            ZipFile(output.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/flipbook.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":128"))
                assertTrue(rewritten.contains("\"uv_size\":[128,128]"))
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/frame_0.png"))).width)
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/frame_1.png"))).width)
                assertEquals(64, ImageIO.read(zip.getInputStream(zip.getEntry("textures/misc/enchanted_item_glint.png"))).width)
            }
        }
    }

    @Test
    fun losslessCropPreservesPixelsForControllerArrayFrames() {
        withTempDirectory { root ->
            val input = root.resolve("flipbook-lossless.mcpack")
            val output = root.resolve("flipbook-lossless-cropped.mcpack")
            val geometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.flipbook_crop","texture_width":256,"texture_height":256},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[80,80],"uv_size":[16,16]}}}]}]}]}"""
            val attachable = """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.flipbook_crop"},"textures":{"frame_0":"textures/entity/frame_0","frame_1":"textures/entity/frame_1","enchanted":"textures/misc/enchanted_item_glint"},"render_controllers":["controller.render.flipbook_crop"]}}}"""
            val controller = """{"render_controllers":{"controller.render.flipbook_crop":{"arrays":{"textures":{"Array.frames":["Texture.frame_0","Texture.frame_1"]}},"geometry":"Geometry.default","textures":["Array.frames[query.variant]","Texture.enchanted"]}}}"""
            fun png(seed: Int): ByteArray {
                val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until 256) for (x in 0 until 256) {
                    image.setRGB(x, y, Color(seed + x % 127, seed + y % 127, seed + (x + y) % 127).rgb)
                }
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            val frame0 = png(20)
            val frame1 = png(60)
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/flipbook-crop.geo.json" to geometry.toByteArray(),
                "attachables/flipbook-crop.json" to attachable.toByteArray(),
                "render_controllers/flipbook-crop.json" to controller.toByteArray(),
                "textures/entity/frame_0.png" to frame0,
                "textures/entity/frame_1.png" to frame1,
                "textures/misc/enchanted_item_glint.png" to ByteArrayOutputStream().also {
                    ImageIO.write(BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png", it)
                }.toByteArray(),
            ))

            PackOptimizer().optimize(input, output)

            ZipFile(output.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/flipbook-crop.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":24"))
                assertTrue(rewritten.contains("\"uv\":[4,4]"))
                assertTrue(rewritten.contains("\"uv_size\":[16,16]"))
                for ((name, source) in listOf("frame_0" to frame0, "frame_1" to frame1)) {
                    val cropped = ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/$name.png")))
                    assertEquals(24, cropped.width)
                    val expected = IntArray(24 * 24)
                    val actual = IntArray(24 * 24)
                    ImageIO.read(ByteArrayInputStream(source)).getRGB(76, 76, 24, 24, expected, 0, 24)
                    cropped.getRGB(0, 0, 24, 24, actual, 0, 24)
                    assertContentEquals(expected, actual)
                }
                assertEquals(64, ImageIO.read(zip.getInputStream(zip.getEntry("textures/misc/enchanted_item_glint.png"))).width)
            }
        }
    }

    @Test
    fun pairsDynamicGeometryAndTextureAliasesByTheSameArrayIndex() {
        withTempDirectory { root ->
            val input = root.resolve("matched-arrays.mcpack")
            val output = root.resolve("matched-arrays-safe.mcpack")
            val smallGeometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.small_array","texture_width":16,"texture_height":16},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[0,0],"uv_size":[16,16]}}}]}]}]}"""
            val largeGeometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.large_array","texture_width":256,"texture_height":256},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[0,0],"uv_size":[256,256]}}}]}]}]}"""
            val attachable = """{"minecraft:attachable":{"description":{"geometry":{"small":"geometry.scf.small_array","large":"geometry.scf.large_array"},"textures":{"small":"textures/entity/small_array","large":"textures/entity/large_array","enchanted":"textures/misc/enchanted_item_glint"},"render_controllers":["controller.render.matched_arrays"]}}}"""
            val controller = """{"render_controllers":{"controller.render.matched_arrays":{"arrays":{"textures":{"Array.frames":["Texture.small","Texture.large"]},"geometries":{"Array.geometries":["Geometry.small","Geometry.large"]}},"geometry":"Array.geometries[query.variant]","textures":["Array.frames[query.variant]","Texture.enchanted"]}}}"""
            fun png(dimension: Int): ByteArray = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(dimension, dimension, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/small-array.geo.json" to smallGeometry.toByteArray(),
                "models/entity/large-array.geo.json" to largeGeometry.toByteArray(),
                "attachables/matched-arrays.json" to attachable.toByteArray(),
                "render_controllers/matched-arrays.json" to controller.toByteArray(),
                "textures/entity/small_array.png" to png(256),
                "textures/entity/large_array.png" to png(64),
                "textures/misc/enchanted_item_glint.png" to png(64),
            ))

            PackOptimizer().optimizeMini(input, output, emptyList(), 128)

            ZipFile(output.toFile()).use { zip ->
                val small = zip.getInputStream(zip.getEntry("models/entity/small-array.geo.json"))
                    .use { it.readBytes().decodeToString() }
                val large = zip.getInputStream(zip.getEntry("models/entity/large-array.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(small.contains("\"texture_width\":128"))
                assertTrue(small.contains("\"uv_size\":[128,128]"))
                assertTrue(large.contains("\"texture_width\":256"))
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/small_array.png"))).width)
                assertEquals(64, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/large_array.png"))).width)
                assertEquals(64, ImageIO.read(zip.getInputStream(zip.getEntry("textures/misc/enchanted_item_glint.png"))).width)
            }
        }
    }

    @Test
    fun resizesDynamicGeometryTextureWithoutRewritingUvData() {
        withTempDirectory { root ->
            val input = root.resolve("dynamic-controller.mcpack")
            val output = root.resolve("dynamic-controller-safe.mcpack")
            val geometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.dynamic","texture_width":256,"texture_height":256},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[0,0],"uv_size":[256,256]}}}]}]}]}"""
            val entity = """{"minecraft:client_entity":{"description":{"geometry":{"uv_512_512":"geometry.scf.dynamic"},"textures":{"default":"textures/entity/dynamic"},"render_controllers":["controller.render.dynamic"]}}}"""
            val controller = """{"render_controllers":{"controller.render.dynamic":{"geometry":"Array.geometries[query.variant]","textures":["Texture.default"]}}}"""
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val geometryBytes = geometry.toByteArray()
            val entityBytes = entity.toByteArray()
            val controllerBytes = controller.toByteArray()
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/dynamic.geo.json" to geometryBytes,
                "entity/dynamic.entity.json" to entityBytes,
                "render_controllers/dynamic.json" to controllerBytes,
                "textures/entity/dynamic.png" to png,
            ))

            PackOptimizer().optimizeMini(input, output, emptyList(), 128)

            ZipFile(output.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/dynamic.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":256"))
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/dynamic.png"))).width)
            }

            val geometryPack = root.resolve("dynamic-geometry.mcpack")
            val attachmentPack = root.resolve("dynamic-attachment.mcpack")
            val texturePack = root.resolve("dynamic-texture.mcpack")
            val geometryOutput = root.resolve("dynamic-geometry-safe.mcpack")
            val attachmentOutput = root.resolve("dynamic-attachment-safe.mcpack")
            val textureOutput = root.resolve("dynamic-texture-safe.mcpack")
            writePack(geometryPack, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/dynamic.geo.json" to geometryBytes,
            ))
            writePack(attachmentPack, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "entity/dynamic.entity.json" to entityBytes,
                "render_controllers/dynamic.json" to controllerBytes,
            ))
            writePack(texturePack, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/entity/dynamic.png" to png,
            ))
            val packs = listOf(geometryPack, attachmentPack, texturePack)
            assertTrue(setOf(0, 1, 2) in PackOptimizer().linkedPackGroups(packs))
            PackOptimizer().optimizeResizedPackSet(
                packs, listOf(geometryOutput, attachmentOutput, textureOutput), 128,
            )
            ZipFile(geometryOutput.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/dynamic.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":256"))
            }
            ZipFile(textureOutput.toFile()).use { zip ->
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/dynamic.png"))).width)
            }
        }
    }

    @Test
    fun resizesSharedTextureAcrossDifferentGeometryUvScales() {
        withTempDirectory { root ->
            val smallId = "geometry.scf.small_uv"
            val largeId = "geometry.scf.large_uv"
            fun geometry(identifier: String, dimension: Int) =
                """{"minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":$dimension,"texture_height":$dimension},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[0,0],"uv_size":[$dimension,$dimension]}}}]}]}]}"""
            fun attachable(identifier: String) =
                """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"textures/entity/shared_uv"}}}}"""
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val single = root.resolve("single.mcpack")
            val singleOutput = root.resolve("single-safe.mcpack")
            val base = root.resolve("base.mcpack")
            val overlay = root.resolve("overlay.mcpack")
            val baseOutput = root.resolve("base-safe.mcpack")
            val overlayOutput = root.resolve("overlay-safe.mcpack")
            val smallModel = geometry(smallId, 16).toByteArray()
            val largeModel = geometry(largeId, 256).toByteArray()
            val attachments = mapOf(
                "attachables/small.json" to attachable(smallId).toByteArray(),
                "attachables/large.json" to attachable(largeId).toByteArray(),
            )
            writePack(single, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/small.geo.json" to smallModel,
                "models/entity/large.geo.json" to largeModel,
                "textures/entity/shared_uv.png" to png,
            ) + attachments)
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/small.geo.json" to smallModel,
                "models/entity/large.geo.json" to largeModel,
            ))
            writePack(overlay, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/entity/shared_uv.png" to png,
            ) + attachments)

            PackOptimizer().optimizeMini(single, singleOutput, emptyList(), 128)
            assertMixedGeometryResize(singleOutput, singleOutput)

            assertTrue(setOf(0, 1) in PackOptimizer().linkedPackGroups(listOf(base, overlay)))
            PackOptimizer().optimizeResizedPackSet(
                listOf(base, overlay), listOf(baseOutput, overlayOutput), 128,
            )
            assertMixedGeometryResize(baseOutput, overlayOutput)
        }
    }

    private fun assertTextureControllerResize(output: Path) {
        ZipFile(output.toFile()).use { zip ->
            val rewritten = zip.getInputStream(zip.getEntry("models/entity/alias.geo.json"))
                .use { it.readBytes().decodeToString() }
            assertTrue(rewritten.contains("\"texture_width\":128"))
            assertTrue(rewritten.contains("\"uv_size\":[128,128]"))
            assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/alias.png"))).width)
        }
    }

    private fun assertMixedGeometryResize(geometryPack: Path, texturePack: Path) {
        ZipFile(geometryPack.toFile()).use { zip ->
            for (model in listOf("small", "large")) {
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/$model.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":128"))
                assertTrue(rewritten.contains("\"texture_height\":128"))
                assertTrue(rewritten.contains("\"uv_size\":[128,128]"))
            }
        }
        ZipFile(texturePack.toFile()).use { zip ->
            assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/shared_uv.png"))).width)
        }
    }

    @Test
    fun resizedPackSetScalesUvsWhenTexturePixelsExceedGeometryTexels() {
        withTempDirectory { root ->
            val input = root.resolve("highres.mcpack")
            val output = root.resolve("highres-mini.mcpack")
            val image = BufferedImage(432, 432, BufferedImage.TYPE_INT_ARGB).apply {
                for (y in 0 until height) for (x in 0 until width) {
                    setRGB(x, y, Color(x % 256, y % 256, (x + y) % 256).rgb)
                }
            }
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val geometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.highres","texture_width":16,"texture_height":16},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[1,2],"uv_size":[15,14]}}}]}]}]}"""
            val attachable = """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.highres"},"textures":{"default":"textures/entity/highres"}}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/highres.geo.json" to geometry.toByteArray(),
                "attachables/highres.json" to attachable.toByteArray(),
                "textures/entity/highres.png" to png,
            ))

            PackOptimizer().optimizeResizedPackSet(listOf(input), listOf(output), 128)

            ZipFile(output.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/highres.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":128"))
                assertTrue(rewritten.contains("\"texture_height\":128"))
                assertTrue(rewritten.contains("\"uv\":[8,16]"))
                assertTrue(rewritten.contains("\"uv_size\":[120,112]"))
                val resized = ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/highres.png")))
                assertEquals(128, resized.width)
                assertEquals(128, resized.height)
            }
        }
    }

    @Test
    fun resizedSplitSetScalesUvsAcrossTextureAndGeometryPacks() {
        withTempDirectory { root ->
            val base = root.resolve("geometry.mcpack")
            val textures = root.resolve("textures.mcpack")
            val baseOutput = root.resolve("geometry-mini.mcpack")
            val textureOutput = root.resolve("textures-mini.mcpack")
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).apply {
                for (y in 0 until height) for (x in 0 until width) setRGB(x, y, Color(x * 4, y * 4, 120).rgb)
            }
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val geometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.split_highres","texture_width":16,"texture_height":16},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[4,4],"uv_size":[8,8]}}}]}]}]}"""
            val attachable = """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.split_highres"},"textures":{"default":"textures/entity/split_highres"}}}}"""
            writePack(base, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/split.geo.json" to geometry.toByteArray(),
            ))
            writePack(textures, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/split.json" to attachable.toByteArray(),
                "textures/entity/split_highres.png" to png,
            ))

            PackOptimizer().optimizeResizedPackSet(
                listOf(base, textures), listOf(baseOutput, textureOutput), 8,
            )

            ZipFile(baseOutput.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/split.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":8"))
                assertTrue(rewritten.contains("\"texture_height\":8"))
                assertTrue(rewritten.contains("\"uv\":[2,2]"))
                assertTrue(rewritten.contains("\"uv_size\":[4,4]"))
            }
            ZipFile(textureOutput.toFile()).use { zip ->
                val resized = ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/split_highres.png")))
                assertEquals(8, resized.width)
                assertEquals(8, resized.height)
            }
        }
    }

    @Test
    fun resizedPackSetLeavesUnsupportedColorMetadataGeometryUnchanged() {
        withTempDirectory { root ->
            val input = root.resolve("gamma-only.mcpack")
            val output = root.resolve("gamma-only-safe.mcpack")
            val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
            val gammaPng = pngWithUnpairedGamma(image)
            val itemPng = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            assertFalse(PngOptimizer.supportsResize(gammaPng))
            val geometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.gamma","texture_width":16,"texture_height":16},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[4,4],"uv_size":[8,8]}}}]}]}]}"""
            val attachable = """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.gamma"},"textures":{"default":"textures/entity/gamma"}}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/gamma.geo.json" to geometry.toByteArray(),
                "attachables/gamma.json" to attachable.toByteArray(),
                "textures/entity/gamma.png" to gammaPng,
                "textures/items/icon.png" to itemPng,
            ))

            PackOptimizer().optimizeResizedPackSet(listOf(input), listOf(output), 128)

            ZipFile(output.toFile()).use { zip ->
                val rewritten = zip.getInputStream(zip.getEntry("models/entity/gamma.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(rewritten.contains("\"texture_width\":16"))
                assertTrue(rewritten.contains("\"texture_height\":16"))
                assertTrue(rewritten.contains("\"uv\":[4,4]"))
                assertEquals(256, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/gamma.png"))).width)
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/items/icon.png"))).width)
            }
        }
    }

    @Test
    fun resizedProfileScalesTinyRoundedUvOverflowButFullProfileStaysStrict() {
        withTempDirectory { root ->
            val input = root.resolve("rounded-uv.mcpack")
            val full = root.resolve("rounded-uv-full.mcpack")
            val safe = root.resolve("rounded-uv-safe.mcpack")
            fun png(color: Int): ByteArray {
                val image = BufferedImage(304, 304, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, color)
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            fun geometry(identifier: String, uvHeight: String) =
                """{"minecraft:geometry":[{"description":{"identifier":"$identifier","texture_width":16,"texture_height":16},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[8,8],"uv_size":[8,$uvHeight]}}}]}]}]}"""
            fun attachable(identifier: String, texture: String) =
                """{"minecraft:attachable":{"description":{"geometry":{"default":"$identifier"},"textures":{"default":"$texture"}}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/entity/rounded.geo.json" to geometry("geometry.scf.rounded", "8.0001").toByteArray(),
                "models/entity/outside.geo.json" to geometry("geometry.scf.outside", "8.01").toByteArray(),
                "attachables/rounded.json" to attachable("geometry.scf.rounded", "textures/entity/rounded").toByteArray(),
                "attachables/outside.json" to attachable("geometry.scf.outside", "textures/entity/outside").toByteArray(),
                "textures/entity/rounded.png" to png(0xFF34AABB.toInt()),
                "textures/entity/outside.png" to png(0xFFBD7331.toInt()),
            ))

            PackOptimizer().optimizeForLowEnd(input, full)
            PackOptimizer().optimizeResizedPackSet(listOf(input), listOf(safe), 128)

            ZipFile(full.toFile()).use { zip ->
                assertEquals(304, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/rounded.png"))).width)
                assertEquals(304, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/outside.png"))).width)
                val geometryText = zip.getInputStream(zip.getEntry("models/entity/rounded.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(geometryText.contains("\"texture_width\":16"))
                assertTrue(geometryText.contains("\"uv_size\":[8,8.0001]"))
            }
            ZipFile(safe.toFile()).use { zip ->
                val roundedGeometry = zip.getInputStream(zip.getEntry("models/entity/rounded.geo.json"))
                    .use { it.readBytes().decodeToString() }
                val outsideGeometry = zip.getInputStream(zip.getEntry("models/entity/outside.geo.json"))
                    .use { it.readBytes().decodeToString() }
                assertTrue(roundedGeometry.contains("\"texture_width\":128"))
                assertTrue(roundedGeometry.contains("\"uv_size\":[64,64.0008]"))
                assertTrue(outsideGeometry.contains("\"texture_width\":16"))
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/rounded.png"))).width)
                assertEquals(128, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/outside.png"))).width)
            }
        }
    }

    @Test
    fun malformedJsonDisablesUnreachableAssetPruning() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/broken.json" to """{"broken":"\q"}""".toByteArray(),
                "models/entity/orphan.geo.json" to """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.orphan"}}]}""".toByteArray(),
                "textures/stitched/orphan.png" to byteArrayOf(2),
            ))

            val report = PackOptimizer().optimize(input, output)

            assertEquals(0, report.entriesFiltered)
            ZipFile(output.toFile()).use { zip ->
                assertTrue(zip.getEntry("models/entity/orphan.geo.json") != null)
                assertTrue(zip.getEntry("textures/stitched/orphan.png") != null)
            }
        }
    }

    @Test
    fun rejectsUnsafeZipPaths() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf("../escape.txt" to byteArrayOf(1)))

            assertFailsWith<IllegalArgumentException> {
                PackOptimizer().optimize(input, output)
            }
            assertTrue(Files.notExists(output))
        }
    }

    @Test
    fun packSetRewriteRejectsOutputPathCollisionsBeforeWriting() {
        withTempDirectory { root ->
            val first = root.resolve("first.mcpack")
            val second = root.resolve("second.mcpack")
            writePack(first, mapOf("manifest.json" to "{}".toByteArray()))
            writePack(second, mapOf("manifest.json" to "{}".toByteArray()))
            val firstBytes = Files.readAllBytes(first)
            val secondBytes = Files.readAllBytes(second)
            val optimizer = PackOptimizer()

            assertFailsWith<IllegalArgumentException> {
                optimizer.optimizeResizedPackSet(listOf(first, second), listOf(second, first), 128)
            }
            assertFailsWith<IllegalArgumentException> {
                optimizer.optimizeResizedPackSet(listOf(first, second), listOf(first, first), 128)
            }
            assertContentEquals(firstBytes, Files.readAllBytes(first))
            assertContentEquals(secondBytes, Files.readAllBytes(second))
        }
    }

    @Test
    fun miniProfileReducesTextureDimensionsExplicitly() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val image = BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            graphics.color = Color.MAGENTA
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.dispose()
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            writePack(input, mapOf("manifest.json" to "{}".toByteArray(), "textures/large.png" to png))

            PackOptimizer().optimizeMini(input, output, emptyList(), 256)

            ZipFile(output.toFile()).use { zip ->
                val resized = ImageIO.read(zip.getInputStream(zip.getEntry("textures/large.png")))
                assertEquals(256, resized.width)
                assertEquals(128, resized.height)
            }
        }
    }

    @Test
    fun auditReportsTextureMemoryAndFileRisk() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val mediumImage = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
            val mediumPng = ByteArrayOutputStream().also { ImageIO.write(mediumImage, "png", it) }.toByteArray()
            val smallImage = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
            val smallPng = ByteArrayOutputStream().also { ImageIO.write(smallImage, "png", it) }.toByteArray()
            val fontImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            val fontPng = ByteArrayOutputStream().also { ImageIO.write(fontImage, "png", it) }.toByteArray()
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/item_texture.json" to """{"texture_data":{"a":{"textures":"textures/a"}}}""".toByteArray(),
                "textures/a.png" to png,
                "textures/b.png" to mediumPng,
                "textures/c.png" to smallPng,
                "font/glyph_E0.png" to fontPng,
                "models/a.json" to "{\"origin\":[],\"origin\":[]}".toByteArray(),
            ))

            val report = PackAuditor().audit(input)

            assertEquals(7, report.fileCount)
            assertTrue(report.atlasReferenceScanComplete)
            assertEquals(4, report.textureFileCount)
            assertEquals((64L * 32L + 32L * 16L + 16L * 16L + 8L * 8L) * 4L, report.textureMemoryBytes)
            assertEquals("textures/a.png", report.largestTexturePath)
            assertEquals(64, report.largestTextureWidth)
            assertEquals(32, report.largestTextureHeight)
            assertEquals(64L * 32L * 4L, report.largestTextureMemoryBytes)
            assertEquals(64, report.maxTextureDimension)
            assertEquals("textures/a.png", report.largestPotentialAtlasTexturePath)
            assertEquals(64L * 32L * 4L, report.largestPotentialAtlasTextureMemoryBytes)
            assertEquals(
                listOf("textures/a.png", "textures/b.png", "font/glyph_E0.png"),
                report.textureHotspots.map { it.path },
            )
            assertEquals(0, report.potentialAtlasTexturesOverBudgetCount)
            assertEquals(1, report.modelFileCount)
            assertEquals(0, report.maxModelCubes)
            assertEquals(1, report.rootFontGlyphCount)
            assertEquals(16L * 16L * 4L, report.rootFontGlyphTextureMemoryBytes)
            assertTrue(report.warnings.any { it.contains("root font glyph") })
        }
    }

    @Test
    fun auditWarnsOnCreatorToolsHighestResolutionMipBudget() {
        withTempDirectory { root ->
            val input = root.resolve("large-mips.mcpack")
            fun png(width: Int, height: Int): ByteArray {
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, 0xff345678.toInt())
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/over_mip_budget.png" to png(2048, 1024),
                "textures/at_mip_budget.png" to png(1024, 1024),
            ))

            val report = PackAuditor().audit(input)

            assertTrue(report.warnings.any { it.contains("1 textures exceed Creator Tools' 4 MiB") })
            assertTrue(report.warnings.any { it.contains("textures/over_mip_budget.png") })
            assertFalse(report.warnings.any { it.contains("A texture is larger than 2,048 px") })
        }
    }

    @Test
    fun auditWarnsWhenRootPackIconIsNotSquare() {
        withTempDirectory { root ->
            val input = root.resolve("non-square-icon.mcpack")
            val icon = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
            val iconBytes = ByteArrayOutputStream().also { ImageIO.write(icon, "png", it) }.toByteArray()
            writePack(input, mapOf("manifest.json" to "{}".toByteArray(), "pack_icon.png" to iconBytes))

            val report = PackAuditor().audit(input)

            assertTrue(report.warnings.any { it.contains("pack_icon.png is 3x2") && it.contains("CPACKICON104") })
        }
    }

    @Test
    fun auditFlagsMissingDuplicateAndUnmeasurableRootPackIcons() {
        withTempDirectory { root ->
            val missing = root.resolve("missing-icon.mcpack")
            writePack(missing, mapOf("manifest.json" to "{}".toByteArray()))
            assertTrue(PackAuditor().audit(missing).warnings.any { it.contains("CPACKICON101") })

            val malformed = root.resolve("malformed-icon.mcpack")
            writePack(malformed, mapOf("manifest.json" to "{}".toByteArray(), "pack_icon.png" to byteArrayOf(1, 2, 3)))
            assertTrue(PackAuditor().audit(malformed).warnings.any { it.contains("could not be validated as a PNG") })

            val duplicate = root.resolve("duplicate-icon.mcpack")
            val icon = ByteArrayOutputStream().also {
                ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
            }.toByteArray()
            ZipOutputStream(Files.newOutputStream(duplicate)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("pack_icon.png"))
                zip.write(icon)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("PACK_ICON.PNG"))
                zip.write(icon)
                zip.closeEntry()
            }
            assertTrue(PackAuditor().audit(duplicate).warnings.any { it.contains("CPACKICON102") })
        }
    }

    @Test
    fun fixedPackTextureReferenceScanFailsClosedOnUnparseableReferences() {
        withTempDirectory { root ->
            val input = root.resolve("fixed-pack.mcpack")
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/external.json" to """{"textures":{"default":"textures/model/shared","enchanted":"textures/misc/glint.png#v"}}""".toByteArray(),
                "textures/terrain_texture.json" to """{"texture_data":{"block":{"textures":"textures/blocks/stone"}}}""".toByteArray(),
            ))

            assertEquals(
                setOf("textures/model/shared.png", "textures/misc/glint.png", "textures/blocks/stone.png"),
                PackAuditor().texturePathReferences(input),
            )

            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "materials/broken.material" to "{\"texture\":\"textures/model/shared\" trailing}".toByteArray(),
            ))
            assertNull(PackAuditor().texturePathReferences(input))
        }
    }

    @Test
    fun opaqueBedrockArchivesMakeMetricsIncompleteAndProfilesPassThrough() {
        withTempDirectory { root ->
            val input = root.resolve("archived.mcpack")
            val output = root.resolve("safe.mcpack")
            val image = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "entity/mob.json" to """{"textures":{"default":"textures/entity/mob"}}""".toByteArray(),
                "textures/entity/mob.png" to png,
                "__brarchive/textures.brarchive" to byteArrayOf(0x42, 0x52, 0x41, 0x52),
            ))

            val audit = PackAuditor().audit(input)
            val rewrite = PackOptimizer().optimizeMini(input, output, emptyList(), 128)

            assertTrue(audit.opaqueBedrockArchivePresent)
            assertFalse(audit.textureMetricsComplete)
            assertFalse(audit.atlasReferenceScanComplete)
            assertFalse(audit.textureHandleScanComplete)
            assertTrue(audit.warnings.any { it.contains("__brarchive data, which Fettle cannot read") })
            assertContentEquals(Files.readAllBytes(input), Files.readAllBytes(output))
            assertEquals(0, rewrite.pngEntriesOptimized)
            assertEquals(0, rewrite.entriesFiltered)
        }
    }

    @Test
    fun opaqueBedrockArchivePassThroughStillRejectsUnsafeEntries() {
        withTempDirectory { root ->
            val input = root.resolve("unsafe.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "__brarchive/textures.brarchive" to byteArrayOf(0x42, 0x52, 0x41, 0x52),
                "../outside.txt" to byteArrayOf(1),
            ))

            assertFailsWith<IllegalArgumentException> {
                PackOptimizer().optimizeMini(input, output, emptyList(), 128)
            }
            assertFalse(Files.exists(output))
        }
    }

    @Test
    fun fileCountWarningsUsePackGuidanceInsteadOfAddonOnlyLimit() {
        withTempDirectory { root ->
            fun auditFileCount(count: Int): PackAuditReport {
                val input = root.resolve("$count.mcpack")
                val entries = (0 until count).associate { "files/$it.bin" to byteArrayOf(0) }
                writePack(input, entries)
                return PackAuditor().audit(input)
            }

            val saferRange = auditFileCount(PackAuditReport.MOJANG_SAFER_PACK_FILE_COUNT + 1)
            val highCount = auditFileCount(PackAuditReport.MOJANG_HIGH_PACK_FILE_COUNT + 1)

            assertTrue(saferRange.warnings.any { it.contains("Mojang's safer range is 5,000–7,000") })
            assertTrue(saferRange.warnings.none { it.contains("run out of memory") })
            assertTrue(highCount.warnings.any { it.contains("run out of memory on some devices") })
        }
    }

    @Test
    fun auditSeparatesItemAndBlockAtlasMemoryAndRoundsLikeCreatorTools() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            fun png(width: Int, height: Int): ByteArray {
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            writePack(input, mapOf(
                "textures/item_texture.json" to """{"texture_data":{"a":{"textures":"textures/items/a"},"b":{"textures":"textures/items/b"}}}""".toByteArray(),
                "textures/terrain_texture.json" to """{"texture_data":{"block":{"textures":"textures/blocks/a"}}}""".toByteArray(),
                "attachables/item.json" to """{"textures":{"default":"textures/items/a"}}""".toByteArray(),
                "textures/items/a.png" to png(16, 16),
                "textures/items/b.png" to png(32, 16),
                "textures/blocks/a.png" to png(8, 8),
            ))

            val report = PackAuditor().audit(input)

            assertTrue(report.atlasReferenceScanComplete)
            assertEquals((16L * 16L + 32L * 16L) * 4L, report.itemAtlasTextureMemoryBytes)
            assertEquals(8L * 8L * 4L, report.blockAtlasTextureMemoryBytes)
            assertEquals(4096L, PackAuditReport.roundedAtlasAllocationBytes(report.itemAtlasTextureMemoryBytes))
            assertEquals(256L, PackAuditReport.roundedAtlasAllocationBytes(report.blockAtlasTextureMemoryBytes))
            assertEquals(64L * 1024L * 1024L,
                PackAuditReport.roundedAtlasAllocationBytes(64L * 1024L * 1024L))
            assertEquals(128L * 1024L * 1024L,
                PackAuditReport.roundedAtlasAllocationBytes(64L * 1024L * 1024L + 1))
        }
    }

    @Test
    fun jsonStringScannerRejectsStructurallyInvalidJson() {
        assertEquals(listOf("textures", "default", "textures/entity/mob"),
            JsonStringScanner.read("""{"textures":{"default":"textures/entity/mob"}}"""))
        assertNull(JsonStringScanner.read("""{"textures":{"default":"textures/entity/mob",}}"""))
    }

    @Test
    fun estimatesDistinctHandlesFromEntityAttachableParticleUiAndFlipbookReferences() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            writePack(input, mapOf(
                "textures/item_texture.json" to """{"texture_data":{"item":{"textures":"textures/items/atlas"}}}""".toByteArray(),
                "textures/terrain_texture.json" to """{"texture_data":{"block":{"textures":"textures/blocks/atlas"}}}""".toByteArray(),
                "entity/mob.json" to """{"textures":{"default":"textures/entity/mob","alternate":"textures/entity/shared"}}""".toByteArray(),
                "attachables/item.json" to """{"textures":{"default":"textures/entity/shared","item":"textures/items/custom"}}""".toByteArray(),
                "particles/spark.json" to """{"basic_render_parameters":{"texture":"textures/particles/spark"}}""".toByteArray(),
                "ui/hud.json" to """{"textures":{"icon":"textures/ui/icon"}}""".toByteArray(),
                "textures/flipbook_textures.json" to """[{"flipbook_texture":"textures/blocks/fire"}]""".toByteArray(),
            ))

            val report = PackAuditor().audit(input)

            assertTrue(report.textureHandleScanComplete)
            assertEquals(6, report.estimatedTextureHandleCount)
        }
    }

    @Test
    fun incompleteTextureHandleReferenceScanDoesNotClaimCompleteEstimate() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            writePack(input, mapOf("entity/broken.json" to """{"textures":{"default":"\q"}}""".toByteArray()))

            val report = PackAuditor().audit(input)

            assertFalse(report.textureHandleScanComplete)
            assertTrue(report.warnings.any { it.contains("Texture-handle references could not be fully scanned") })
        }
    }

    @Test
    fun auditorFlagsLargePotentialAtlasTexturesButExcludesEntityUiAndFontRasterPaths() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            fun png(width: Int, height: Int): ByteArray {
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            writePack(input, mapOf(
                "textures/item_texture.json" to """{"texture_data":{"axe":{"textures":"textures/nexo/item/axe_stitched"}}}""".toByteArray(),
                "textures/nexo/item/axe_stitched.png" to png(512, 128),
                "textures/entity/mob.png" to png(1024, 1024),
                "textures/ui/icon.png" to png(1024, 1024),
                "font/glyph_E0.png" to png(1024, 1024),
            ))

            val report = PackAuditor().audit(input)

            assertEquals("textures/nexo/item/axe_stitched.png", report.largestPotentialAtlasTexturePath)
            assertEquals(512L * 128L * 4L, report.largestPotentialAtlasTextureMemoryBytes)
            assertEquals(1, report.potentialAtlasTexturesOverBudgetCount)
            assertTrue(report.warnings.any { it.contains("256 px maximum-dimension guidance") })
        }
    }

    @Test
    fun atlasReferenceScanFailsClosedOnMalformedAttachableJson() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            writePack(input, mapOf("attachables/broken.json" to """{"textures":{"default":"\q"}}""".toByteArray()))

            val report = PackAuditor().audit(input)

            assertFalse(report.atlasReferenceScanComplete)
            assertTrue(report.warnings.any { it.contains("Atlas references could not be fully scanned") })
        }
    }

    @Test
    fun auditCountsCubeObjectsRatherThanEveryOriginProperty() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            writePack(input, mapOf(
                "models/demo.json" to """{"bones":[{"pivot":[0,0,0],"cubes":[{"origin":[0,0,0],"size":[1,1,1]},{"origin":[1,0,0],"size":[1,1,1]}]}]}""".toByteArray(),
            ))

            val report = PackAuditor().audit(input)
            assertEquals(2, report.maxModelCubes)
            assertEquals("models/demo.json", report.largestModelPath)
        }
    }

    @Test
    fun auditReportsModelFilesSkippedByTheComplexityScanBound() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val oversizedModel = ("{\"padding\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}")
                .toByteArray()
            writePack(input, mapOf("models/entity/oversized.geo.json" to oversizedModel))

            val report = PackAuditor().audit(input)

            assertEquals(1, report.modelFileCount)
            assertEquals(1, report.oversizedModelCount)
            assertTrue(report.warnings.any { it.contains("Could not scan 1 model files within the complexity limit") })
        }
    }

    @Test
    fun countsCubesAcrossBlockBonesAndScopesTheGuidanceWarning() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val cube = """{"origin":[0,0,0],"size":[1,1,1]}"""
            val blockModel = """{"bones":[{"cubes":[${List(30) { cube }.joinToString(",")}]},{"cubes":[${List(25) { cube }.joinToString(",")}]}]}"""
            val entityModel = """{"bones":[{"cubes":[${List(60) { cube }.joinToString(",")}]}]}"""
            writePack(input, mapOf(
                "models/blocks/tool.geo.json" to blockModel.toByteArray(),
                "models/entity/mob.geo.json" to entityModel.toByteArray(),
            ))

            val report = PackAuditor().audit(input)

            assertEquals(60, report.maxModelCubes)
            assertEquals("models/entity/mob.geo.json", report.largestModelPath)
            assertEquals(1, report.warnings.count { it.contains("50 cubes") })
            assertTrue(report.warnings.any {
                it.contains("1 block model file exceeds 50 cubes (maximum: 55)")
            })
        }
    }

    @Test
    fun miniProfileDownsizesJpegAndTgaTexturesToo() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("mini.mcpack")
            val jpegImage = BufferedImage(128, 64, BufferedImage.TYPE_INT_RGB)
            val jpeg = ByteArrayOutputStream().also { ImageIO.write(jpegImage, "jpg", it) }.toByteArray()
            val tga = ByteArray(18 + 128 * 64 * 3).apply {
                this[2] = 2
                this[12] = 128.toByte()
                this[14] = 64
                this[16] = 24
                this[17] = 0x20
                for (index in 18 until size step 3) {
                    this[index] = 0x22
                    this[index + 1] = 0x66
                    this[index + 2] = 0xAA.toByte()
                }
            }
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/jpeg.jpg" to jpeg,
                "textures/tga.tga" to tga,
            ))

            val report = PackOptimizer().optimizeMini(input, output, emptyList(), 64)
            val audit = PackAuditor().audit(output)

            assertEquals(2, report.otherRasterEntriesResized)
            assertEquals((64L * 32L + 64L * 32L) * 4L, audit.textureMemoryBytes)
            assertTrue(audit.textureMetricsComplete)
        }
    }

    @Test
    fun miniResizesRasterImagesAboveTheFullDecodePixelLimit() {
        val underDecodeLimitPng = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(4096, 4096, BufferedImage.TYPE_BYTE_GRAY), "png", output)
        }.toByteArray()
        val nonDivisiblePng = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(4096, 2049, BufferedImage.TYPE_BYTE_GRAY), "png", output)
        }.toByteArray()
        val png = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(4100, 4100, BufferedImage.TYPE_BYTE_GRAY), "png", output)
        }.toByteArray()
        val jpeg = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(4100, 4100, BufferedImage.TYPE_BYTE_GRAY), "jpg", output)
        }.toByteArray()

        assertContentEquals(png, PngOptimizer.optimizeIfSmaller(png))
        val resizedUnderLimitPng = ImageIO.read(PngOptimizer.optimizeIfSmaller(underDecodeLimitPng, 128).inputStream())
        val resizedNonDivisiblePng = ImageIO.read(PngOptimizer.optimizeIfSmaller(nonDivisiblePng, 128).inputStream())
        val resizedPng = ImageIO.read(PngOptimizer.optimizeIfSmaller(png, 256).inputStream())
        val resizedJpeg = ImageIO.read(RasterOptimizer.resizeJpeg(jpeg, 256).inputStream())

        assertEquals(128, resizedUnderLimitPng.width)
        assertEquals(128, resizedUnderLimitPng.height)
        assertEquals(128, resizedNonDivisiblePng.width)
        assertEquals(64, resizedNonDivisiblePng.height)
        assertTrue(maxOf(resizedPng.width, resizedPng.height) <= 256)
        assertTrue(maxOf(resizedJpeg.width, resizedJpeg.height) <= 256)
    }

    @Test
    fun safeResizeDoesNotEraseSmallGlyphsFromLargeFontAtlasCells() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val atlas = BufferedImage(4096, 4096, BufferedImage.TYPE_INT_ARGB)
            val sourceCellSize = atlas.width / 16
            val glyphs = listOf(7 to (1 to 1), 8 to (23 to 17), 9 to (49 to 49))
            for ((cell, offset) in glyphs) {
                val (offsetX, offsetY) = offset
                for (y in offsetY until offsetY + 4) for (x in offsetX until offsetX + 4) {
                    atlas.setRGB((cell % 16) * sourceCellSize + x, (cell / 16) * sourceCellSize + y, Color.WHITE.rgb)
                }
            }
            val png = ByteArrayOutputStream().also { ImageIO.write(atlas, "png", it) }.toByteArray()
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "font/glyph_E3.png" to png,
            ))

            PackOptimizer().optimizeSafe(input, output, 128)

            ZipFile(output.toFile()).use { zip ->
                val resized = ImageIO.read(zip.getInputStream(zip.getEntry("font/glyph_E3.png")))
                assertEquals(256, resized.width)
                assertEquals(256, resized.height)
                val outputCellSize = resized.width / 16
                for ((cell, _) in glyphs) {
                    var visiblePixels = 0
                    for (y in (cell / 16) * outputCellSize until (cell / 16 + 1) * outputCellSize) {
                        for (x in (cell % 16) * outputCellSize until (cell % 16 + 1) * outputCellSize) {
                            if ((resized.getRGB(x, y) ushr 24) != 0) visiblePixels++
                        }
                    }
                    assertTrue(visiblePixels > 0, "font glyph cell $cell was erased")
                }
            }
        }
    }

    @Test
    fun miniStreamsLargeRunLengthEncodedTgaIntoTheTargetRaster() {
        val width = 4_100
        val height = 4_100
        val tga = ByteArrayOutputStream().also { output ->
            val header = ByteArray(18)
            header[2] = 10
            header[12] = width.toByte()
            header[13] = (width ushr 8).toByte()
            header[14] = height.toByte()
            header[15] = (height ushr 8).toByte()
            header[16] = 24
            header[17] = 0x20
            output.write(header)
            var remaining = width * height
            while (remaining > 0) {
                val runLength = minOf(128, remaining)
                output.write(0x80 or (runLength - 1))
                output.write(0x22)
                output.write(0x66)
                output.write(0xaa)
                remaining -= runLength
            }
        }.toByteArray()

        withTempDirectory { root ->
            val input = root.resolve("large-tga.mcpack")
            val output = root.resolve("mini.mcpack")
            writePack(input, mapOf("textures/large.tga" to tga))

            val rewrite = PackOptimizer().optimizeMini(input, output, emptyList(), 256)

            assertEquals(1, rewrite.otherRasterEntriesResized)
            ZipFile(output.toFile()).use { zip ->
                val optimized = zip.getInputStream(zip.getEntry("textures/large.tga")).use { it.readAllBytes() }
                val optimizedWidth = (optimized[12].toInt() and 0xff) or ((optimized[13].toInt() and 0xff) shl 8)
                val optimizedHeight = (optimized[14].toInt() and 0xff) or ((optimized[15].toInt() and 0xff) shl 8)
                assertEquals(256, optimizedWidth)
                assertEquals(256, optimizedHeight)
                val bottomRightPixel = 18 + ((optimizedHeight * optimizedWidth - 1) * 4)
                assertEquals(0x22, optimized[bottomRightPixel].toInt() and 0xff)
                assertEquals(0x66, optimized[bottomRightPixel + 1].toInt() and 0xff)
                assertEquals(0xaa, optimized[bottomRightPixel + 2].toInt() and 0xff)
            }
        }
    }

    @Test
    fun leavesTgaAboveTheStreamingScanLimitUnchanged() {
        val oversized = ByteArray(18).apply {
            this[2] = 10
            this[12] = 9_000.toByte()
            this[13] = (9_000 ushr 8).toByte()
            this[14] = 9_000.toByte()
            this[15] = (9_000 ushr 8).toByte()
            this[16] = 24
            this[17] = 0x20
        }

        assertContentEquals(oversized, RasterOptimizer.resizeTga(oversized, 256))
    }

    @Test
    fun miniProfileLeavesAnimatedPngAndMetadataCompatible() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("safe.mcpack")
            val image = BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val metadata = """{"animation":{"frametime":2,"frames":[0,1]}}""".toByteArray()
            writePack(input, mapOf(
                "textures/animated.png" to png,
                "textures/animated.png.mcmeta" to metadata,
            ))

            val report = PackOptimizer().optimizeSafe(input, output, 256)
            val audit = PackAuditor().audit(output)

            assertEquals(0, report.pngEntriesOptimized)
            assertEquals(1, audit.pngAnimationMetadataCount)
            ZipFile(output.toFile()).use { zip ->
                assertContentEquals(png, zip.getInputStream(zip.getEntry("textures/animated.png")).readBytes())
                assertContentEquals(metadata, zip.getInputStream(zip.getEntry("textures/animated.png.mcmeta")).readBytes())
            }
        }
    }

    @Test
    fun miniProfileDoesNotResizeFlipbookMetadataTextures() {
        withTempDirectory { root ->
            val input = root.resolve("flipbook-metadata.mcpack")
            val output = root.resolve("flipbook-metadata-safe.mcpack")
            val image = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            val flipbook = """{"flipbook_textures":[{"flipbook_texture":"textures/entity/flipbook_sheet","ticks_per_frame":2,"atlas_tile":[0,0],"atlas_size":[4,4]}]}"""
            writePack(input, mapOf(
                "textures/flipbook_textures.json" to flipbook.toByteArray(),
                "textures/entity/flipbook_sheet.png" to png,
            ))

            val report = PackOptimizer().optimizeSafe(input, output, 128)

            assertEquals(0, report.pngEntriesOptimized)
            ZipFile(output.toFile()).use { zip ->
                assertEquals(1024, ImageIO.read(zip.getInputStream(zip.getEntry("textures/entity/flipbook_sheet.png"))).width)
            }
        }
    }

    @Test
    fun miniProfilePreservesPngAncillaryMetadataWhenResizing() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("safe.mcpack")
            val image = BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB)
            val png = pngWithSafeMetadata(image)
            writePack(input, mapOf("textures/metadata.png" to png))

            val report = PackOptimizer().optimizeSafe(input, output, 64)

            assertEquals(1, report.pngEntriesOptimized)
            ZipFile(output.toFile()).use { zip ->
                val resized = zip.getInputStream(zip.getEntry("textures/metadata.png")).readBytes()
                assertEquals(64, ImageIO.read(resized.inputStream()).width)
                val chunks = pngChunkTypes(resized)
                assertTrue("sRGB" in chunks)
                assertTrue("tEXt" in chunks)
                assertTrue(resized.toString(Charsets.ISO_8859_1).contains("Keep color metadata"))
            }
        }
    }

    @Test
    fun miniProfileDownsizesRunLengthEncodedTga() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("mini.mcpack")
            val tga = ByteArray(18 + 64 * 4).apply {
                this[2] = 10
                this[12] = 128.toByte()
                this[14] = 64
                this[16] = 24
                this[17] = 0x20
                for (packet in 0 until 64) {
                    val offset = 18 + packet * 4
                    this[offset] = 0xff.toByte()
                    this[offset + 1] = 0x20
                    this[offset + 2] = 0x40
                    this[offset + 3] = 0x80.toByte()
                }
            }
            writePack(input, mapOf("textures/rle.tga" to tga))

            PackOptimizer().optimizeMini(input, output, emptyList(), 64)

            val resized = PackAuditor().audit(output)
            assertEquals(64, resized.maxTextureDimension)
            assertEquals(64L * 32L * 4L, resized.textureMemoryBytes)
            assertTrue(resized.textureMetricsComplete)
        }
    }

    @Test
    fun auditMeasuresJpegAndTgaDimensionsFromBoundedHeaders() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val jpeg = byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(),
                0, 11, 8, 0, 32, 0, 64, 1, 1, 0x11, 0,
            )
            val tga = ByteArray(18).apply {
                this[2] = 2
                this[12] = 48
                this[14] = 32
                this[16] = 24
            }
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/a.jpg" to jpeg,
                "textures/b.tga" to tga,
            ))

            val report = PackAuditor().audit(input)

            assertEquals(2, report.textureFileCount)
            assertEquals((64L * 32L + 48L * 32L) * 4L, report.textureMemoryBytes)
            assertEquals("textures/a.jpg", report.largestTexturePath)
            assertTrue(report.textureMetricsComplete)
            assertEquals(0, report.unmeasuredTextureCount)
        }
    }

    @Test
    fun auditMarksMalformedAndOversizedTextureHeadersIncomplete() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val malformed = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            val oversized = ByteArray(24).apply {
                val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                signature.copyInto(this)
                this[11] = 13
                "IHDR".encodeToByteArray().copyInto(this, 12)
                this[16] = 0
                this[17] = 1
                this[18] = 0
                this[19] = 0
                this[23] = 1
            }
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "textures/bad.png" to malformed,
                "textures/oversized.png" to oversized,
            ))

            val report = PackAuditor().audit(input)

            assertFalse(report.textureMetricsComplete)
            assertEquals(2, report.unmeasuredTextureCount)
            assertTrue(report.warnings.any {
                it.contains("textures/bad.png") && it.contains("textures/oversized.png")
            })
        }
    }

    @Test
    fun rewriteRejectsOversizedDataDescriptorJsonWithoutBufferingItWhole() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val oversizedJson = ByteArray(8 * 1024 * 1024 + 1) { ' '.code.toByte() }
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "models/oversized.json" to oversizedJson,
            ))

            assertFailsWith<IOException> { PackOptimizer().optimize(input, output) }
            assertFalse(Files.exists(output))
        }
    }

    @Test
    fun lowEndResizingPreservesUiAndPbrResolutionWhileKeepingFontGlyphsReadable() {
        withTempDirectory { root ->
            fun png(width: Int, height: Int): ByteArray {
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            fun jpeg(width: Int, height: Int): ByteArray {
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
            }
            fun tga(width: Int, height: Int): ByteArray = ByteArray(18 + width * height * 3).apply {
                this[2] = 2
                this[12] = width.toByte()
                this[13] = (width ushr 8).toByte()
                this[14] = height.toByte()
                this[15] = (height ushr 8).toByte()
                this[16] = 24
                this[17] = 0x20
            }

            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            writePack(input, mapOf(
                "textures/blocks/ordinary.png" to png(512, 256),
                "textures/ui/icon.png" to png(512, 512),
                "font/glyph_E0.png" to png(512, 512),
                "textures/model/pbr.png" to png(512, 512),
                "textures/model/pbr_normal.png" to png(512, 512),
                "textures/model/pbr_mer.png" to png(512, 512),
                "textures/model/pbr.texture_set.json" to
                    """{"format_version":"1.21.30","minecraft:texture_set":{"color":"pbr","normal":"pbr_normal","metalness_emissive_roughness":"pbr_mer"}}""".toByteArray(),
                "textures/model/tga_pbr.tga" to tga(256, 128),
                "textures/model/tga_pbr_normal.tga" to tga(256, 128),
                "textures/model/tga_pbr_mer.tga" to tga(256, 128),
                "textures/model/tga_pbr.texture_set.json" to
                    """{"format_version":"1.21.30","minecraft:texture_set":{"color":"tga_pbr","normal":"tga_pbr_normal","metalness_emissive_roughness":"tga_pbr_mer"}}""".toByteArray(),
                "textures/model/jpeg_pbr.jpg" to jpeg(256, 128),
                "textures/model/jpeg_pbr.texture_set.json" to
                    """{"format_version":"1.21.30","minecraft:texture_set":{"color":"textures/model/jpeg_pbr.jpg"}}""".toByteArray(),
            ))

            PackOptimizer().optimizeSafe(input, output, 128)

            ZipFile(output.toFile()).use { zip ->
                fun dimensions(path: String): Pair<Int, Int> = ImageIO.read(zip.getInputStream(zip.getEntry(path))).let {
                    it.width to it.height
                }
                fun tgaDimensions(path: String): Pair<Int, Int> = zip.getInputStream(zip.getEntry(path)).use { input ->
                    val header = input.readNBytes(18)
                    val width = (header[12].toInt() and 0xff) or ((header[13].toInt() and 0xff) shl 8)
                    val height = (header[14].toInt() and 0xff) or ((header[15].toInt() and 0xff) shl 8)
                    width to height
                }
                assertEquals(128 to 64, dimensions("textures/blocks/ordinary.png"))
                assertEquals(512 to 512, dimensions("textures/ui/icon.png"))
                assertEquals(256 to 256, dimensions("font/glyph_E0.png"))
                assertEquals(512 to 512, dimensions("textures/model/pbr.png"))
                assertEquals(512 to 512, dimensions("textures/model/pbr_normal.png"))
                assertEquals(512 to 512, dimensions("textures/model/pbr_mer.png"))
                assertEquals(256 to 128, tgaDimensions("textures/model/tga_pbr.tga"))
                assertEquals(256 to 128, tgaDimensions("textures/model/tga_pbr_normal.tga"))
                assertEquals(256 to 128, tgaDimensions("textures/model/tga_pbr_mer.tga"))
                assertEquals(256 to 128, dimensions("textures/model/jpeg_pbr.jpg"))
            }
        }
    }

    @Test
    fun linkedLosslessOptimizationPreservesTextureSetAssetsAndTheirUvSpace() {
        withTempDirectory { root ->
            fun png(size: Int): ByteArray {
                val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            }
            fun tga(width: Int, height: Int): ByteArray = ByteArray(18 + width * height * 3).apply {
                this[2] = 2
                this[12] = width.toByte()
                this[13] = (width ushr 8).toByte()
                this[14] = height.toByte()
                this[15] = (height ushr 8).toByte()
                this[16] = 24
                this[17] = 0x20
            }

            val input = root.resolve("pbr.mcpack")
            val output = root.resolve("pbr-optimized.mcpack")
            val resizedOutput = root.resolve("pbr-resized.mcpack")
            val resizedSetOutput = root.resolve("pbr-resized-set.mcpack")
            val geometry = """{"minecraft:geometry":[{"description":{"identifier":"geometry.scf.pbr","texture_width":512,"texture_height":512},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":{"north":{"uv":[128,128],"uv_size":[64,64]}}}]}]}]}"""
            val attachable = """{"minecraft:attachable":{"description":{"geometry":{"default":"geometry.scf.pbr"},"textures":{"default":"textures/model/pbr"}}}}"""
            writePack(input, mapOf(
                "manifest.json" to "{}".toByteArray(),
                "attachables/pbr.json" to attachable.toByteArray(),
                "models/entity/pbr.geo.json" to geometry.toByteArray(),
                "textures/model/pbr.png" to png(512),
                "textures/model/pbr_normal.png" to png(512),
                "textures/model/pbr_mer.png" to png(512),
                "textures/model/pbr.texture_set.json" to
                    """{"format_version":"1.21.30","minecraft:texture_set":{"color":"pbr","normal":"pbr_normal","metalness_emissive_roughness":"pbr_mer"}}""".toByteArray(),
                "textures/model/tga_pbr.tga" to tga(256, 128),
                "textures/model/tga_pbr_normal.tga" to tga(256, 128),
                "textures/model/tga_pbr_mer.tga" to tga(256, 128),
                "textures/model/tga_pbr.texture_set.json" to
                    """{"format_version":"1.21.30","minecraft:texture_set":{"color":"tga_pbr","normal":"tga_pbr_normal","metalness_emissive_roughness":"tga_pbr_mer"}}""".toByteArray(),
            ))

            PackOptimizer().optimizeSafe(input, resizedOutput, 128)
            PackOptimizer().optimizeForLowEndSet(listOf(input), listOf(output))
            PackOptimizer().optimizeResizedPackSet(listOf(input), listOf(resizedSetOutput), 128)

            fun assertPbrAssetsAndUvRemainAligned(path: Path) {
                ZipFile(path.toFile()).use { zip ->
                    fun tgaDimensions(name: String): Pair<Int, Int> = zip.getInputStream(zip.getEntry(name)).use { input ->
                        val header = input.readNBytes(18)
                        ((header[12].toInt() and 0xff) or ((header[13].toInt() and 0xff) shl 8)) to
                            ((header[14].toInt() and 0xff) or ((header[15].toInt() and 0xff) shl 8))
                    }
                    val source = zip.getInputStream(zip.getEntry("textures/model/pbr.png")).use(ImageIO::read)
                    assertEquals(512 to 512, source.width to source.height)
                    assertTrue(zip.getEntry("textures/model/pbr.texture_set.json") != null)
                    assertEquals(256 to 128, tgaDimensions("textures/model/tga_pbr.tga"))
                    assertEquals(256 to 128, tgaDimensions("textures/model/tga_pbr_normal.tga"))
                    assertEquals(256 to 128, tgaDimensions("textures/model/tga_pbr_mer.tga"))
                    val rewrittenGeometry = zip.getInputStream(zip.getEntry("models/entity/pbr.geo.json"))
                        .use { it.readBytes().decodeToString() }
                    assertTrue(rewrittenGeometry.contains("\"texture_width\":512"))
                    assertTrue(rewrittenGeometry.contains("\"uv\":[128,128]"))
                    assertFalse(zip.entries().asSequence().any { it.name.startsWith("textures/entity/fettle/atlases/") })
                }
            }
            assertPbrAssetsAndUvRemainAligned(resizedOutput)
            assertPbrAssetsAndUvRemainAligned(output)
            assertPbrAssetsAndUvRemainAligned(resizedSetOutput)
        }
    }

    @Test
    fun malformedTextureSetPreventsResizingUnclassifiedTextures() {
        withTempDirectory { root ->
            val input = root.resolve("input.mcpack")
            val output = root.resolve("output.mcpack")
            val image = BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB)
            val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            writePack(input, mapOf(
                "textures/blocks/ordinary.png" to png,
                "textures/model/broken.texture_set.json" to "{broken".toByteArray(),
            ))

            PackOptimizer().optimizeSafe(input, output, 128)

            ZipFile(output.toFile()).use { zip ->
                val dimensions = ImageIO.read(zip.getInputStream(zip.getEntry("textures/blocks/ordinary.png")))
                assertEquals(512, dimensions.width)
                assertEquals(256, dimensions.height)
            }
        }
    }

    private fun writePack(path: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun pngWithSafeMetadata(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { imageOutput ->
                writer.output = imageOutput
                val metadata = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(image),
                    writer.defaultWriteParam,
                )
                val root = metadata.getAsTree("javax_imageio_png_1.0") as IIOMetadataNode
                val srgb = IIOMetadataNode("sRGB")
                srgb.setAttribute("renderingIntent", "Perceptual")
                root.appendChild(srgb)
                val text = IIOMetadataNode("tEXt")
                val entry = IIOMetadataNode("tEXtEntry")
                entry.setAttribute("keyword", "Description")
                entry.setAttribute("value", "Keep color metadata")
                text.appendChild(entry)
                root.appendChild(text)
                metadata.setFromTree("javax_imageio_png_1.0", root)
                writer.write(null, IIOImage(image, null, metadata), writer.defaultWriteParam)
                imageOutput.flush()
            }
            return output.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    private fun pngWithUnpairedGamma(image: BufferedImage): ByteArray {
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val type = "gAMA".toByteArray(Charsets.US_ASCII)
        val data = byteArrayOf(0, 0, 0xB1.toByte(), 0x8F.toByte())
        val checksum = CRC32().apply {
            update(type)
            update(data)
        }.value
        val chunk = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 4))
            write(type)
            write(data)
            write(byteArrayOf(
                (checksum ushr 24).toByte(), (checksum ushr 16).toByte(),
                (checksum ushr 8).toByte(), checksum.toByte(),
            ))
        }.toByteArray()
        return ByteArrayOutputStream(png.size + chunk.size).apply {
            write(png, 0, 33)
            write(chunk)
            write(png, 33, png.size - 33)
        }.toByteArray()
    }

    private fun indexedPng(source: BufferedImage): ByteArray {
        val indexes = HashMap<Int, Int>()
        val colors = ArrayList<Int>()
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val color = source.getRGB(x, y)
            indexes.getOrPut(color) {
                colors += color
                colors.lastIndex
            }
        }
        require(colors.size <= 256)
        val red = ByteArray(colors.size) { (colors[it] ushr 16).toByte() }
        val green = ByteArray(colors.size) { (colors[it] ushr 8).toByte() }
        val blue = ByteArray(colors.size) { colors[it].toByte() }
        val alpha = ByteArray(colors.size) { (colors[it] ushr 24).toByte() }
        val colorModel = IndexColorModel(8, colors.size, red, green, blue, alpha)
        val indexed = BufferedImage(source.width, source.height, BufferedImage.TYPE_BYTE_INDEXED, colorModel)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            indexed.raster.setSample(x, y, 0, indexes.getValue(source.getRGB(x, y)))
        }
        return ByteArrayOutputStream().also { ImageIO.write(indexed, "png", it) }.toByteArray()
    }

    private fun fourBitPalettePng(width: Int = 2, height: Int = 2): ByteArray {
        require(width % 2 == 0)
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
        fun writeInt(value: Int) {
            output.write(value ushr 24)
            output.write(value ushr 16)
            output.write(value ushr 8)
            output.write(value)
        }
        fun chunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            writeInt(data.size)
            output.write(typeBytes)
            output.write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }
            writeInt(crc.value.toInt())
        }

        chunk("IHDR", byteArrayOf(
            0, 0, 0, width.toByte(), 0, 0, 0, height.toByte(), 4, 3, 0, 0, 0,
        ))
        chunk("PLTE", byteArrayOf(0, 0, 0, -1, 0, 0))
        chunk("tRNS", byteArrayOf(0, -1))
        val rows = ByteArray(height * (1 + width / 2))
        for (y in 0 until height) {
            val rowStart = y * (1 + width / 2)
            rows[rowStart] = 0
            for (x in 0 until width step 2) {
                val firstIndex = (x + y) % 2
                val secondIndex = (x + 1 + y) % 2
                rows[rowStart + 1 + x / 2] = ((firstIndex shl 4) or secondIndex).toByte()
            }
        }
        val compressed = ByteArrayOutputStream().also { bytes ->
            DeflaterOutputStream(bytes).use { it.write(rows) }
        }.toByteArray()
        chunk("IDAT", compressed)
        chunk("IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun pngChunkTypes(bytes: ByteArray): Set<String> {
        val types = HashSet<String>()
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = ((bytes[offset].toLong() and 0xffL) shl 24) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
                (bytes[offset + 3].toLong() and 0xffL)
            if (offset.toLong() + length + 12 > bytes.size) break
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            types += type
            offset += 12 + length.toInt()
            if (type == "IEND") break
        }
        return types
    }

    private fun writeUvTexturePack(
        root: Path,
        uv: String,
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): Path {
        Files.createDirectories(root)
        val input = root.resolve("input.mcpack")
        val texture = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        for (y in 8 until 32) for (x in 8 until 32) texture.setRGB(x, y, 0xFF34AABB.toInt())
        val png = ByteArrayOutputStream().also { ImageIO.write(texture, "png", it) }.toByteArray()
        val model = """{"format_version":"1.21.0","minecraft:geometry":[{"description":{"identifier":"geometry.scf.item","texture_width":128,"texture_height":128},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":$uv}]}]}]}"""
        val entries = linkedMapOf(
            "manifest.json" to "{}".toByteArray(),
            "attachables/item.json" to """{"minecraft:attachable":{"description":{"identifier":"scf:item","geometry":{"default":"geometry.scf.item"},"textures":{"default":"textures/stitched/shared"},"render_controllers":["controller.render.item_default"]}}}""".toByteArray(),
            "models/entity/item.geo.json" to model.toByteArray(),
            "textures/stitched/shared.png" to png,
        )
        entries.putAll(extraEntries)
        writePack(input, entries)
        return input
    }

    private fun withTempDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("fettle-pack-test-")
        try {
            block(root)
        } finally {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
