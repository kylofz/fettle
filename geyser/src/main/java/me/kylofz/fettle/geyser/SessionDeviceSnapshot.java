package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;

import java.util.Map;
import java.util.UUID;

record SessionDeviceSnapshot(
        BedrockDeviceCapabilities capabilities,
        Integer selectionMemoryTier,
        String scaffoldingAnimationVariant,
        Map<UUID, PackProfile> selectedProfiles,
        long textureBudgetBytes,
        long estimatedTextureBytes,
        long estimatedTextureFileCount,
        long estimatedTextureHandleUpperBound,
        boolean metricsComplete,
        boolean textureHandleMetricsComplete,
        boolean budgetKnown,
        boolean withinBudget,
        boolean defaultTierBudget) {
    SessionDeviceSnapshot {
        selectedProfiles = Map.copyOf(selectedProfiles);
    }
}
