package me.kylofz.fettle.geyser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FettleBudgetConfigTest {
    @Test
    void leavesVibrantVisualsEnabledByDefault(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.properties");
        Files.writeString(config, "profile=auto\n");
        FettleExtension extension = new FettleExtension();
        setField(extension, "configFile", config);

        Method loadConfig = FettleExtension.class.getDeclaredMethod("loadConfig");
        loadConfig.setAccessible(true);

        assertTrue((boolean) loadConfig.invoke(extension));
        assertFalse((boolean) getField(extension, "disableVibrantVisuals"));
    }

    @Test
    void loadsOperatorTestedTierBudgetsForAutomaticSelection(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.properties");
        Properties properties = new Properties();
        properties.setProperty("profile", "auto");
        properties.setProperty("disable-vibrant-visuals", "true");
        properties.setProperty("texture-budget-memory-tier-3-mib", "600");
        properties.setProperty("texture-budget-unknown-mib", "200");
        UUID matchedPack = UUID.fromString("52ab4cb8-76d5-4f0f-8ca6-3788c23665e2");
        properties.setProperty("match-pack-uuids", matchedPack.toString());
        try (OutputStream output = Files.newOutputStream(config)) {
            properties.store(output, "tier budget test");
        }

        FettleExtension extension = new FettleExtension();
        setField(extension, "configFile", config);
        Method loadConfig = FettleExtension.class.getDeclaredMethod("loadConfig");
        loadConfig.setAccessible(true);
        assertTrue((boolean) loadConfig.invoke(extension));
        assertTrue((boolean) getField(extension, "disableVibrantVisuals"));

        @SuppressWarnings("unchecked")
        Map<Integer, Long> budgets = (Map<Integer, Long>) getField(extension, "textureBudgetOverrides");
        assertEquals(600L * 1024L * 1024L, budgets.get(3));
        assertEquals(200L * 1024L * 1024L, budgets.get(DeviceProfilePolicy.UNKNOWN_TIER_CONFIG_KEY));
        @SuppressWarnings("unchecked")
        Set<UUID> packUuids = (Set<UUID>) getField(extension, "matchPackUuids");
        assertEquals(Set.of(matchedPack), packUuids);

        Method saveConfig = FettleExtension.class.getDeclaredMethod("saveConfig");
        saveConfig.setAccessible(true);
        assertTrue((boolean) saveConfig.invoke(extension));
        Properties saved = new Properties();
        try (var input = Files.newInputStream(config)) {
            saved.load(input);
        }
        assertEquals("200", saved.getProperty("texture-budget-unknown-mib"));
        assertEquals("true", saved.getProperty("disable-vibrant-visuals"));
        assertEquals(matchedPack.toString(), saved.getProperty("match-pack-uuids"));
    }

    @Test
    void rejectsNoncanonicalPackUuidRules() {
        assertThrows(IllegalArgumentException.class,
                () -> FettleExtension.parseMatchPackUuids("not-a-uuid"));
    }

    @Test
    void malformedUnicodeEscapeDoesNotEscapeConfigSaveFailureHandling(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.properties");
        byte[] malformed = ("profile=" + '\\' + "uZZZZ\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(config, malformed);
        FettleExtension extension = new FettleExtension();
        setField(extension, "configFile", config);

        Method saveConfig = FettleExtension.class.getDeclaredMethod("saveConfig");
        saveConfig.setAccessible(true);

        assertFalse((boolean) saveConfig.invoke(extension));
        assertArrayEquals(malformed, Files.readAllBytes(config));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = FettleExtension.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = FettleExtension.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
