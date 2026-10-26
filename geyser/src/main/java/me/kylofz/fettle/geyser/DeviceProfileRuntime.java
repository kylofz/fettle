package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.device.MemoryTierClaimStatus;
import org.geysermc.geyser.api.connection.GeyserConnection;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

final class DeviceProfileRuntime {
    private final GeyserDeviceCapabilitiesReader capabilitiesReader = new GeyserDeviceCapabilitiesReader();
    private final DeviceSelectionPlanner planner = new DeviceSelectionPlanner();
    private final Map<GeyserConnection, SessionDeviceSnapshot> sessions = new ConcurrentHashMap<>();
    private final Map<GeyserConnection, Long> selectionStartedNanos = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> packJoinAttempts = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> postInitializationJoins = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> postInitializationElapsedNanos = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> preInitializationDisconnects = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> resourcePackReasonDisconnects = new ConcurrentHashMap<>();
    private final Map<DeviceProfilePolicy.Status, LongAdder> rejectedSelections = new ConcurrentHashMap<>();

    DeviceSelectionPlanner.Plan plan(
            GeyserConnection connection,
            PackProfile configuredProfile,
            PackProfile playerOverride,
            Map<Integer, Long> textureBudgetOverrides,
            PackSetAudit packSetAudit) {
        return plan(readCapabilities(connection), configuredProfile, playerOverride, textureBudgetOverrides, packSetAudit);
    }

    BedrockDeviceCapabilities readCapabilities(GeyserConnection connection) {
        return capabilitiesReader.read(connection);
    }

    DeviceSelectionPlanner.Plan plan(
            BedrockDeviceCapabilities capabilities,
            PackProfile configuredProfile,
            PackProfile playerOverride,
            Map<Integer, Long> textureBudgetOverrides,
            PackSetAudit packSetAudit) {
        return planner.plan(capabilities, configuredProfile, playerOverride, textureBudgetOverrides, packSetAudit);
    }

    void putSession(GeyserConnection connection, SessionDeviceSnapshot snapshot) {
        sessions.put(connection, snapshot);
        if (selectionStartedNanos.putIfAbsent(connection, System.nanoTime()) == null) {
            packJoinAttempts.computeIfAbsent(observationContext(snapshot), ignored -> new LongAdder()).increment();
        }
    }

    SessionDeviceSnapshot session(GeyserConnection connection) {
        return sessions.get(connection);
    }

    SessionDeviceSnapshot recordPostInitializationJoin(GeyserConnection connection) {
        SessionDeviceSnapshot snapshot = sessions.get(connection);
        if (snapshot == null) return null;
        Long started = selectionStartedNanos.remove(connection);
        if (started == null) return snapshot;
        String key = observationContext(snapshot);
        postInitializationJoins.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        postInitializationElapsedNanos.computeIfAbsent(key, ignored -> new LongAdder())
                .add(System.nanoTime() - started);
        return snapshot;
    }

    void recordDisconnectBeforeInitialization(GeyserConnection connection, String reason) {
        if (selectionStartedNanos.remove(connection) == null) return;
        SessionDeviceSnapshot snapshot = sessions.get(connection);
        if (snapshot == null) return;
        String key = observationContext(snapshot);
        preInitializationDisconnects.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        // shortcut: only identify this Geyser reason key until other reasons are verified upstream.
        if ("disconnectionScreen.resourcePack".equals(reason)) {
            resourcePackReasonDisconnects.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }
    }

    void recordRejectedSelection(DeviceProfilePolicy.Status status) {
        rejectedSelections.computeIfAbsent(status, ignored -> new LongAdder()).increment();
    }

    void removeSession(GeyserConnection connection) {
        sessions.remove(connection);
        selectionStartedNanos.remove(connection);
    }

    void clearSessions() {
        sessions.clear();
        selectionStartedNanos.clear();
        packJoinAttempts.clear();
        postInitializationJoins.clear();
        postInitializationElapsedNanos.clear();
        preInitializationDisconnects.clear();
        resourcePackReasonDisconnects.clear();
        rejectedSelections.clear();
    }

