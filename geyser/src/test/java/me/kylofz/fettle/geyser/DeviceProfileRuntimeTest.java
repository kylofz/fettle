package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.device.MemoryTierClaimStatus;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void summarizesLiveTierCoverageByDeviceOsOrGeyserPlatformWithoutRetainingPlayerIds() {
        DeviceProfileRuntime runtime = new DeviceProfileRuntime();
        GeyserConnection iosWithTier = connection(
                new FakeClientData(jwt("{\"DeviceOS\":2,\"MemoryTier\":2,\"GameVersion\":\"1.26.52\",\"GraphicsMode\":2}"), FakeDeviceOs.IOS),
                BedrockPlatform.IOS);
        GeyserConnection iosWithoutTier = connection(
                new FakeClientData(jwt("{\"DeviceOS\":2,\"GameVersion\":\"1.26.51\",\"GraphicsMode\":0}"), FakeDeviceOs.IOS),
                BedrockPlatform.IOS);
        GeyserConnection androidWithInvalidTier = connection(
                new FakeClientData(jwt("{\"DeviceOS\":1,\"MemoryTier\":4.5,\"GameVersion\":\"1.26.52\",\"GraphicsMode\":1}"), FakeDeviceOs.GOOGLE));
        GeyserConnection undecoded = connection(new FakeClientData(null), BedrockPlatform.IOS);
        GeyserConnection unknownPlatform = connection(new FakeClientData(null), BedrockPlatform.UNKNOWN);
        for (GeyserConnection connection : List.of(
                iosWithTier, iosWithoutTier, androidWithInvalidTier, undecoded, unknownPlatform)) {
            DeviceSelectionPlanner.Plan plan = runtime.plan(
                    connection, PackProfile.AUTO, null, Map.of(), auditSet());
            runtime.putSession(connection, plan.snapshot(plan.selection().profiles(), null, connection.protocolVersion()));
        }
        DeviceProfileRuntime.Summary live = runtime.summary();
        assertEquals(5, live.trackedSessions());
        assertEquals(2, live.undecodedClientData());
        assertEquals(4, live.missingMemoryTier());
        assertEquals(1, live.missingMemoryTierUnknownPlatform());
        assertEquals(Map.of("OPTIMIZE_MINI", 5), live.profileSets());
        assertEquals(5, live.selectionOutcomes().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, live.selectionOutcomes().get(
                "iOS (2), Protocol=786, GameVersion=1.26.52, GraphicsMode=2, MemoryTier=2, profiles=OPTIMIZE_MINI, packFiles=1, memoryBudget=fit, stackAtlasPolicy=fit"));
        assertEquals(1, live.selectionOutcomes().get(
                "iOS (2), Protocol=786, GameVersion=1.26.51, GraphicsMode=0, MemoryTier=unknown, profiles=OPTIMIZE_MINI, packFiles=1, memoryBudget=unknown, stackAtlasPolicy=fit"));
        assertEquals(5, live.pendingInitialization().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(Map.of(2, 1), live.memoryTiers());
        assertEquals(Map.of("iOS (2) [MemoryTier=2, GameVersion=1.26.52, Protocol=786]", 1), live.memoryTiersByPlatform());
        assertEquals(Map.of("Android (1) [INVALID, GameVersion=1.26.52, Protocol=786]", 1,
                "BedrockPlatform=IOS [UNDECODABLE, GameVersion=unknown, Protocol=786]", 1,
                "iOS (2) [MISSING, GameVersion=1.26.51, Protocol=786]", 1), live.missingMemoryTierByPlatform());

        assertNotNull(runtime.recordPostInitializationJoin(iosWithTier));
        assertNotNull(runtime.recordPostInitializationJoin(iosWithoutTier));
        assertNotNull(runtime.recordPostInitializationJoin(iosWithTier));
        assertNull(runtime.recordPostInitializationJoin(connection(new FakeClientData(jwt("{}")))));
        runtime.recordDisconnectBeforeInitialization(androidWithInvalidTier, "disconnectionScreen.resourcePack");
        runtime.removeSession(androidWithInvalidTier);
        runtime.recordDisconnectBeforeInitialization(iosWithTier, "disconnectionScreen.resourcePack");
        runtime.removeSession(iosWithTier);
        runtime.recordDisconnectBeforeInitialization(undecoded, "read timeout");
        runtime.removeSession(undecoded);
        runtime.recordDisconnectBeforeInitialization(unknownPlatform, "server closed connection");
        runtime.removeSession(unknownPlatform);
        runtime.recordDisconnectBeforeInitialization(connection(new FakeClientData(jwt("{}"))),
                "disconnectionScreen.resourcePack");

        DeviceProfileRuntime.Summary summary = runtime.summary();

        assertEquals(1, summary.trackedSessions());
        assertTrue(summary.pendingInitialization().isEmpty());
        assertEquals(5, summary.packJoinAttemptCounts().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of(
                "iOS (2), Protocol=786, GraphicsMode=2, MemoryTier=2, profiles=OPTIMIZE_MINI, packFiles=1, memoryBudget=fit, stackAtlasPolicy=fit", 1L,
                "iOS (2), Protocol=786, GraphicsMode=0, MemoryTier=unknown, profiles=OPTIMIZE_MINI, packFiles=1, memoryBudget=unknown, stackAtlasPolicy=fit", 1L),
                summary.postInitializationJoinCounts());
        assertEquals(summary.postInitializationJoinCounts().keySet(),
                summary.postInitializationAverageMillis().keySet());
        assertTrue(summary.postInitializationAverageMillis().values().stream().allMatch(value -> value >= 0));
        assertEquals(3, summary.preInitializationDisconnectCounts().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(Map.of(
                "Android (1), Protocol=786, GraphicsMode=1, MemoryTier=unknown, profiles=OPTIMIZE_MINI, packFiles=1, memoryBudget=unknown, stackAtlasPolicy=fit", 1L,
                "BedrockPlatform=IOS, Protocol=786, GraphicsMode=unknown, MemoryTier=unknown, profiles=OPTIMIZE_MINI, packFiles=1, memoryBudget=unknown, stackAtlasPolicy=fit", 1L,
                "unknown platform, Protocol=786, GraphicsMode=unknown, MemoryTier=unknown, profiles=OPTIMIZE_MINI, packFiles=1, memoryBudget=unknown, stackAtlasPolicy=fit", 1L),
                summary.preInitializationDisconnectCounts());
        assertEquals(Map.of("Android (1), Protocol=786, GraphicsMode=1, MemoryTier=unknown, profiles=OPTIMIZE_MINI, packFiles=1, memoryBudget=unknown, stackAtlasPolicy=fit", 1L),
                summary.resourcePackReasonDisconnectCounts());
        runtime.clearSessions();
        assertTrue(runtime.summary().pendingInitialization().isEmpty());
        assertTrue(runtime.summary().packJoinAttemptCounts().isEmpty());
        assertTrue(runtime.summary().preInitializationDisconnectCounts().isEmpty());
        assertTrue(runtime.summary().postInitializationJoinCounts().isEmpty());
        assertTrue(runtime.summary().postInitializationAverageMillis().isEmpty());
        assertTrue(runtime.summary().resourcePackReasonDisconnectCounts().isEmpty());
    }

    private static GeyserConnection connection(FakeClientData data) {
        return connection(data, BedrockPlatform.GOOGLE);
    }

    private static GeyserConnection connection(FakeClientData data, BedrockPlatform platform) {
        return (GeyserConnection) Proxy.newProxyInstance(
                GeyserConnection.class.getClassLoader(),
                new Class<?>[]{GeyserConnection.class, ClientDataAccess.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "fake-geyser-connection";
                    case "xuid" -> "123456789";
                    case "platform" -> platform;
                    case "protocolVersion" -> 786;
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
        return new PackSetAudit(List.of(), List.of(), List.of(), Map.of(), Map.of(PACK_ID, variants), Map.of(), List.of(),
                0, 0, 0, 0, true, 0, true, true);
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
        private final FakeDeviceOs deviceOs;

        FakeClientData(String originalString) {
            this(originalString, FakeDeviceOs.UNKNOWN);
        }

        FakeClientData(String originalString, FakeDeviceOs deviceOs) {
            this.originalString = originalString;
            this.deviceOs = deviceOs;
        }

        public String getOriginalString() {
            return originalString;
        }

        public FakeDeviceOs getDeviceOs() {
            return deviceOs;
        }
    }

    public enum FakeDeviceOs {
        UNKNOWN("Unknown"),
        GOOGLE("Android"),
        IOS("iOS");

        private final String label;

        FakeDeviceOs(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
