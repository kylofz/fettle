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
    // Fettle applies Creator Tools' per-pack threshold to the summed required-pack stack conservatively.
    private static final long STACK_ATLAS_POLICY_LIMIT_BYTES = PackAuditReport.CREATOR_TOOLS_ATLAS_MEMORY_HARD_LIMIT_BYTES;
    private static final List<PackProfile> QUALITY_ORDER = List.of(
            PackProfile.FULL, PackProfile.OPTIMIZE_FULL, PackProfile.OPTIMIZE_SEMI,
            PackProfile.OPTIMIZE_MINI, PackProfile.SAFE);

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
            return summarize(selected, variants, budget, fixedTextureMemoryBytes, fixedTextureFileCount,
                    fixedMetricsComplete, budgetKnown, fixedItemAtlasTextureMemoryBytes,
                    fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete);
        }

        long selectionBudget = budgetKnown ? budget : conservativeUnknownTierBudget(textureBudgetOverrides);
        boolean fixedAtlasWithinLimit = fixedItemAtlasTextureMemoryBytes <= STACK_ATLAS_POLICY_LIMIT_BYTES
                && fixedBlockAtlasTextureMemoryBytes <= STACK_ATLAS_POLICY_LIMIT_BYTES;
        // Incomplete fixed-pack scans still provide a lower bound; don't stack measured target atlases past the hard cap.
        long availableItemAtlas = Math.max(0, STACK_ATLAS_POLICY_LIMIT_BYTES - Math.max(0, fixedItemAtlasTextureMemoryBytes));
        long availableBlockAtlas = Math.max(0, STACK_ATLAS_POLICY_LIMIT_BYTES - Math.max(0, fixedBlockAtlasTextureMemoryBytes));
        long fixedResourceMemory = estimatedResourceMemory(
                Math.max(0, fixedTextureMemoryBytes),
                Math.max(0, fixedItemAtlasTextureMemoryBytes),
                Math.max(0, fixedBlockAtlasTextureMemoryBytes));
        Map<ResourceUsage, State> states = Map.of(
                new ResourceUsage(0, 0, 0), new State(0, 0, 0, fixedResourceMemory, null, Map.of()));
        for (List<UUID> unit : profileUnits) {
            Map<ResourceUsage, State> next = new HashMap<>();
            for (Map.Entry<ResourceUsage, State> stateEntry : states.entrySet()) {
                for (PackProfile candidate : fittingCandidates(unit, variants)) {
                    long candidateBytes = 0;
                    long candidateArchiveBytes = 0;
                    long candidateItemAtlasBytes = 0;
                    long candidateBlockAtlasBytes = 0;
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
                        candidateQuality += visualQuality(candidate);
                        candidateProfiles.put(packId, candidate);
                    }
                    ResourceUsage previousUsage = stateEntry.getKey();
                    long usedTexture = saturatedAdd(previousUsage.textureBytes(), candidateBytes);
                    long usedItemAtlas = saturatedAdd(previousUsage.itemAtlasBytes(), candidateItemAtlasBytes);
                    long usedBlockAtlas = saturatedAdd(previousUsage.blockAtlasBytes(), candidateBlockAtlasBytes);
                    long resourceMemory = estimatedResourceMemory(
                            saturatedAdd(Math.max(0, fixedTextureMemoryBytes), usedTexture),
                            saturatedAdd(Math.max(0, fixedItemAtlasTextureMemoryBytes), usedItemAtlas),
                            saturatedAdd(Math.max(0, fixedBlockAtlasTextureMemoryBytes), usedBlockAtlas));
                    if (resourceMemory > selectionBudget) continue;
                    if (fixedAtlasMetricsComplete && !fixedAtlasWithinLimit) continue;
                    if (usedItemAtlas > availableItemAtlas || usedBlockAtlas > availableBlockAtlas) continue;

                    State previous = stateEntry.getValue();
                    ResourceUsage usage = new ResourceUsage(usedTexture, usedItemAtlas, usedBlockAtlas);
                    State incumbent = next.get(usage);
                    int quality = previous.quality() + candidateQuality;
                    long archiveBytes = saturatedAdd(previous.archiveBytes(), candidateArchiveBytes);
                    long textureBytes = saturatedAdd(previous.textureMemoryBytes(), candidateBytes);
                    if (better(quality, resourceMemory, archiveBytes, textureBytes, incumbent)) {
                        next.put(usage, new State(quality, archiveBytes, textureBytes, resourceMemory,
                                previous, Map.copyOf(candidateProfiles)));
                    }
                }
            }
            if (next.isEmpty()) {
                return summarize(lowestMemoryProfiles(profileUnits, variants), variants, selectionBudget,
                        fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, budgetKnown,
                        fixedItemAtlasTextureMemoryBytes, fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete);
            }
            states = pruneDominatedStates(next);
        }

        State best = states.values().stream()
                .max(Comparator.comparingInt(State::quality)
                        .thenComparingLong(state -> -state.estimatedResourceMemoryBytes())
                        .thenComparingLong(state -> -state.archiveBytes()))
                .orElse(new State(0, 0, 0, fixedResourceMemory, null, Map.of()));
        return summarize(best.profiles(), variants, selectionBudget, fixedTextureMemoryBytes, fixedTextureFileCount,
                fixedMetricsComplete, budgetKnown, fixedItemAtlasTextureMemoryBytes,
                fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete);
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
        if (budgets == null || budgets.isEmpty()) return DEFAULT_UNKNOWN_TIER_BUDGET;
        return budgets.values().stream().filter(value -> value != null && value >= 0)
                .mapToLong(Long::longValue).min().orElse(DEFAULT_UNKNOWN_TIER_BUDGET);
    }

    private static long estimatedResourceMemory(long textureBytes, long itemAtlasBytes, long blockAtlasBytes) {
        return saturatedAdd(textureBytes, saturatedAdd(
                PackAuditReport.roundedAtlasAllocationBytes(itemAtlasBytes),
                PackAuditReport.roundedAtlasAllocationBytes(blockAtlasBytes)));
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
                    && variants.get(id).get(profile).getAtlasReferenceScanComplete())) {
                result.add(profile);
            }
        }
        return result;
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

    private static Map<UUID, PackProfile> lowestMemoryProfiles(
            List<List<UUID>> units,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants) {
        Map<UUID, PackProfile> selected = new LinkedHashMap<>();
        for (List<UUID> unit : units) {
            PackProfile profile = null;
            long bestMemory = Long.MAX_VALUE;
            long bestArchive = Long.MAX_VALUE;
            for (PackProfile candidate : fittingCandidates(unit, variants)) {
                List<PackAuditReport> reports = unit.stream().map(id -> variants.get(id).get(candidate)).toList();
                long memory = reports.stream().mapToLong(PackAuditReport::getTextureMemoryBytes).reduce(0, DeviceProfilePolicy::saturatedAdd);
                long itemAtlasMemory = reports.stream().mapToLong(PackAuditReport::getItemAtlasTextureMemoryBytes)
                        .reduce(0, DeviceProfilePolicy::saturatedAdd);
                long blockAtlasMemory = reports.stream().mapToLong(PackAuditReport::getBlockAtlasTextureMemoryBytes)
                        .reduce(0, DeviceProfilePolicy::saturatedAdd);
                long resourceMemory = estimatedResourceMemory(memory, itemAtlasMemory, blockAtlasMemory);
                long archive = reports.stream().mapToLong(PackAuditReport::getArchiveBytes).reduce(0, DeviceProfilePolicy::saturatedAdd);
                if (profile == null || resourceMemory < bestMemory || resourceMemory == bestMemory &&
                        (visualQuality(candidate) > visualQuality(profile) ||
                                visualQuality(candidate) == visualQuality(profile) && archive < bestArchive)) {
                    profile = candidate;
                    bestMemory = resourceMemory;
                    bestArchive = archive;
                }
            }
            if (profile == null) {
                profile = QUALITY_ORDER.stream()
                        .filter(candidate -> unit.stream().allMatch(id -> variants.getOrDefault(id, Map.of()).containsKey(candidate)))
                        .findFirst().orElse(PackProfile.FULL);
            }
            PackProfile chosen = profile;
            unit.forEach(id -> selected.put(id, chosen));
        }
        return selected;
    }

    private static boolean better(
            int quality,
            long estimatedResourceMemoryBytes,
            long archiveBytes,
            long textureBytes,
            State incumbent) {
        if (incumbent == null) return true;
        if (quality != incumbent.quality()) return quality > incumbent.quality();
        if (estimatedResourceMemoryBytes != incumbent.estimatedResourceMemoryBytes()) {
            return estimatedResourceMemoryBytes < incumbent.estimatedResourceMemoryBytes();
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
                || leftUsage.blockAtlasBytes() > rightUsage.blockAtlasBytes()) return false;
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
        long textureMemory = Math.max(0, fixedTextureMemoryBytes);
        long textureFileCount = Math.max(0, fixedTextureFileCount);
        long itemAtlasMemory = Math.max(0, fixedItemAtlasTextureMemoryBytes);
        long blockAtlasMemory = Math.max(0, fixedBlockAtlasTextureMemoryBytes);
        boolean complete = fixedMetricsComplete;
        boolean atlasComplete = fixedAtlasMetricsComplete;
        for (Map.Entry<UUID, PackProfile> entry : selected.entrySet()) {
            PackAuditReport report = variants == null ? null
                    : variants.getOrDefault(entry.getKey(), Map.of()).get(entry.getValue());
            if (report == null) {
                complete = false;
            } else {
                textureFileCount = saturatedAdd(textureFileCount, report.getTextureFileCount());
                textureMemory = saturatedAdd(textureMemory, report.getTextureMemoryBytes());
                if (!report.getTextureMetricsComplete()) complete = false;
                itemAtlasMemory = saturatedAdd(itemAtlasMemory, report.getItemAtlasTextureMemoryBytes());
                blockAtlasMemory = saturatedAdd(blockAtlasMemory, report.getBlockAtlasTextureMemoryBytes());
                if (!report.getAtlasReferenceScanComplete() || !report.getTextureMetricsComplete()) atlasComplete = false;
            }
        }
        long resourceMemory = estimatedResourceMemory(textureMemory, itemAtlasMemory, blockAtlasMemory);
        return new Selection(Map.copyOf(selected), budget, textureMemory, textureFileCount, complete,
                budgetKnown, budgetKnown && complete && atlasComplete && resourceMemory <= budget,
                resourceMemory,
                itemAtlasMemory, blockAtlasMemory, atlasComplete,
            atlasComplete && itemAtlasMemory <= STACK_ATLAS_POLICY_LIMIT_BYTES
                    && blockAtlasMemory <= STACK_ATLAS_POLICY_LIMIT_BYTES);
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
            boolean metricsComplete,
            boolean budgetKnown,
            boolean withinBudget,
            long estimatedResourceMemoryBytes,
            long estimatedItemAtlasTextureBytes,
            long estimatedBlockAtlasTextureBytes,
            boolean atlasMetricsComplete,
            boolean withinStackAtlasPolicy) {
    }

    record TextureHandleEstimate(long upperBound, boolean complete) {
    }

    private record ResourceUsage(long textureBytes, long itemAtlasBytes, long blockAtlasBytes) {
    }

    private static final class State {
        private final int quality;
        private final long archiveBytes;
        private final long textureMemoryBytes;
        private final long estimatedResourceMemoryBytes;
        private final State previous;
        private final Map<UUID, PackProfile> selectedProfiles;

        private State(int quality, long archiveBytes, long textureMemoryBytes,
                      long estimatedResourceMemoryBytes,
                      State previous, Map<UUID, PackProfile> selectedProfiles) {
            this.quality = quality;
            this.archiveBytes = archiveBytes;
            this.textureMemoryBytes = textureMemoryBytes;
            this.estimatedResourceMemoryBytes = estimatedResourceMemoryBytes;
            this.previous = previous;
            this.selectedProfiles = selectedProfiles;
        }

        private int quality() { return quality; }
        private long archiveBytes() { return archiveBytes; }
        private long textureMemoryBytes() { return textureMemoryBytes; }
        private long estimatedResourceMemoryBytes() { return estimatedResourceMemoryBytes; }

        private Map<UUID, PackProfile> profiles() {
            Map<UUID, PackProfile> profiles = new LinkedHashMap<>();
            for (State state = this; state != null; state = state.previous) {
                profiles.putAll(state.selectedProfiles);
            }
            return Map.copyOf(profiles);
        }
    }
}
