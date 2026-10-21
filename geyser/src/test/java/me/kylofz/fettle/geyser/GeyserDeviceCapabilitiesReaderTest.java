package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.device.MemoryTierClaimStatus;
import org.geysermc.api.util.BedrockPlatform;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeyserDeviceCapabilitiesReaderTest {
    @Test
    void readsCapabilityClaimsFromGeyserSessionData() {
        String payload = "{\"MemoryTier\":1,\"MaxViewDistance\":32,\"DeviceModel\":\"test\"}";
        FakeConnection connection = new FakeConnection(new FakeClientData(jwt(payload)));

        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader()
                .readClientData(connection, "ANDROID");

        assertEquals("ANDROID", capabilities.getPlatform());
        assertEquals(true, capabilities.getClientDataDecoded());
        assertEquals(1, capabilities.getMemoryTier());
        assertEquals(32, capabilities.getMaxViewDistance());
        assertEquals("test", capabilities.getDeviceModel());
    }

    @Test
    void readsAnIosReportedMemoryTierWithoutInferringItFromIos() {
        String payload = "{\"DeviceOS\":2,\"DeviceModel\":\"iPhone\",\"MemoryTier\":1}";
        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader()
                .readClientData(new FakeConnection(new FakeClientData(jwt(payload), "iPhone", FakeDeviceOs.IOS, null)), "IOS");

        assertEquals("IOS", capabilities.getPlatform());
        assertEquals(true, capabilities.getClientDataDecoded());
        assertEquals(2, capabilities.getDeviceOs());
        assertEquals("iOS", capabilities.getDeviceOsLabel());
        assertEquals(1, capabilities.getMemoryTier());
        assertEquals(MemoryTierClaimStatus.VALID, capabilities.getMemoryTierClaimStatus());
    }

    @Test
    void missingGeyserInternalsFailBackToUnknownCapabilities() {
        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader()
                .readClientData(new Object(), "WINDOWS");

        assertEquals("WINDOWS", capabilities.getPlatform());
        assertEquals(false, capabilities.getClientDataDecoded());
        assertEquals(MemoryTierClaimStatus.UNDECODABLE, capabilities.getMemoryTierClaimStatus());
        assertNull(capabilities.getMemoryTier());
    }

    @Test
    void usesGeyserParsedIdentityFieldsWithoutInventingMissingSpecs() {
        FakeClientData clientData = new FakeClientData(null, "Pixel 8", FakeDeviceOs.GOOGLE, "1.26.10");
        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader()
                .readClientData(new FakeConnection(clientData), "ANDROID");

        assertEquals(1, capabilities.getDeviceOs());
        assertEquals("Android", capabilities.getDeviceOsLabel());
        assertEquals("Pixel 8", capabilities.getDeviceModel());
        assertEquals("1.26.10", capabilities.getGameVersion());
        assertNull(capabilities.getMemoryTier());
    }

    @Test
    void usesPublicGeyserVersionWhenTheRawClientPayloadOmitsIt() {
        FakeClientData clientData = new FakeClientData(jwt("{\"DeviceOS\":2,\"MemoryTier\":1}"),
                "iPhone", FakeDeviceOs.IOS, null);
        GeyserConnection connection = (GeyserConnection) Proxy.newProxyInstance(
                GeyserConnection.class.getClassLoader(),
                new Class<?>[]{GeyserConnection.class, ClientDataAccess.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "platform" -> BedrockPlatform.IOS;
                    case "version" -> "1.26.52";
                    case "getClientData" -> clientData;
                    default -> null;
                });

        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader().read(connection);

        assertEquals("1.26.52", capabilities.getGameVersion());
        assertEquals(1, capabilities.getMemoryTier());
        assertEquals("iOS", capabilities.getDeviceOsLabel());
    }

    @Test
    void signedClientDataClaimsTakePrecedenceOverParsedFallbacks() {
        String payload = "{\"DeviceOS\":7,\"DeviceModel\":\"JWT model\",\"GameVersion\":\"1.26.20\"}";
        FakeClientData clientData = new FakeClientData(jwt(payload), "Parsed model", FakeDeviceOs.GOOGLE, "1.26.10");

        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader()
                .readClientData(new FakeConnection(clientData), "ANDROID");

        assertEquals(7, capabilities.getDeviceOs());
        assertNull(capabilities.getDeviceOsLabel());
        assertEquals("JWT model", capabilities.getDeviceModel());
        assertEquals("1.26.20", capabilities.getGameVersion());
    }

    private static String jwt(String payload) {
        return "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    public interface ClientDataAccess {
        FakeClientData getClientData();
    }

    public static final class FakeConnection {
        private final FakeClientData clientData;

        FakeConnection(FakeClientData clientData) {
            this.clientData = clientData;
        }

        public FakeClientData getClientData() {
            return clientData;
        }
    }

    public static final class FakeClientData {
        private final String originalString;
        private final String deviceModel;
        private final FakeDeviceOs deviceOs;
        private final String gameVersion;

        FakeClientData(String originalString) {
            this(originalString, null, FakeDeviceOs.UNKNOWN, null);
        }

        FakeClientData(String originalString, String deviceModel, FakeDeviceOs deviceOs, String gameVersion) {
            this.originalString = originalString;
            this.deviceModel = deviceModel;
            this.deviceOs = deviceOs;
            this.gameVersion = gameVersion;
        }

        public String getOriginalString() {
            return originalString;
        }

        public String getDeviceModel() {
            return deviceModel;
        }

        public FakeDeviceOs getDeviceOs() {
            return deviceOs;
        }

        public String getGameVersion() {
            return gameVersion;
        }
    }

    private enum FakeDeviceOs {
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