    Summary summary() {
        Map<Integer, Integer> memoryTiers = new TreeMap<>();
        Map<String, Integer> memoryTiersByPlatform = new TreeMap<>();
        Map<String, Integer> missingMemoryTierByPlatform = new TreeMap<>();
        Map<String, Integer> profileSets = new TreeMap<>();
        Map<String, Integer> selectionOutcomes = new TreeMap<>();
        Map<String, Integer> pendingInitialization = new TreeMap<>();
        Map<String, Long> packJoinAttemptCounts = new TreeMap<>();
        Map<String, Long> postInitializationJoinCounts = new TreeMap<>();
        Map<String, Long> postInitializationAverageMillis = new TreeMap<>();
        Map<String, Long> preInitializationDisconnectCounts = new TreeMap<>();
        Map<String, Long> resourcePackReasonDisconnectCounts = new TreeMap<>();
        Map<DeviceProfilePolicy.Status, Long> rejectedSelectionCounts = new EnumMap<>(DeviceProfilePolicy.Status.class);
        Map<MemoryTierClaimStatus, Integer> memoryTierFailures = new EnumMap<>(MemoryTierClaimStatus.class);
        packJoinAttempts.forEach((key, count) -> packJoinAttemptCounts.put(key, count.sum()));
        postInitializationJoins.forEach((key, count) -> postInitializationJoinCounts.put(key, count.sum()));
        postInitializationElapsedNanos.forEach((key, elapsed) -> {
            long joins = postInitializationJoinCounts.getOrDefault(key, 0L);
            if (joins > 0) postInitializationAverageMillis.put(key,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsed.sum() / joins));
        });
        preInitializationDisconnects.forEach((key, count) -> preInitializationDisconnectCounts.put(key, count.sum()));
        resourcePackReasonDisconnects.forEach((key, count) -> resourcePackReasonDisconnectCounts.put(key, count.sum()));
        rejectedSelections.forEach((status, count) -> rejectedSelectionCounts.put(status, count.sum()));
        int undecodedClientData = 0;
        int missingMemoryTier = 0;
        int missingMemoryTierUnknownPlatform = 0;
        for (Map.Entry<GeyserConnection, SessionDeviceSnapshot> entry : sessions.entrySet()) {
            SessionDeviceSnapshot snapshot = entry.getValue();
            BedrockDeviceCapabilities capabilities = snapshot.capabilities();
            String profileSet = selectedProfileSet(snapshot);
            profileSets.merge(profileSet, 1, Integer::sum);
            selectionOutcomes.merge(sessionContext(snapshot), 1, Integer::sum);
            if (selectionStartedNanos.containsKey(entry.getKey())) {
                pendingInitialization.merge(observationContext(snapshot), 1, Integer::sum);
            }
            if (!capabilities.getClientDataDecoded()) undecodedClientData++;
            Integer tier = snapshot.selectionMemoryTier();
            String platform = platformLabel(capabilities);
            String gameVersion = capabilities.getGameVersion() == null ? "unknown" : capabilities.getGameVersion();
            String protocolVersion = protocolVersionLabel(snapshot.geyserProtocolVersion());
            if (tier == null) {
                missingMemoryTier++;
                memoryTierFailures.merge(capabilities.getMemoryTierClaimStatus(), 1, Integer::sum);
                if (platform == null) missingMemoryTierUnknownPlatform++;
                else missingMemoryTierByPlatform.merge(
                        platform + " [tier claim " + capabilities.getMemoryTierClaimStatus() + ", game version "
                                + gameVersion + ", protocol " + protocolVersion + "]", 1, Integer::sum);
            } else {
                memoryTiers.merge(tier, 1, Integer::sum);
                memoryTiersByPlatform.merge(
                        (platform == null ? "unknown platform" : platform) + " [MemoryTier " + tier
                                + ", game version " + gameVersion + ", protocol " + protocolVersion + "]",
                        1, Integer::sum);
            }
        }
        return new Summary(sessions.size(), undecodedClientData, missingMemoryTier,
                missingMemoryTierUnknownPlatform, profileSets, selectionOutcomes, memoryTiers, memoryTiersByPlatform,
                pendingInitialization, packJoinAttemptCounts, postInitializationJoinCounts, postInitializationAverageMillis,
                preInitializationDisconnectCounts, resourcePackReasonDisconnectCounts, memoryTierFailures,
                missingMemoryTierByPlatform, rejectedSelectionCounts);
    }

    private static String sessionContext(SessionDeviceSnapshot snapshot) {
        return sessionContext(snapshot, true);
    }

    private static String observationContext(SessionDeviceSnapshot snapshot) {
        return sessionContext(snapshot, false);
    }

    private static String sessionContext(SessionDeviceSnapshot snapshot, boolean includeGameVersion) {
        BedrockDeviceCapabilities capabilities = snapshot.capabilities();
        String platform = platformLabel(capabilities);
        String memoryTier = snapshot.selectionMemoryTier() == null
                ? "unknown" : snapshot.selectionMemoryTier().toString();
        String gameVersion = includeGameVersion
                ? ", game version " + (capabilities.getGameVersion() == null ? "unknown" : capabilities.getGameVersion())
                : "";
        String protocolVersion = protocolVersionLabel(snapshot.geyserProtocolVersion());
        String graphicsMode = capabilities.getGraphicsMode() == null
                ? "unknown" : capabilities.getGraphicsMode().toString();
        long packFiles = 0;
        boolean packFileCountComplete = true;
        for (PackSetAudit.PackAuditSnapshot pack : snapshot.packAudits()) {
            if (pack.report() == null) packFileCountComplete = false;
            else packFiles += pack.report().getFileCount();
        }
        String memoryBudgetFit = !snapshot.budgetKnown() || !snapshot.metricsComplete()
                ? "unknown" : snapshot.withinBudget() ? "fits" : "exceeded";
        String atlasFit = !snapshot.atlasMetricsComplete()
                ? "unknown" : snapshot.withinStackAtlasPolicy() ? "fits" : "exceeded";
        String handleFit = !snapshot.textureHandleMetricsComplete()
                ? "unknown" : snapshot.withinTextureHandleLimit()
                        ? "within Fettle's 4,000-handle estimate policy"
                        : "above Fettle's 4,000-handle estimate policy";
        return "platform " + (platform == null ? "unknown" : platform) + ", protocol " + protocolVersion + gameVersion
                + ", graphics mode " + graphicsMode + ", MemoryTier " + memoryTier
                + ", selected profiles " + selectedProfileSet(snapshot)
                + ", pack files " + (packFileCountComplete ? Long.toString(packFiles) : "partial (~" + packFiles + ")")
                + ", budget " + memoryBudgetFit + ", atlas policy " + atlasFit + ", texture handles " + handleFit;
    }

    private static String protocolVersionLabel(int protocolVersion) {
        return protocolVersion > 0 ? Integer.toString(protocolVersion) : "unknown";
    }

    private static String selectedProfileSet(SessionDeviceSnapshot snapshot) {
        return snapshot.selectedProfiles().values().stream()
                .map(PackProfile::name).distinct().sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    private static String platformLabel(BedrockDeviceCapabilities capabilities) {
        Integer deviceOs = capabilities.getDeviceOs();
        if (deviceOs != null) {
            String label = capabilities.getDeviceOsLabel();
            return label == null ? "DeviceOS " + deviceOs : label + " (DeviceOS " + deviceOs + ")";
        }
        String platform = capabilities.getPlatform();
        return platform == null || platform.equalsIgnoreCase("UNKNOWN") ? null : "Bedrock platform " + platform;
    }

    record Summary(
            int trackedSessions,
            int undecodedClientData,
            int missingMemoryTier,
            int missingMemoryTierUnknownPlatform,
            Map<String, Integer> profileSets,
            Map<String, Integer> selectionOutcomes,
            Map<Integer, Integer> memoryTiers,
            Map<String, Integer> memoryTiersByPlatform,
            Map<String, Integer> pendingInitialization,
            Map<String, Long> packJoinAttemptCounts,
            Map<String, Long> postInitializationJoinCounts,
            Map<String, Long> postInitializationAverageMillis,
            Map<String, Long> preInitializationDisconnectCounts,
            Map<String, Long> resourcePackReasonDisconnectCounts,
            Map<MemoryTierClaimStatus, Integer> memoryTierFailures,
            Map<String, Integer> missingMemoryTierByPlatform,
            Map<DeviceProfilePolicy.Status, Long> rejectedSelectionCounts) {
        Summary {
            profileSets = Collections.unmodifiableMap(new TreeMap<>(profileSets));
            memoryTiers = Collections.unmodifiableMap(new TreeMap<>(memoryTiers));
            memoryTiersByPlatform = Collections.unmodifiableMap(new TreeMap<>(memoryTiersByPlatform));
            pendingInitialization = Collections.unmodifiableMap(new TreeMap<>(pendingInitialization));
            packJoinAttemptCounts = Collections.unmodifiableMap(new TreeMap<>(packJoinAttemptCounts));
            postInitializationJoinCounts = Collections.unmodifiableMap(new TreeMap<>(postInitializationJoinCounts));
            postInitializationAverageMillis = Collections.unmodifiableMap(new TreeMap<>(postInitializationAverageMillis));
            preInitializationDisconnectCounts = Collections.unmodifiableMap(new TreeMap<>(preInitializationDisconnectCounts));
            resourcePackReasonDisconnectCounts = Collections.unmodifiableMap(new TreeMap<>(resourcePackReasonDisconnectCounts));
            EnumMap<MemoryTierClaimStatus, Integer> failures = new EnumMap<>(MemoryTierClaimStatus.class);
            failures.putAll(memoryTierFailures);
            memoryTierFailures = Collections.unmodifiableMap(failures);
            missingMemoryTierByPlatform = Collections.unmodifiableMap(new TreeMap<>(missingMemoryTierByPlatform));
            selectionOutcomes = Collections.unmodifiableMap(new TreeMap<>(selectionOutcomes));
            EnumMap<DeviceProfilePolicy.Status, Long> rejected = new EnumMap<>(DeviceProfilePolicy.Status.class);
            rejected.putAll(rejectedSelectionCounts);
            rejectedSelectionCounts = Collections.unmodifiableMap(rejected);
        }
    }
}
