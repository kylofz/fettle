package me.kylofz.fettle.optimizer

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackAuditorTest {
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
}
