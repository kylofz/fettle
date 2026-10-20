package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.optimizer.PackAuditReport;
import org.geysermc.api.util.BedrockPlatform;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceProfileRuntimeTest {
    private static final long MIB = 1024L * 1024L;
    private static final UUID PACK_ID = UUID.fromString("e64da2bc-9df9-4d1a-975c-32f472350d70");

    @Test
    void geyserExposedClientDataFlowsIntoAutomaticSelectionWithoutPairing() {
        String payload = "{\"DeviceOS\":7,\"DeviceModel\":\"Example Phone\","
                + "\"GameVersion\":\"1.26.10\",\"MemoryTier\":3,\"MaxViewDistance\":32,"
                + "\"GraphicsMode\":2,\"CompatibleWithClientSideChunkGen\":true}";
        DeviceProfileRuntime runtime = new DeviceProfileRuntime();

        DeviceSelectionPlanner.Plan plan = runtime.plan(
                connection(new FakeClientData(jwt(payload))),
                PackProfile.AUTO,
                null,
                Map.of(),
                auditSet());

        assertTrue(plan.automatic());
        assertEquals(3, plan.capabilities().getMemoryTier());
        assertEquals(32, plan.capabilities().getMaxViewDistance());
        assertEquals(2, plan.capabilities().getGraphicsMode());
        assertEquals(450 * MIB, plan.selection().textureBudgetBytes());
        assertEquals(PackProfile.OPTIMIZE_SEMI, plan.selection().profiles().get(PACK_ID));
    }

    private static GeyserConnection connection(FakeClientData data) {
        return (GeyserConnection) Proxy.newProxyInstance(
                GeyserConnection.class.getClassLoader(),
                new Class<?>[]{GeyserConnection.class, ClientDataAccess.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "xuid" -> "123456789";
                    case "platform" -> BedrockPlatform.GOOGLE;
                    case "getClientData" -> data;
                    default -> null;
                });
    }

    private static PackSetAudit auditSet() {
        Map<PackProfile, PackAuditReport> variants = Map.of(
                PackProfile.FULL, audit(900 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(900 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(420 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(200 * MIB),
                PackProfile.SAFE, audit(100 * MIB));
        return new PackSetAudit(List.of(), List.of(), List.of(), Map.of(), Map.of(PACK_ID, variants), List.of(),
                0, 0, 0, true, true);
    }

    private static PackAuditReport audit(long textureBytes) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, true, 0, 0, List.of());
    }

    private static String jwt(String payload) {
        return "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    public interface ClientDataAccess {
        FakeClientData getClientData();
    }

    public static final class FakeClientData {
        private final String originalString;

        FakeClientData(String originalString) {
            this.originalString = originalString;
        }

        public String getOriginalString() {
            return originalString;
        }
    }
}
