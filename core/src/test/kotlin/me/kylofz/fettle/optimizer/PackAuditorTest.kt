package me.kylofz.fettle.optimizer

import java.io.ByteArrayInputStream
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackAuditorTest {
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
