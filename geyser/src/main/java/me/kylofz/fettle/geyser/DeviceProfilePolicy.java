package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.optimizer.PackAuditReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DeviceProfilePolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long DEFAULT_UNKNOWN_TIER_BUDGET = 300L * MIB;
    static final int UNKNOWN_TIER_CONFIG_KEY = -1;
    static final int AUTO_PACK_FILE_LIMIT = PackAuditReport.MOJANG_HIGH_PACK_FILE_COUNT;
    static final int AUTO_TEXTURE_HANDLE_LIMIT = PackAuditReport.MINECRAFT_TEXTURE_HANDLE_LIMIT;
    private static final int MAX_PARETO_STATES = 4_096;
    // Fettle applies Creator Tools' per-pack thresholds to the full required-pack stack conservatively.
    private static final long STACK_ATLAS_POLICY_LIMIT_BYTES = PackAuditReport.CREATOR_TOOLS_ATLAS_MEMORY_HARD_LIMIT_BYTES;
    private static final List<PackProfile> QUALITY_ORDER = List.of(
            PackProfile.FULL, PackProfile.OPTIMIZE_FULL, PackProfile.OPTIMIZE_SEMI,
            PackProfile.OPTIMIZE_MINI, PackProfile.SAFE);

    enum Status { READY, DEGRADED, PREPARING, UNMEASURED, UNSUPPORTED, OVER_BUDGET, SEARCH_LIMIT, FAILED }

    private DeviceProfilePolicy() {
    }

    static Selection selectAll(
            PackProfile configured,
            PackProfile playerOverride,
            BedrockDeviceCapabilities capabilities,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            Map<Integer, Long> textureBudgetOverrides,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete) {
        return selectAll(configured, playerOverride, capabilities, variants, textureBudgetOverrides,
                fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, List.of(), 0, 0, true);
    }

    static Selection selectAll(
            PackProfile configured,
            PackProfile playerOverride,
            BedrockDeviceCapabilities capabilities,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            Map<Integer, Long> textureBudgetOverrides,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete,
            List<Set<UUID>> linkedProfileGroups) {
        return selectAll(configured, playerOverride, capabilities, variants, textureBudgetOverrides,
                fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, linkedProfileGroups,
                0, 0, true);
    }

    static Selection selectAll(
            PackProfile configured,
            PackProfile playerOverride,
            BedrockDeviceCapabilities capabilities,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            Map<Integer, Long> textureBudgetOverrides,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete,
            List<Set<UUID>> linkedProfileGroups,
            long fixedItemAtlasTextureMemoryBytes,
            long fixedBlockAtlasTextureMemoryBytes,
            boolean fixedAtlasMetricsComplete) {
        return selectAll(configured, playerOverride, capabilities, variants, textureBudgetOverrides,
                fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, linkedProfileGroups,
                fixedItemAtlasTextureMemoryBytes, fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete,
                0, AUTO_PACK_FILE_LIMIT);
    }

    static Selection selectAll(
            PackProfile configured,
            PackProfile playerOverride,
            BedrockDeviceCapabilities capabilities,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            Map<Integer, Long> textureBudgetOverrides,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete,
            List<Set<UUID>> linkedProfileGroups,
            long fixedItemAtlasTextureMemoryBytes,
            long fixedBlockAtlasTextureMemoryBytes,
            boolean fixedAtlasMetricsComplete,
            long fixedPackFileCount,
            long maxPackFileCount) {
        return selectAll(configured, playerOverride, capabilities, variants, textureBudgetOverrides,
                fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, linkedProfileGroups,
                fixedItemAtlasTextureMemoryBytes, fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete,
                fixedPackFileCount, maxPackFileCount, 0, true, AUTO_TEXTURE_HANDLE_LIMIT);
    }

    static Selection selectAll(
            PackProfile configured,
            PackProfile playerOverride,
            BedrockDeviceCapabilities capabilities,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            Map<Integer, Long> textureBudgetOverrides,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete,
            List<Set<UUID>> linkedProfileGroups,
            long fixedItemAtlasTextureMemoryBytes,
            long fixedBlockAtlasTextureMemoryBytes,
            boolean fixedAtlasMetricsComplete,
            long fixedPackFileCount,
            long maxPackFileCount,
            long fixedTextureHandleUpperBound,
            boolean fixedHandleMetricsComplete,
            long maxTextureHandleCount) {
        List<UUID> packIds = variants == null ? List.of() : variants.keySet().stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        List<List<UUID>> profileUnits = profileUnits(packIds, linkedProfileGroups);
        Integer memoryTier = capabilities == null ? null : capabilities.getMemoryTier();
        Long tierBudget = textureBudgetOverrides == null || memoryTier == null
                ? null : textureBudgetOverrides.get(memoryTier);
        boolean budgetKnown = tierBudget != null && tierBudget >= 0;
        long budget = budgetKnown ? tierBudget : 0;

        PackProfile fixed = playerOverride != null && playerOverride != PackProfile.AUTO
                ? playerOverride
                : playerOverride == null && configured != PackProfile.AUTO ? configured : null;
        if (fixed != null) {
            Map<UUID, PackProfile> selected = new LinkedHashMap<>();
            packIds.forEach(id -> selected.put(id, fixed));
            long fixedBudget = budgetKnown ? budget : conservativeUnknownTierBudget(textureBudgetOverrides);
            return summarize(selected, variants, fixedBudget, fixedTextureMemoryBytes, fixedTextureFileCount,
                    fixedMetricsComplete, budgetKnown, fixedItemAtlasTextureMemoryBytes,
                    fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete, fixedPackFileCount,
                    maxPackFileCount, fixedTextureHandleUpperBound, fixedHandleMetricsComplete,
                    maxTextureHandleCount);
        }

        if (!fixedMetricsComplete || !fixedAtlasMetricsComplete) {
            return summarize(Map.of(), variants, 0, fixedTextureMemoryBytes, fixedTextureFileCount,
                    fixedMetricsComplete, budgetKnown, fixedItemAtlasTextureMemoryBytes,
                    fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete, fixedPackFileCount,
                    maxPackFileCount, fixedTextureHandleUpperBound, fixedHandleMetricsComplete,
                    maxTextureHandleCount).withStatus(Status.UNMEASURED);
        }

        if (!fixedHandleMetricsComplete) {
            return summarize(Map.of(), variants, 0, fixedTextureMemoryBytes, fixedTextureFileCount,
                    fixedMetricsComplete, budgetKnown, fixedItemAtlasTextureMemoryBytes,
                    fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete, fixedPackFileCount,
                    maxPackFileCount, fixedTextureHandleUpperBound, false, maxTextureHandleCount)
                    .withStatus(Status.UNMEASURED);
        }

        long selectionBudget = budgetKnown ? budget : conservativeUnknownTierBudget(textureBudgetOverrides);
        Map<ResourceUsage, State> states = Map.of(
                new ResourceUsage(0, 0, 0, 0, 0), new State(0, 0, 0, Math.max(0, fixedTextureMemoryBytes), null, Map.of()));
        for (List<UUID> unit : profileUnits) {
            Map<ResourceUsage, State> next = new LinkedHashMap<>();
            boolean searchLimitExceeded = false;
            search:
            for (Map.Entry<ResourceUsage, State> stateEntry : states.entrySet()) {
                for (PackProfile candidate : fittingCandidates(unit, variants)) {
                    long candidateBytes = 0;
                    long candidateArchiveBytes = 0;
                    long candidateItemAtlasBytes = 0;
                    long candidateBlockAtlasBytes = 0;
                    long candidatePackFileCount = 0;
                    long candidateTextureHandles = 0;
                    int candidateQuality = 0;
                    Map<UUID, PackProfile> candidateProfiles = new LinkedHashMap<>();
                    for (UUID packId : unit) {
                        PackAuditReport report = variants.get(packId).get(candidate);
                        candidateBytes = saturatedAdd(candidateBytes, report.getTextureMemoryBytes());
                        candidateArchiveBytes = saturatedAdd(candidateArchiveBytes, report.getArchiveBytes());
                        candidateItemAtlasBytes = saturatedAdd(candidateItemAtlasBytes,
                                report.getItemAtlasTextureMemoryBytes());
                        candidateBlockAtlasBytes = saturatedAdd(candidateBlockAtlasBytes,
                                report.getBlockAtlasTextureMemoryBytes());
                        candidatePackFileCount = saturatedAdd(candidatePackFileCount, report.getFileCount());
                        candidateTextureHandles = saturatedAdd(
                                candidateTextureHandles, report.getEstimatedTextureHandleCount());
                        candidateQuality += visualQuality(candidate);
                        candidateProfiles.put(packId, candidate);
                    }
                    ResourceUsage previousUsage = stateEntry.getKey();
                    long usedTexture = saturatedAdd(previousUsage.textureBytes(), candidateBytes);
                    long usedItemAtlas = saturatedAdd(previousUsage.itemAtlasBytes(), candidateItemAtlasBytes);
                    long usedBlockAtlas = saturatedAdd(previousUsage.blockAtlasBytes(), candidateBlockAtlasBytes);
                    long usedPackFileCount = saturatedAdd(previousUsage.packFileCount(), candidatePackFileCount);
                    long usedTextureHandles = saturatedAdd(previousUsage.textureHandleCount(), candidateTextureHandles);
                    long textureMemory = saturatedAdd(Math.max(0, fixedTextureMemoryBytes), usedTexture);
                    long itemAtlasMemory = saturatedAdd(Math.max(0, fixedItemAtlasTextureMemoryBytes), usedItemAtlas);
                    long blockAtlasMemory = saturatedAdd(Math.max(0, fixedBlockAtlasTextureMemoryBytes), usedBlockAtlas);
                    if (textureMemory > selectionBudget) continue;
                    if (saturatedAdd(Math.max(0, fixedPackFileCount), usedPackFileCount) > maxPackFileCount) continue;
                    if (saturatedAdd(Math.max(0, fixedTextureHandleUpperBound), usedTextureHandles)
                            > maxTextureHandleCount) continue;
                    if (PackAuditReport.roundedAtlasAllocationBytes(itemAtlasMemory) > STACK_ATLAS_POLICY_LIMIT_BYTES
                            || PackAuditReport.roundedAtlasAllocationBytes(blockAtlasMemory) > STACK_ATLAS_POLICY_LIMIT_BYTES) continue;

                    State previous = stateEntry.getValue();
                    ResourceUsage usage = new ResourceUsage(
                            usedTexture, usedItemAtlas, usedBlockAtlas, usedPackFileCount, usedTextureHandles);
                    State incumbent = next.get(usage);
                    if (incumbent == null && next.size() >= MAX_PARETO_STATES) {
                        searchLimitExceeded = true;
                        break search;
                    }
                    int quality = previous.quality() + candidateQuality;
                    long archiveBytes = saturatedAdd(previous.archiveBytes(), candidateArchiveBytes);
                    long textureBytes = saturatedAdd(previous.textureMemoryBytes(), candidateBytes);
                    if (better(quality, textureMemory, archiveBytes, textureBytes, incumbent)) {
                        next.put(usage, new State(quality, archiveBytes, textureBytes, textureMemory,
                                previous, Map.copyOf(candidateProfiles)));
                    }
                }
            }
            if (searchLimitExceeded) {
                return summarize(Map.of(), variants, selectionBudget,
                        fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, budgetKnown,
                        fixedItemAtlasTextureMemoryBytes, fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete,
                        fixedPackFileCount, maxPackFileCount, fixedTextureHandleUpperBound,
                        fixedHandleMetricsComplete, maxTextureHandleCount).withStatus(Status.SEARCH_LIMIT);
            }
            if (next.isEmpty()) {
                return summarize(Map.of(), variants, selectionBudget,
                        fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, budgetKnown,
                        fixedItemAtlasTextureMemoryBytes, fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete,
                        fixedPackFileCount, maxPackFileCount, fixedTextureHandleUpperBound,
                        fixedHandleMetricsComplete, maxTextureHandleCount)
                        .withStatus(noFeasibleStatus(profileUnits, variants));
            }
            states = pruneDominatedStates(next);
        }

        State best = states.values().stream()
                .max(Comparator.comparingInt(State::quality)
                        .thenComparingLong(state -> -state.estimatedTextureMemoryBytes())
                        .thenComparingLong(state -> -state.archiveBytes()))
                .orElse(new State(0, 0, 0, Math.max(0, fixedTextureMemoryBytes), null, Map.of()));
        return summarize(best.profiles(), variants, selectionBudget, fixedTextureMemoryBytes, fixedTextureFileCount,
                fixedMetricsComplete, budgetKnown, fixedItemAtlasTextureMemoryBytes,
                fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete, fixedPackFileCount,
                maxPackFileCount, fixedTextureHandleUpperBound, fixedHandleMetricsComplete,
                maxTextureHandleCount);
    }

    static Long parseTextureBudgetMib(String value) {
        if (value == null) return null;
        try {
            long mib = Long.parseLong(value.trim());
            return mib >= 0 && mib <= 8192 ? mib * MIB : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static long conservativeUnknownTierBudget(Map<Integer, Long> budgets) {
        Long unknownBudget = budgets == null ? null : budgets.get(UNKNOWN_TIER_CONFIG_KEY);
        if (unknownBudget != null && unknownBudget >= 0) return unknownBudget;
        long lowestConfiguredBudget = budgets == null ? DEFAULT_UNKNOWN_TIER_BUDGET
                : budgets.values().stream().filter(value -> value != null && value >= 0)
                        .mapToLong(Long::longValue).min().orElse(DEFAULT_UNKNOWN_TIER_BUDGET);
        return Math.min(DEFAULT_UNKNOWN_TIER_BUDGET, lowestConfiguredBudget);
    }

    static boolean fits(PackAuditReport report, long textureBudget) {
        return report != null && report.getTextureMetricsComplete() && report.getTextureMemoryBytes() <= textureBudget;
    }

    private static List<PackProfile> fittingCandidates(
            List<UUID> unit,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants) {
        List<PackProfile> result = new ArrayList<>();
        for (PackProfile profile : QUALITY_ORDER) {
            if (unit.stream().allMatch(id -> variants.getOrDefault(id, Map.of()).containsKey(profile)
                    && fits(variants.get(id).get(profile), Long.MAX_VALUE)
                    && variants.get(id).get(profile).getAtlasReferenceScanComplete()
                    && variants.get(id).get(profile).getTextureHandleScanComplete()
                    && variants.get(id).get(profile).getOversizedModelCount() == 0
                    && !variants.get(id).get(profile).getOpaqueBedrockArchivePresent()
                    && !variants.get(id).get(profile).getOptimizationUnsupported())) {
                result.add(profile);
            }
        }
        return result;
    }

    private static Status noFeasibleStatus(
            List<List<UUID>> units,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants) {
        boolean incomplete = false;
        boolean unsupported = false;
        for (List<UUID> unit : units) {
            boolean hasCommonVariant = false;
            boolean hasCompleteVariant = false;
            boolean hasOpaqueVariant = false;
            boolean unitUnsupported = false;
            for (PackProfile profile : QUALITY_ORDER) {
                if (!unit.stream().allMatch(id -> variants.getOrDefault(id, Map.of()).containsKey(profile))) continue;
                hasCommonVariant = true;
                List<PackAuditReport> reports = unit.stream().map(id -> variants.get(id).get(profile)).toList();
                if (reports.stream().anyMatch(PackAuditReport::getOptimizationUnsupported)) {
                    unitUnsupported = true;
                    continue;
                }
                if (reports.stream().anyMatch(PackAuditReport::getOpaqueBedrockArchivePresent)) {
                    hasOpaqueVariant = true;
                    continue;
                }
                if (reports.stream().allMatch(report -> report.getTextureMetricsComplete()
                        && report.getAtlasReferenceScanComplete() && report.getTextureHandleScanComplete()
                        && report.getOversizedModelCount() == 0)) {
                    hasCompleteVariant = true;
                } else {
                    incomplete = true;
                }
            }
            if (!hasCommonVariant) return Status.FAILED;
            if (!hasCompleteVariant && (hasOpaqueVariant || unitUnsupported)) unsupported = true;
        }
        if (unsupported) return Status.UNSUPPORTED;
        if (incomplete) return Status.UNMEASURED;
        return Status.OVER_BUDGET;
    }

    private static List<List<UUID>> profileUnits(List<UUID> packIds, List<Set<UUID>> groups) {
        Map<UUID, UUID> parent = new HashMap<>();
        packIds.forEach(id -> parent.put(id, id));
        for (Set<UUID> group : groups) {
            UUID first = group.stream().filter(parent::containsKey).findFirst().orElse(null);
            if (first == null) continue;
            for (UUID id : group) if (parent.containsKey(id)) union(parent, first, id);
        }
        Map<UUID, List<UUID>> units = new LinkedHashMap<>();
        for (UUID id : packIds) units.computeIfAbsent(find(parent, id), ignored -> new ArrayList<>()).add(id);
        return List.copyOf(units.values());
    }

    private static UUID find(Map<UUID, UUID> parent, UUID id) {
        UUID root = parent.get(id);
        if (root.equals(id)) return root;
        root = find(parent, root);
        parent.put(id, root);
        return root;
    }

    private static void union(Map<UUID, UUID> parent, UUID left, UUID right) {
        UUID leftRoot = find(parent, left);
        UUID rightRoot = find(parent, right);
        if (!leftRoot.equals(rightRoot)) parent.put(rightRoot, leftRoot);
    }

    private static boolean better(
            int quality,
            long estimatedTextureMemoryBytes,
            long archiveBytes,
            long textureBytes,
            State incumbent) {
        if (incumbent == null) return true;
        if (quality != incumbent.quality()) return quality > incumbent.quality();
        if (estimatedTextureMemoryBytes != incumbent.estimatedTextureMemoryBytes()) {
            return estimatedTextureMemoryBytes < incumbent.estimatedTextureMemoryBytes();
        }
        if (textureBytes != incumbent.textureMemoryBytes()) return textureBytes < incumbent.textureMemoryBytes();
        return archiveBytes < incumbent.archiveBytes();
    }

    private static Map<ResourceUsage, State> pruneDominatedStates(Map<ResourceUsage, State> states) {
        List<Map.Entry<ResourceUsage, State>> ordered = new ArrayList<>(states.entrySet());
        Map<ResourceUsage, State> frontier = new LinkedHashMap<>();
        for (Map.Entry<ResourceUsage, State> candidate : ordered) {
            boolean dominated = frontier.entrySet().stream().anyMatch(incumbent ->
                    dominates(incumbent.getKey(), incumbent.getValue(), candidate.getKey(), candidate.getValue()));
            if (dominated) continue;
            frontier.entrySet().removeIf(incumbent ->
                    dominates(candidate.getKey(), candidate.getValue(), incumbent.getKey(), incumbent.getValue()));
            frontier.put(candidate.getKey(), candidate.getValue());
        }
        return frontier;
    }

    private static boolean dominates(ResourceUsage leftUsage, State left, ResourceUsage rightUsage, State right) {
        if (left.quality() < right.quality()
                || leftUsage.textureBytes() > rightUsage.textureBytes()
                || leftUsage.itemAtlasBytes() > rightUsage.itemAtlasBytes()
                || leftUsage.blockAtlasBytes() > rightUsage.blockAtlasBytes()
                || leftUsage.packFileCount() > rightUsage.packFileCount()
                || leftUsage.textureHandleCount() > rightUsage.textureHandleCount()) return false;
        return left.quality() > right.quality() || left.archiveBytes() <= right.archiveBytes();
    }

    private static int visualQuality(PackProfile profile) {
        return switch (profile) {
            case FULL, OPTIMIZE_FULL -> 3;
            case OPTIMIZE_SEMI -> 2;
            case OPTIMIZE_MINI -> 1;
            case SAFE -> 0;
            default -> -1;
        };
    }

    static Selection summarize(
            Map<UUID, PackProfile> selected,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            long budget,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete,
            boolean budgetKnown,
            long fixedItemAtlasTextureMemoryBytes,
            long fixedBlockAtlasTextureMemoryBytes,
            boolean fixedAtlasMetricsComplete) {
        return summarize(selected, variants, budget, fixedTextureMemoryBytes, fixedTextureFileCount,
                fixedMetricsComplete, budgetKnown, fixedItemAtlasTextureMemoryBytes,
                fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete, 0, AUTO_PACK_FILE_LIMIT);
    }

    static Selection summarize(
            Map<UUID, PackProfile> selected,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            long budget,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete,
            boolean budgetKnown,
            long fixedItemAtlasTextureMemoryBytes,
            long fixedBlockAtlasTextureMemoryBytes,
            boolean fixedAtlasMetricsComplete,
            long fixedPackFileCount,
            long maxPackFileCount) {
        return summarize(selected, variants, budget, fixedTextureMemoryBytes, fixedTextureFileCount,
                fixedMetricsComplete, budgetKnown, fixedItemAtlasTextureMemoryBytes,
                fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete, fixedPackFileCount,
                maxPackFileCount, 0, true, AUTO_TEXTURE_HANDLE_LIMIT);
    }

    static Selection summarize(
            Map<UUID, PackProfile> selected,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            long budget,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            boolean fixedMetricsComplete,
            boolean budgetKnown,
            long fixedItemAtlasTextureMemoryBytes,
            long fixedBlockAtlasTextureMemoryBytes,
            boolean fixedAtlasMetricsComplete,
            long fixedPackFileCount,
            long maxPackFileCount,
            long fixedTextureHandleUpperBound,
            boolean fixedHandleMetricsComplete,
            long maxTextureHandleCount) {
        long textureMemory = Math.max(0, fixedTextureMemoryBytes);
        long textureFileCount = Math.max(0, fixedTextureFileCount);
        long packFileCount = Math.max(0, fixedPackFileCount);
        long itemAtlasMemory = Math.max(0, fixedItemAtlasTextureMemoryBytes);
        long blockAtlasMemory = Math.max(0, fixedBlockAtlasTextureMemoryBytes);
        boolean complete = fixedMetricsComplete;
        boolean atlasComplete = fixedAtlasMetricsComplete;
        boolean unsupported = false;
        TextureHandleEstimate handles = estimateTextureHandles(
                selected, variants, fixedTextureHandleUpperBound, fixedHandleMetricsComplete);
        for (Map.Entry<UUID, PackProfile> entry : selected.entrySet()) {
            PackAuditReport report = variants == null ? null
                    : variants.getOrDefault(entry.getKey(), Map.of()).get(entry.getValue());
            if (report == null) {
                complete = false;
            } else {
                unsupported |= report.getOptimizationUnsupported() || report.getOpaqueBedrockArchivePresent();
                textureFileCount = saturatedAdd(textureFileCount, report.getTextureFileCount());
                packFileCount = saturatedAdd(packFileCount, report.getFileCount());
                textureMemory = saturatedAdd(textureMemory, report.getTextureMemoryBytes());
                if (!report.getTextureMetricsComplete() || !report.getTextureHandleScanComplete()
                        || report.getOversizedModelCount() > 0 || report.getOptimizationUnsupported()
                        || report.getOpaqueBedrockArchivePresent()) complete = false;
                itemAtlasMemory = saturatedAdd(itemAtlasMemory, report.getItemAtlasTextureMemoryBytes());
                blockAtlasMemory = saturatedAdd(blockAtlasMemory, report.getBlockAtlasTextureMemoryBytes());
                if (!report.getAtlasReferenceScanComplete() || !report.getTextureMetricsComplete()) atlasComplete = false;
            }
        }
        boolean atlasWithinPolicy = atlasComplete
                && PackAuditReport.roundedAtlasAllocationBytes(itemAtlasMemory) <= STACK_ATLAS_POLICY_LIMIT_BYTES
                && PackAuditReport.roundedAtlasAllocationBytes(blockAtlasMemory) <= STACK_ATLAS_POLICY_LIMIT_BYTES;
        boolean withinPackFileLimit = packFileCount <= maxPackFileCount;
        boolean withinTextureHandleLimit = handles.complete() && handles.upperBound() <= maxTextureHandleCount;
        boolean satisfiesPolicy = complete && atlasWithinPolicy && textureMemory <= budget
                && withinPackFileLimit && withinTextureHandleLimit;
        boolean withinBudget = budgetKnown && complete && textureMemory <= budget;
        Status status = unsupported ? Status.UNSUPPORTED
                : !complete || !atlasComplete || !handles.complete() ? Status.UNMEASURED
                : !satisfiesPolicy ? Status.OVER_BUDGET
                : !budgetKnown || selected.values().stream().anyMatch(profile -> profile != PackProfile.FULL)
                        ? Status.DEGRADED : Status.READY;
        return new Selection(Map.copyOf(selected), budget, textureMemory, textureFileCount,
                packFileCount, maxPackFileCount, withinPackFileLimit, handles.upperBound(),
                maxTextureHandleCount, handles.complete(), withinTextureHandleLimit, complete,
                budgetKnown, withinBudget, itemAtlasMemory, blockAtlasMemory,
                atlasComplete, atlasWithinPolicy, satisfiesPolicy, status);
    }

    static TextureHandleEstimate estimateTextureHandles(
            Map<UUID, PackProfile> selected,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants,
            long fixedHandleUpperBound,
            boolean fixedMetricsComplete) {
        long upperBound = Math.max(0, fixedHandleUpperBound);
        boolean complete = fixedMetricsComplete;
        for (Map.Entry<UUID, PackProfile> entry : selected.entrySet()) {
            PackAuditReport report = variants == null ? null
                    : variants.getOrDefault(entry.getKey(), Map.of()).get(entry.getValue());
            if (report == null) {
                complete = false;
            } else {
                upperBound = saturatedAdd(upperBound, report.getEstimatedTextureHandleCount());
                if (!report.getTextureHandleScanComplete()) complete = false;
            }
        }
        return new TextureHandleEstimate(upperBound, complete);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    record Selection(
            Map<UUID, PackProfile> profiles,
            long textureBudgetBytes,
            long estimatedTextureBytes,
            long estimatedTextureFileCount,
            long requiredPackFileCount,
            long maxPackFileCount,
            boolean withinPackFileLimit,
            long estimatedTextureHandleUpperBound,
            long maxTextureHandleCount,
            boolean textureHandleMetricsComplete,
            boolean withinTextureHandleLimit,
            boolean metricsComplete,
            boolean budgetKnown,
            boolean withinBudget,
            long estimatedItemAtlasTextureBytes,
            long estimatedBlockAtlasTextureBytes,
            boolean atlasMetricsComplete,
            boolean withinStackAtlasPolicy,
            boolean satisfiesPolicy,
            Status status) {
        Selection withStatus(Status status) {
            boolean selectable = status == Status.READY || status == Status.DEGRADED;
            boolean measured = metricsComplete && status != Status.UNMEASURED
                    && status != Status.UNSUPPORTED && status != Status.PREPARING
                    && status != Status.SEARCH_LIMIT && status != Status.FAILED;
            boolean atlasMeasured = atlasMetricsComplete && status != Status.UNMEASURED
                    && status != Status.UNSUPPORTED && status != Status.PREPARING
                    && status != Status.SEARCH_LIMIT && status != Status.FAILED;
            boolean hasSelectedPackStack = !profiles.isEmpty();
            return new Selection(profiles, textureBudgetBytes, estimatedTextureBytes, estimatedTextureFileCount,
                    requiredPackFileCount, maxPackFileCount, withinPackFileLimit && measured && hasSelectedPackStack,
                    estimatedTextureHandleUpperBound, maxTextureHandleCount,
                    textureHandleMetricsComplete && measured,
                    withinTextureHandleLimit && measured && hasSelectedPackStack,
                    measured, budgetKnown, withinBudget && measured && hasSelectedPackStack,
                    estimatedItemAtlasTextureBytes, estimatedBlockAtlasTextureBytes, atlasMeasured,
                    withinStackAtlasPolicy && atlasMeasured,
                    satisfiesPolicy && measured && atlasMeasured && selectable, status);
        }
    }

    record TextureHandleEstimate(long upperBound, boolean complete) {
    }

    private record ResourceUsage(
            long textureBytes, long itemAtlasBytes, long blockAtlasBytes, long packFileCount, long textureHandleCount) {
    }

    private static final class State {
        private final int quality;
        private final long archiveBytes;
        private final long textureMemoryBytes;
        private final long estimatedTextureMemoryBytes;
        private final State previous;
        private final Map<UUID, PackProfile> selectedProfiles;

        private State(int quality, long archiveBytes, long textureMemoryBytes,
                      long estimatedTextureMemoryBytes,
                      State previous, Map<UUID, PackProfile> selectedProfiles) {
            this.quality = quality;
            this.archiveBytes = archiveBytes;
            this.textureMemoryBytes = textureMemoryBytes;
            this.estimatedTextureMemoryBytes = estimatedTextureMemoryBytes;
            this.previous = previous;
            this.selectedProfiles = selectedProfiles;
        }

        private int quality() { return quality; }
        private long archiveBytes() { return archiveBytes; }
        private long textureMemoryBytes() { return textureMemoryBytes; }
        private long estimatedTextureMemoryBytes() { return estimatedTextureMemoryBytes; }

        private Map<UUID, PackProfile> profiles() {
            Map<UUID, PackProfile> profiles = new LinkedHashMap<>();
            for (State state = this; state != null; state = state.previous) {
                profiles.putAll(state.selectedProfiles);
            }
            return Map.copyOf(profiles);
        }
    }
}
