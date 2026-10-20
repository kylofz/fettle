package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class DeviceProfileRuntime {
    private final GeyserDeviceCapabilitiesReader capabilitiesReader = new GeyserDeviceCapabilitiesReader();
    private final DeviceSelectionPlanner planner = new DeviceSelectionPlanner();
    private final Map<GeyserConnection, SessionDeviceSnapshot> sessions = new ConcurrentHashMap<>();

    DeviceSelectionPlanner.Plan plan(
            GeyserConnection connection,
            PackProfile configuredProfile,
            PackProfile playerOverride,
            Map<Integer, Long> textureBudgetOverrides,
            PackSetAudit packSetAudit) {
        BedrockDeviceCapabilities capabilities = capabilitiesReader.read(connection);
        return planner.plan(capabilities, configuredProfile, playerOverride, textureBudgetOverrides, packSetAudit);
    }

    void putSession(GeyserConnection connection, SessionDeviceSnapshot snapshot) {
        sessions.put(connection, snapshot);
    }

    SessionDeviceSnapshot session(GeyserConnection connection) {
        return sessions.get(connection);
    }

    void removeSession(GeyserConnection connection) {
        sessions.remove(connection);
    }

    void clearSessions() {
        sessions.clear();
    }
}
