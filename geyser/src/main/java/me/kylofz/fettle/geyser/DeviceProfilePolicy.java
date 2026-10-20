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
                fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, List.of());
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
                    fixedMetricsComplete, budgetKnown);
        }

        if (!budgetKnown) {
            return summarize(highestQualityProfiles(profileUnits, variants), variants, budget,
                    fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, false);
        }

        long availableBudget = Math.max(0, budget - Math.max(0, fixedTextureMemoryBytes));
        long budgetUnits = availableBudget / MIB;
        Map<Long, State> states = Map.of(0L, new State(0, 0, 0, null, Map.of()));
        for (List<UUID> unit : profileUnits) {
            Map<Long, State> next = new HashMap<>();
            for (Map.Entry<Long, State> stateEntry : states.entrySet()) {
                for (PackProfile candidate : fittingCandidates(unit, variants)) {
                    long candidateBytes = 0;
                    long candidateArchiveBytes = 0;
                    int candidateQuality = 0;
                    Map<UUID, PackProfile> candidateProfiles = new LinkedHashMap<>();
                    for (UUID packId : unit) {
                        PackAuditReport report = variants.get(packId).get(candidate);
                        candidateBytes = saturatedAdd(candidateBytes, report.getTextureMemoryBytes());
                        candidateArchiveBytes = saturatedAdd(candidateArchiveBytes, report.getArchiveBytes());
                        candidateQuality += visualQuality(candidate);
                        candidateProfiles.put(packId, candidate);
                    }
                    long used = stateEntry.getKey() + memoryUnits(candidateBytes);
                    if (used > budgetUnits) continue;

                    State previous = stateEntry.getValue();
                    State incumbent = next.get(used);
                    int quality = previous.quality() + candidateQuality;
                    long archiveBytes = saturatedAdd(previous.archiveBytes(), candidateArchiveBytes);
                    long textureBytes = saturatedAdd(previous.textureMemoryBytes(), candidateBytes);
                    if (better(quality, archiveBytes, textureBytes, incumbent)) {
                        next.put(used, new State(quality, archiveBytes, textureBytes,
                                previous, Map.copyOf(candidateProfiles)));
                    }
                }
            }
            if (next.isEmpty()) {
                return summarize(lowestMemoryProfiles(profileUnits, variants), variants, budget,
                        fixedTextureMemoryBytes, fixedTextureFileCount, fixedMetricsComplete, true);
            }
            states = next;
        }

        State best = states.values().stream()
                .max(Comparator.comparingInt(State::quality)
                        .thenComparingLong(state -> -state.textureMemoryBytes())
                        .thenComparingLong(state -> -state.archiveBytes()))
                .orElse(new State(0, 0, 0, null, Map.of()));
        return summarize(best.profiles(), variants, budget, fixedTextureMemoryBytes, fixedTextureFileCount,
                fixedMetricsComplete, true);
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

    static boolean fits(PackAuditReport report, long textureBudget) {
        return report != null && report.getTextureMetricsComplete() && report.getTextureMemoryBytes() <= textureBudget;
    }

    private static List<PackProfile> fittingCandidates(
            List<UUID> unit,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants) {
        List<PackProfile> result = new ArrayList<>();
        for (PackProfile profile : QUALITY_ORDER) {
            if (unit.stream().allMatch(id -> variants.getOrDefault(id, Map.of()).containsKey(profile)
                    && fits(variants.get(id).get(profile), Long.MAX_VALUE))) result.add(profile);
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

    private static Map<UUID, PackProfile> highestQualityProfiles(
            List<List<UUID>> units,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants) {
        Map<UUID, PackProfile> selected = new LinkedHashMap<>();
        for (List<UUID> unit : units) {
            PackProfile profile = QUALITY_ORDER.stream()
                    .filter(candidate -> unit.stream().allMatch(id -> variants.getOrDefault(id, Map.of()).containsKey(candidate)))
                    .findFirst()
                    .orElse(PackProfile.FULL);
            unit.forEach(id -> selected.put(id, profile));
        }
        return selected;
    }

    private static Map<UUID, PackProfile> lowestMemoryProfiles(
            List<List<UUID>> units,
            Map<UUID, Map<PackProfile, PackAuditReport>> variants) {
        Map<UUID, PackProfile> selected = new LinkedHashMap<>();
        for (List<UUID> unit : units) {
            PackProfile profile = null;
            long bestMemory = Long.MAX_VALUE;
            long bestArchive = Long.MAX_VALUE;
            for (PackProfile candidate : QUALITY_ORDER) {
                if (!unit.stream().allMatch(id -> variants.getOrDefault(id, Map.of()).containsKey(candidate))) continue;
                List<PackAuditReport> reports = unit.stream().map(id -> variants.get(id).get(candidate)).toList();
                if (reports.stream().anyMatch(report -> report == null || !report.getTextureMetricsComplete())) continue;
                long memory = reports.stream().mapToLong(PackAuditReport::getTextureMemoryBytes).reduce(0, DeviceProfilePolicy::saturatedAdd);
                long archive = reports.stream().mapToLong(PackAuditReport::getArchiveBytes).reduce(0, DeviceProfilePolicy::saturatedAdd);
                if (profile == null || memory < bestMemory || memory == bestMemory &&
                        (visualQuality(candidate) > visualQuality(profile) ||
                                visualQuality(candidate) == visualQuality(profile) && archive < bestArchive)) {
                    profile = candidate;
                    bestMemory = memory;
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

    private static boolean better(int quality, long archiveBytes, long textureBytes, State incumbent) {
        if (incumbent == null) return true;
        if (quality != incumbent.quality()) return quality > incumbent.quality();
        if (textureBytes != incumbent.textureMemoryBytes()) {
            return textureBytes < incumbent.textureMemoryBytes();
        }
        return archiveBytes < incumbent.archiveBytes();
    }

    private static long memoryUnits(long bytes) {
        if (bytes <= 0) return 0;
        return 1 + (bytes - 1) / MIB;
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
            boolean budgetKnown) {
        long textureMemory = Math.max(0, fixedTextureMemoryBytes);
        long textureFileCount = Math.max(0, fixedTextureFileCount);
        boolean complete = fixedMetricsComplete;
        for (Map.Entry<UUID, PackProfile> entry : selected.entrySet()) {
            PackAuditReport report = variants == null ? null
                    : variants.getOrDefault(entry.getKey(), Map.of()).get(entry.getValue());
            if (report == null) {
                complete = false;
            } else {
                textureFileCount = saturatedAdd(textureFileCount, report.getTextureFileCount());
                if (report.getTextureMetricsComplete()) {
                    textureMemory = saturatedAdd(textureMemory, report.getTextureMemoryBytes());
                } else {
                    complete = false;
                }
            }
        }
        return new Selection(Map.copyOf(selected), budget, textureMemory, textureFileCount, complete,
                budgetKnown, budgetKnown && complete && textureMemory <= budget);
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
            boolean withinBudget) {
    }

    record TextureHandleEstimate(long upperBound, boolean complete) {
    }

    private static final class State {
        private final int quality;
        private final long archiveBytes;
        private final long textureMemoryBytes;
        private final State previous;
        private final Map<UUID, PackProfile> selectedProfiles;

        private State(int quality, long archiveBytes, long textureMemoryBytes,
                      State previous, Map<UUID, PackProfile> selectedProfiles) {
            this.quality = quality;
            this.archiveBytes = archiveBytes;
            this.textureMemoryBytes = textureMemoryBytes;
            this.previous = previous;
            this.selectedProfiles = selectedProfiles;
        }

        private int quality() { return quality; }
        private long archiveBytes() { return archiveBytes; }
        private long textureMemoryBytes() { return textureMemoryBytes; }

        private Map<UUID, PackProfile> profiles() {
            Map<UUID, PackProfile> profiles = new LinkedHashMap<>();
            for (State state = this; state != null; state = state.previous) {
                profiles.putAll(state.selectedProfiles);
            }
            return Map.copyOf(profiles);
        }
    }
}
