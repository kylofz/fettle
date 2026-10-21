package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.lang.reflect.Method;

final class GeyserDeviceCapabilitiesReader {
    BedrockDeviceCapabilities read(GeyserConnection connection) {
        String platform = null;
        try {
            platform = connection.platform().name();
        } catch (RuntimeException ignored) {
        }
        BedrockDeviceCapabilities capabilities = readClientData(connection, platform);
        if (capabilities.getGameVersion() != null) return capabilities;
        try {
            return capabilities.withGeyserParsedIdentity(null, null, connection.version());
        } catch (RuntimeException | LinkageError ignored) {
            return capabilities;
        }
    }

    BedrockDeviceCapabilities readClientData(Object connection, String platform) {
        // shortcut: Geyser 2.12 exposes ClientData only on its implementation; replace reflection if the API adds a getter.
        Object clientData = invokeNoArgs(connection, "getClientData");
        Object jwt = invokeNoArgs(clientData, "getOriginalString");
        Object deviceOs = invokeNoArgs(clientData, "getDeviceOs");
        // Geyser's BedrockClientData adapter maps DeviceOS IDs to enum ordinals.
        Integer deviceOsOrdinal = deviceOs instanceof Enum<?> value && value.ordinal() > 0
                ? value.ordinal() : null;
        String deviceOsLabel = deviceOs instanceof Enum<?> value && value.ordinal() > 0
                ? value.toString() : null;
        Object deviceModel = invokeNoArgs(clientData, "getDeviceModel");
        Object gameVersion = invokeNoArgs(clientData, "getGameVersion");
        return BedrockDeviceCapabilities.fromGeyserClientDataJwt(
                jwt instanceof String value ? value : null,
                platform).withGeyserParsedIdentity(
                deviceOsOrdinal,
                deviceModel instanceof String value ? value : null,
                gameVersion instanceof String value ? value : null,
                deviceOsLabel);
    }

    private Object invokeNoArgs(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
