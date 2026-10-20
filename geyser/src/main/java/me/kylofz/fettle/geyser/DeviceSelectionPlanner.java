package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.optimizer.PackAuditReport;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DeviceSelectionPlanner {
    private static final long MIB = 1024L * 1024L;
    private static final int MAX_CACHED_SELECTIONS = 128;
    // MemoryTier is not Microsoft's performance tier; these defaults are a documented budget policy.
    private static final Map<Integer, Long> DEFAULT_MEMORY_TIER_BUDGETS = Map.of(
            0, 300L * MIB,
            1, 300L * MIB,
            2, 300L * MIB,
            3, 450L * MIB,
            4, 1200L * MIB);
    private final Map<SelectionKey, DeviceProfilePolicy.Selection> selections = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SelectionKey, DeviceProfilePolicy.Selection> eldest) {
            return size() > MAX_CACHED_SELECTIONS;
        }
    };

    Plan plan(
            BedrockDeviceCapabilities capabilities,
            PackProfile configuredProfile,
            PackProfile playerOverride,
            Map<Integer, Long> textureBudgetOverrides,
            PackSetAudit packSetAudit) {
        Integer memoryTier = capabilities == null ? null : capabilities.getMemoryTier();
        Long defaultBudget = defaultTextureBudget(memoryTier);
        Long configuredBudget = textureBudgetOverrides == null || memoryTier == null
                ? null : textureBudgetOverrides.get(memoryTier);
        Map<Integer, Long> budgets = new HashMap<>(DEFAULT_MEMORY_TIER_BUDGETS);
        if (textureBudgetOverrides != null) budgets.putAll(textureBudgetOverrides);

        boolean automatic = playerOverride == PackProfile.AUTO
                || playerOverride == null && configuredProfile == PackProfile.AUTO;
        SelectionKey key = new SelectionKey(memoryTier, configuredProfile, playerOverride, Map.copyOf(budgets),
                packSetAudit.variantReports(), packSetAudit.fixedTextureMemoryBytes(),
                packSetAudit.fixedTextureFileCount(), packSetAudit.fixedMetricsComplete(),
                packSetAudit.linkedProfileGroups());
        DeviceProfilePolicy.Selection selection;
        synchronized (selections) {
            selection = selections.get(key);
            if (selection == null) {
                selection = DeviceProfilePolicy.selectAll(
                        configuredProfile,
                        playerOverride,
                        capabilities,
                        packSetAudit.variantReports(),
                        budgets,
                        packSetAudit.fixedTextureMemoryBytes(),
                        packSetAudit.fixedTextureFileCount(),
                        packSetAudit.fixedMetricsComplete(),
                        packSetAudit.linkedProfileGroups());
                selections.put(key, selection);
            }
        }
        return new Plan(capabilities, memoryTier, automatic, packSetAudit, selection,
                automatic && configuredBudget == null && defaultBudget != null);
    }

    private record SelectionKey(
            Integer memoryTier,
            PackProfile configuredProfile,
            PackProfile playerOverride,
            Map<Integer, Long> textureBudgetOverrides,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete,
            List<Set<UUID>> linkedProfileGroups) {
    }

    static Long defaultTextureBudget(Integer memoryTier) {
        return memoryTier == null ? null : DEFAULT_MEMORY_TIER_BUDGETS.get(memoryTier);
    }

    record Plan(
            BedrockDeviceCapabilities capabilities,
            Integer selectionMemoryTier,
            boolean automatic,
            PackSetAudit packSetAudit,
            DeviceProfilePolicy.Selection selection,
            boolean defaultTierBudget) {

        SessionDeviceSnapshot snapshot(Map<UUID, PackProfile> selectedProfiles, String scaffoldingAnimation) {
            DeviceProfilePolicy.Selection delivered = DeviceProfilePolicy.summarize(
                    selectedProfiles,
                    packSetAudit.variantReports(),
                    selection.textureBudgetBytes(),
                    packSetAudit.fixedTextureMemoryBytes(),
                    packSetAudit.fixedTextureFileCount(),
                    packSetAudit.fixedMetricsComplete(),
                    selection.budgetKnown());
            DeviceProfilePolicy.TextureHandleEstimate handles = DeviceProfilePolicy.estimateTextureHandles(
                    selectedProfiles,
                    packSetAudit.variantReports(),
                    packSetAudit.fixedTextureHandleUpperBound(),
                    packSetAudit.fixedHandleMetricsComplete());
            return new SessionDeviceSnapshot(
                    capabilities,
                    selectionMemoryTier,
                    scaffoldingAnimation,
                    selectedProfiles,
                    delivered.textureBudgetBytes(),
                    delivered.estimatedTextureBytes(),
                    delivered.estimatedTextureFileCount(),
                    handles.upperBound(),
                    delivered.metricsComplete(),
                    handles.complete(),
                    delivered.budgetKnown(),
                    delivered.withinBudget(),
                    defaultTierBudget);
        }
    }
}
