package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;

import java.util.List;
import java.util.Map;
import java.util.UUID;

record SessionDeviceSnapshot(
        BedrockDeviceCapabilities capabilities,
        int geyserProtocolVersion,
        Integer selectionMemoryTier,
        String scaffoldingAnimationVariant,
        DeviceProfilePolicy.Status selectionStatus,
        Map<UUID, PackProfile> selectedProfiles,
        List<PackSetAudit.PackAuditSnapshot> packAudits,
        long textureBudgetBytes,
        long estimatedTextureBytes,
        long estimatedTextureFileCount,
        long estimatedItemAtlasTextureBytes,
        long estimatedBlockAtlasTextureBytes,
        long estimatedTextureHandleUpperBound,
        boolean metricsComplete,
        boolean textureHandleMetricsComplete,
        boolean withinTextureHandleLimit,
        boolean budgetKnown,
        boolean withinBudget,
        boolean atlasMetricsComplete,
        boolean withinStackAtlasPolicy,
        boolean defaultTierBudget) {
    SessionDeviceSnapshot {
        selectedProfiles = Map.copyOf(selectedProfiles);
        packAudits = List.copyOf(packAudits);
    }
}
