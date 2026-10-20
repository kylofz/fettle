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

class FettleBudgetConfigTest {
    @Test
    void loadsOperatorTestedTierBudgetsForAutomaticSelection(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config.properties");
        Properties properties = new Properties();
        properties.setProperty("profile", "auto");
        properties.setProperty("texture-budget-memory-tier-3-mib", "600");
        try (OutputStream output = Files.newOutputStream(config)) {
            properties.store(output, "tier budget test");
        }

        FettleExtension extension = new FettleExtension();
        setField(extension, "configFile", config);
        Method loadConfig = FettleExtension.class.getDeclaredMethod("loadConfig");
        loadConfig.setAccessible(true);
        assertTrue((boolean) loadConfig.invoke(extension));

        @SuppressWarnings("unchecked")
        Map<Integer, Long> budgets = (Map<Integer, Long>) getField(extension, "textureBudgetOverrides");
        assertEquals(600L * 1024L * 1024L, budgets.get(3));
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
