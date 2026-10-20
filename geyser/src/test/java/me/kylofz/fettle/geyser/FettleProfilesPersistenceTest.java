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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FettleProfilesPersistenceTest {
    @Test
    void savesOverridesWithoutDeletingUnrecognizedExistingProfileData(@TempDir Path directory) throws Exception {
        Path profiles = directory.resolve("profiles.properties");
        Properties existing = new Properties();
        existing.setProperty("123456789", "SAFE");
        existing.setProperty("fettle-device-calibration.example", "tested-value");
        try (OutputStream output = Files.newOutputStream(profiles)) {
            existing.store(output, "older Fettle data");
        }

        FettleExtension extension = new FettleExtension();
        setField(extension, "profilesFile", profiles);
        @SuppressWarnings("unchecked")
        Map<String, PackProfile> overrides = (Map<String, PackProfile>) getField(extension, "playerProfiles");
        overrides.put("123456789", PackProfile.AUTO);

        Method saveProfiles = FettleExtension.class.getDeclaredMethod("saveProfiles");
        saveProfiles.setAccessible(true);
        assertTrue((boolean) saveProfiles.invoke(extension));

        Properties saved = new Properties();
        try (var input = Files.newInputStream(profiles)) {
            saved.load(input);
        }
        assertEquals("AUTO", saved.getProperty("123456789"));
        assertEquals("tested-value", saved.getProperty("fettle-device-calibration.example"));
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
