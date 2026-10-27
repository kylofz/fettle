package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.pack.ResourcePackManifest.Version;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackCompatibilityTest {
    @Test
    void comparesNumericVersionComponentsAndAcceptsPreviewSuffixes() {
        assertEquals(-1, FettleExtension.compareClientVersion("1.21.9", 1, 21, 10));
        assertEquals(0, FettleExtension.compareClientVersion("1.26.40-preview", 1, 26, 40));
        assertEquals(1, FettleExtension.compareClientVersion("1.26.41", 1, 26, 40));
        assertNull(FettleExtension.compareClientVersion("unknown", 1, 26, 40));
    }

    @Test
    void rejectsDeclaredMinimumAboveKnownClientVersion() {
        String issue = FettleExtension.compatibilityIssue("1.20.80", version(1, 21, 0), false);

        assertNotNull(issue);
        assertTrue(issue.contains("min_engine_version 1.21.0"));
    }

    @Test
    void requiresKnownCompatibleClientVersionForOpaqueArchives() {
        assertNotNull(FettleExtension.compatibilityIssue("unknown", null, true));
        assertNotNull(FettleExtension.compatibilityIssue("1.26.30", null, true));
        assertNull(FettleExtension.compatibilityIssue("1.26.40", null, true));
        assertNull(FettleExtension.compatibilityIssue("1.27.0", null, true));
    }

    private static Version version(int major, int minor, int patch) {
        return new Version() {
            @Override
            public int major() {
                return major;
            }

            @Override
            public int minor() {
                return minor;
            }

            @Override
            public int patch() {
                return patch;
            }

            @Override
            public String toString() {
                return major + "." + minor + "." + patch;
            }
        };
    }
}
