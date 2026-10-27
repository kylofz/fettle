package me.kylofz.fettle.optimizer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackAuditorTest {
    @Test
    fun reportsSupportedSoundAssetCountAndArchiveBytesWithoutCallingItMemory(@TempDir directory: Path) {
        val pack = directory.resolve("sound-assets.mcpack")
        RawZipBuilder(pack).use { zip ->
            zip.addStored("sounds/ambient/rain.ogg", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            zip.addStored("sounds/effects/click.wav", ByteArrayInputStream(byteArrayOf(4, 5)))
            zip.addStored("sounds/music/theme.fsb", ByteArrayInputStream(byteArrayOf(6, 7, 8, 9)))
            zip.addStored("sounds/sound_definitions.json", ByteArrayInputStream("{}".toByteArray()))
            zip.finish()
        }

        val audit = PackAuditor().audit(pack)

        assertEquals(3, audit.soundAssetCount)
        assertEquals(9L, audit.soundAssetBytes)
        assertTrue(audit.soundAssetMetricsComplete)
    }

    @Test
    fun opaqueBedrockArchiveMakesSoundMetricsIncomplete(@TempDir directory: Path) {
        val pack = directory.resolve("opaque-sound-assets.mcpack")
        RawZipBuilder(pack).use { zip ->
            zip.addStored("__brarchive/sounds.brarchive", ByteArrayInputStream(byteArrayOf(1)))
            zip.finish()
        }

        val audit = PackAuditor().audit(pack)

        assertTrue(audit.opaqueBedrockArchivePresent)
        assertFalse(audit.soundAssetMetricsComplete)
    }

    @Test
    fun malformedRootPackIconDoesNotMakeRuntimeTextureMetricsIncomplete(@TempDir directory: Path) {
        val pack = directory.resolve("malformed-pack-icon.mcpack")
        val runtimeTexture = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        RawZipBuilder(pack).use { zip ->
            zip.addStored("pack_icon.png", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            zip.addStored("textures/a.png", ByteArrayInputStream(runtimeTexture))
            zip.finish()
        }

        val audit = PackAuditor().audit(pack)

        assertEquals(1, audit.textureFileCount)
        assertEquals(16L * 16L * 4L, audit.textureMemoryBytes)
        assertEquals(0, audit.unmeasuredTextureCount)
        assertTrue(audit.textureMetricsComplete)
        assertTrue(audit.atlasReferenceScanComplete)
        assertFalse(audit.warnings.any { it.contains("could not be measured") })
        assertTrue(audit.warnings.any { it.contains("pack_icon.png could not be validated") })
    }

    @Test
    fun duplicateZipEntryNamesAreMarkedUnsupportedForOptimization(@TempDir directory: Path) {
        val pack = directory.resolve("duplicate-paths.mcpack")
        RawZipBuilder(pack).use { zip ->
            zip.addStored("metadata.txt", ByteArrayInputStream("first".toByteArray()))
            zip.addStored("metadata.txt", ByteArrayInputStream("second".toByteArray()))
            zip.finish()
        }

        val audit = PackAuditor().audit(pack)

        assertEquals(2, audit.fileCount)
        assertTrue(audit.textureMetricsComplete)
        assertTrue(audit.optimizationUnsupported)
        assertTrue(audit.warnings.any { it.contains("duplicate ZIP entry names") })
    }

    @Test
    fun cubeCounterIgnoresCubeTextInsideStrings() {
        val json = """{"description":"not a key: \"cubes\":[{}]","bones":[{"cubes":[{},{}]}]}"""

        assertEquals(2, JsonStringScanner.countObjectsInArraysNamed(json, "cubes"))
        assertNull(JsonStringScanner.countObjectsInArraysNamed("{\"cubes\":[{},]}", "cubes"))
    }

    @Test
    fun modelAuditStopsAfterThePackWideScanBudget(@TempDir directory: Path) {
        val pack = directory.resolve("many-models.mcpack")
        val whitespaceModel = ByteArray(4 * 1024 * 1024) { ' '.code.toByte() }.also {
            it[0] = '{'.code.toByte()
            it[1] = '}'.code.toByte()
        }
        ZipOutputStream(Files.newOutputStream(pack)).use { zip ->
            repeat(17) { index ->
                zip.putNextEntry(ZipEntry("models/model_$index.json"))
                zip.write(whitespaceModel)
                zip.closeEntry()
            }
        }

        val audit = PackAuditor().audit(pack)

        assertEquals(17, audit.modelFileCount)
        assertEquals(1, audit.oversizedModelCount)
        assertTrue(audit.warnings.any { it.contains("Could not scan 1 model files") })
    }
}
