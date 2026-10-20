package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import org.junit.jupiter.api.Test;

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
        assertEquals(1, capabilities.getMemoryTier());
        assertEquals(32, capabilities.getMaxViewDistance());
        assertEquals("test", capabilities.getDeviceModel());
    }

    @Test
    void missingGeyserInternalsFailBackToUnknownCapabilities() {
        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader()
                .readClientData(new Object(), "WINDOWS");

        assertEquals("WINDOWS", capabilities.getPlatform());
        assertNull(capabilities.getMemoryTier());
    }

    @Test
    void usesGeyserParsedIdentityFieldsWithoutInventingMissingSpecs() {
        FakeClientData clientData = new FakeClientData(null, "Pixel 8", FakeDeviceOs.GOOGLE, "1.26.10");
        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader()
                .readClientData(new FakeConnection(clientData), "ANDROID");

        assertEquals(1, capabilities.getDeviceOs());
        assertEquals("Pixel 8", capabilities.getDeviceModel());
        assertEquals("1.26.10", capabilities.getGameVersion());
        assertNull(capabilities.getMemoryTier());
    }

    @Test
    void signedClientDataClaimsTakePrecedenceOverParsedFallbacks() {
        String payload = "{\"DeviceOS\":7,\"DeviceModel\":\"JWT model\",\"GameVersion\":\"1.26.20\"}";
        FakeClientData clientData = new FakeClientData(jwt(payload), "Parsed model", FakeDeviceOs.GOOGLE, "1.26.10");

        BedrockDeviceCapabilities capabilities = new GeyserDeviceCapabilitiesReader()
                .readClientData(new FakeConnection(clientData), "ANDROID");

        assertEquals(7, capabilities.getDeviceOs());
        assertEquals("JWT model", capabilities.getDeviceModel());
        assertEquals("1.26.20", capabilities.getGameVersion());
    }

    private static String jwt(String payload) {
        return "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
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
        UNKNOWN,
        GOOGLE
    }
}
