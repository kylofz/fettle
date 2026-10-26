package me.kylofz.fettle.geyser;

import me.kylofz.fettle.optimizer.PackAuditReport;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;

import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

record PackSetAudit(
        List<ResourcePack> allPacks,
        List<String> sourceHashes,
        Map<UUID, String> packOptionFingerprints,
        List<ResourcePack> targetPacks,
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> preparedVariants,
        Map<UUID, Map<PackProfile, PackAuditReport>> variantReports,
        Map<UUID, PackAuditReport> fixedPackReports,
        List<Set<UUID>> linkedProfileGroups,
        long fixedTextureMemoryBytes,
        long fixedTextureFileCount,
        long fixedItemAtlasTextureMemoryBytes,
        long fixedBlockAtlasTextureMemoryBytes,
        boolean fixedAtlasMetricsComplete,
        long fixedTextureHandleUpperBound,
        boolean fixedMetricsComplete,
        boolean fixedHandleMetricsComplete,
        boolean preparedPackStackMatches) {

    PackSetAudit(
            List<ResourcePack> allPacks,
            List<String> sourceHashes,
            List<ResourcePack> targetPacks,
            Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> preparedVariants,
            Map<UUID, Map<PackProfile, PackAuditReport>> variantReports,
            Map<UUID, PackAuditReport> fixedPackReports,
            List<Set<UUID>> linkedProfileGroups,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            long fixedItemAtlasTextureMemoryBytes,
            long fixedBlockAtlasTextureMemoryBytes,
            boolean fixedAtlasMetricsComplete,
            long fixedTextureHandleUpperBound,
            boolean fixedMetricsComplete,
            boolean fixedHandleMetricsComplete) {
        this(allPacks, sourceHashes, Map.of(), targetPacks, preparedVariants, variantReports, fixedPackReports,
                linkedProfileGroups, fixedTextureMemoryBytes, fixedTextureFileCount,
                fixedItemAtlasTextureMemoryBytes, fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete,
                fixedTextureHandleUpperBound, fixedMetricsComplete, fixedHandleMetricsComplete, true);
    }

    PackSetAudit(
            List<ResourcePack> allPacks,
            List<String> sourceHashes,
            Map<UUID, String> packOptionFingerprints,
            List<ResourcePack> targetPacks,
            Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> preparedVariants,
            Map<UUID, Map<PackProfile, PackAuditReport>> variantReports,
            Map<UUID, PackAuditReport> fixedPackReports,
            List<Set<UUID>> linkedProfileGroups,
            long fixedTextureMemoryBytes,
            long fixedTextureFileCount,
            long fixedItemAtlasTextureMemoryBytes,
            long fixedBlockAtlasTextureMemoryBytes,
            boolean fixedAtlasMetricsComplete,
            long fixedTextureHandleUpperBound,
            boolean fixedMetricsComplete,
            boolean fixedHandleMetricsComplete) {
        this(allPacks, sourceHashes, packOptionFingerprints, targetPacks, preparedVariants, variantReports, fixedPackReports,
                linkedProfileGroups, fixedTextureMemoryBytes, fixedTextureFileCount,
                fixedItemAtlasTextureMemoryBytes, fixedBlockAtlasTextureMemoryBytes, fixedAtlasMetricsComplete,
                fixedTextureHandleUpperBound, fixedMetricsComplete, fixedHandleMetricsComplete, true);
    }

    PackSetAudit {
        allPacks = List.copyOf(allPacks);
        sourceHashes = List.copyOf(sourceHashes);
        packOptionFingerprints = Map.copyOf(packOptionFingerprints);
        targetPacks = List.copyOf(targetPacks);
        linkedProfileGroups = linkedProfileGroups.stream().map(Set::copyOf).toList();
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> prepared = new LinkedHashMap<>();
        preparedVariants.forEach((uuid, profiles) -> prepared.put(uuid, Map.copyOf(profiles)));
        preparedVariants = Collections.unmodifiableMap(prepared);
        Map<UUID, Map<PackProfile, PackAuditReport>> reports = new LinkedHashMap<>();
        variantReports.forEach((uuid, profiles) -> reports.put(uuid, Map.copyOf(profiles)));
        variantReports = Collections.unmodifiableMap(reports);
        fixedPackReports = Map.copyOf(fixedPackReports);
    }

    long fixedPackFileCount() {
        long count = 0;
        for (PackAuditReport report : fixedPackReports.values()) {
            if (Long.MAX_VALUE - count < report.getFileCount()) return Long.MAX_VALUE;
            count += report.getFileCount();
        }
        return count;
    }

    boolean matches(Collection<ResourcePack> packs) {
        return matches(packs, Map.of());
    }

    boolean matches(
            Collection<ResourcePack> packs,
            Map<UUID, ? extends Collection<ResourcePackOption<?>>> options) {
        if (allPacks.size() != packs.size() || sourceHashes.size() != allPacks.size()) return false;
        int index = 0;
        for (ResourcePack candidate : packs) {
            ResourcePack prepared = allPacks.get(index);
            if (!candidate.uuid().equals(prepared.uuid())) return false;
            String currentHash = sourceHash(candidate);
            if (currentHash.isEmpty() || !currentHash.equals(sourceHashes.get(index))) return false;
            Collection<ResourcePackOption<?>> candidateOptions = options == null ? null
                    : options.get(candidate.uuid());
            String currentOptions = PackVariantManager.optionFingerprint(
                    candidateOptions == null ? List.of() : candidateOptions);
            if (!currentOptions.equals(packOptionFingerprints.getOrDefault(candidate.uuid(), ""))) return false;
            index++;
        }
        return true;
    }

    static PackSetAudit forSession(
            PackSetAudit prepared,
            Collection<ResourcePack> packs,
            Predicate<ResourcePack> targetMatcher,
            PackVariantManager variantManager) {
        return forSession(prepared, packs, targetMatcher, variantManager, Map.of());
    }

    static PackSetAudit forSession(
            PackSetAudit prepared,
            Collection<ResourcePack> packs,
            Predicate<ResourcePack> targetMatcher,
            PackVariantManager variantManager,
            Map<UUID, ? extends Collection<ResourcePackOption<?>>> packOptions) {
        List<ResourcePack> snapshot = List.copyOf(packs);
        if (prepared != null && prepared.matches(snapshot, packOptions)) return prepared;

        List<ResourcePack> targetPacks = snapshot.stream().filter(targetMatcher).toList();
        return collect(snapshot, targetPacks, targetMatcher, variantManager, packOptions);
    }

    List<PackAuditSnapshot> selectedPackAudits(Map<UUID, PackProfile> selectedProfiles) {
        Map<UUID, ResourcePack> packsById = new LinkedHashMap<>();
        allPacks.forEach(pack -> packsById.put(pack.uuid(), pack));
        Set<UUID> ids = new java.util.LinkedHashSet<>(packsById.keySet());
        ids.addAll(selectedProfiles.keySet());
        ids.addAll(fixedPackReports.keySet());

        return ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).map(id -> {
            PackProfile profile = selectedProfiles.get(id);
            boolean optimizedTarget = profile != null;
            PackAuditReport report = optimizedTarget
                    ? variantReports.getOrDefault(id, Map.of()).get(profile)
                    : fixedPackReports.get(id);
            ResourcePack pack = packsById.get(id);
            String name = id.toString();
            if (pack != null) {
                try {
                    String candidate = pack.manifest().header().name();
                    if (candidate != null && !candidate.isBlank()) name = candidate;
                } catch (RuntimeException | LinkageError ignored) {
                }
            }
            return new PackAuditSnapshot(id, name, optimizedTarget ? profile : PackProfile.FULL, report);
        }).toList();
    }

    PackVariantManager.PreparedVariant preparedVariant(
            ResourcePack original,
            PackProfile profile,
            PackVariantManager variantManager) {
        if (profile != PackProfile.FULL && !preparedPackStackMatches) return null;
        PackVariantManager.PreparedVariant variant = preparedVariants
                .getOrDefault(original.uuid(), Map.of()).get(profile);
        if (variant != null && variant.file() == null) return variant;
        if (variant != null && variantManager != null) {
            return variantManager.isPreparedVariantValid(variant) ? variant : null;
        }
        if (variant != null && Files.isRegularFile(variant.file())) return variant;
        if (profile != PackProfile.FULL && preparedPackStackMatches) return null;
        return variantManager == null ? null : variantManager.preparedVariantIfCached(original, profile);
    }

    static PackSetAudit collect(
            Collection<ResourcePack> packs,
            List<ResourcePack> targetPacks,
            Predicate<ResourcePack> targetMatcher,
            PackVariantManager variantManager) {
        return collect(packs, targetPacks, targetMatcher, variantManager, Map.of());
    }

    static PackSetAudit collect(
            Collection<ResourcePack> packs,
            List<ResourcePack> targetPacks,
            Predicate<ResourcePack> targetMatcher,
            PackVariantManager variantManager,
            Map<UUID, ? extends Collection<ResourcePackOption<?>>> packOptions) {
        List<ResourcePack> allPacks = List.copyOf(packs);
        targetPacks = List.copyOf(targetPacks);
        List<String> sourceHashes = allPacks.stream().map(PackSetAudit::sourceHash).toList();
        Map<UUID, String> sourceHashesById = new LinkedHashMap<>();
        Map<UUID, String> optionFingerprintsById = new LinkedHashMap<>();
        for (int index = 0; index < allPacks.size(); index++) {
            sourceHashesById.put(allPacks.get(index).uuid(), sourceHashes.get(index));
            Collection<ResourcePackOption<?>> options = packOptions == null ? null
                    : packOptions.get(allPacks.get(index).uuid());
            optionFingerprintsById.put(allPacks.get(index).uuid(),
                    PackVariantManager.optionFingerprint(options == null ? List.of() : options));
        }
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> preparedVariants = new LinkedHashMap<>();
        Map<UUID, Map<PackProfile, PackAuditReport>> variantReports = new LinkedHashMap<>();
        boolean preparedPackStackMatches = variantManager != null
                && variantManager.matchesPreparedSet(allPacks, targetPacks, sourceHashesById, optionFingerprintsById);
        for (ResourcePack pack : targetPacks) {
            Map<PackProfile, PackVariantManager.PreparedVariant> prepared;
            if (variantManager == null) {
                prepared = Map.of();
            } else if (preparedPackStackMatches) {
                prepared = variantManager.preparedVariantsIfCached(pack);
            } else {
                PackVariantManager.PreparedVariant original =
                        variantManager.preparedVariantIfCached(pack, PackProfile.FULL);
                prepared = original == null ? Map.of() : Map.of(PackProfile.FULL, original);
            }
            preparedVariants.put(pack.uuid(), prepared);
            Map<PackProfile, PackAuditReport> reports = new LinkedHashMap<>();
            prepared.forEach((profile, variant) -> reports.put(profile, variant.audit()));
            variantReports.put(pack.uuid(), reports);
        }

        long fixedTextureMemory = 0;
        long fixedTextureFileCount = 0;
        long fixedItemAtlasTextureMemory = 0;
        long fixedBlockAtlasTextureMemory = 0;
        long fixedTextureHandleUpperBound = 0;
        boolean fixedMetricsComplete = true;
        boolean fixedAtlasMetricsComplete = true;
        boolean fixedHandleMetricsComplete = true;
        Map<UUID, PackAuditReport> fixedPackReports = new LinkedHashMap<>();
        for (ResourcePack pack : allPacks) {
            if (targetMatcher.test(pack)) continue;
            PackVariantManager.PreparedVariant full = variantManager == null
                    ? null : variantManager.preparedVariantIfCached(pack, PackProfile.FULL);
            PackAuditReport report = full == null ? null : full.audit();
            if (report == null || !report.getTextureMetricsComplete() || !report.getTextureHandleScanComplete()
                    || report.getOversizedModelCount() > 0) fixedMetricsComplete = false;
            if (report == null || !report.getAtlasReferenceScanComplete() || !report.getTextureMetricsComplete()) {
                fixedAtlasMetricsComplete = false;
            }
            if (report == null || !report.getTextureHandleScanComplete()) fixedHandleMetricsComplete = false;
            if (report == null) continue;
            fixedPackReports.put(pack.uuid(), report);

            long bytes = report.getTextureMemoryBytes();
            if (Long.MAX_VALUE - fixedTextureMemory < bytes) {
                fixedTextureMemory = Long.MAX_VALUE;
                fixedMetricsComplete = false;
            } else {
                fixedTextureMemory += bytes;
            }
            long textureFiles = report.getTextureFileCount();
            if (Long.MAX_VALUE - fixedTextureFileCount < textureFiles) {
                fixedTextureFileCount = Long.MAX_VALUE;
                fixedMetricsComplete = false;
            } else {
                fixedTextureFileCount += textureFiles;
            }
            long itemAtlasBytes = report.getItemAtlasTextureMemoryBytes();
            if (Long.MAX_VALUE - fixedItemAtlasTextureMemory < itemAtlasBytes) {
                fixedItemAtlasTextureMemory = Long.MAX_VALUE;
                fixedAtlasMetricsComplete = false;
            } else {
                fixedItemAtlasTextureMemory += itemAtlasBytes;
            }
            long blockAtlasBytes = report.getBlockAtlasTextureMemoryBytes();
            if (Long.MAX_VALUE - fixedBlockAtlasTextureMemory < blockAtlasBytes) {
                fixedBlockAtlasTextureMemory = Long.MAX_VALUE;
                fixedAtlasMetricsComplete = false;
            } else {
                fixedBlockAtlasTextureMemory += blockAtlasBytes;
            }
            long handles = report.getEstimatedTextureHandleCount();
            if (Long.MAX_VALUE - fixedTextureHandleUpperBound < handles) {
                fixedTextureHandleUpperBound = Long.MAX_VALUE;
                fixedHandleMetricsComplete = false;
            } else {
                fixedTextureHandleUpperBound += handles;
            }
        }

        return new PackSetAudit(allPacks, sourceHashes, optionFingerprintsById, targetPacks,
                preparedVariants, variantReports, fixedPackReports,
                variantManager == null ? List.of() : variantManager.linkedProfileGroups(),
                fixedTextureMemory, fixedTextureFileCount, fixedItemAtlasTextureMemory,
                fixedBlockAtlasTextureMemory, fixedAtlasMetricsComplete, fixedTextureHandleUpperBound,
                fixedMetricsComplete, fixedHandleMetricsComplete, preparedPackStackMatches);
    }

    record PackAuditSnapshot(UUID id, String name, PackProfile profile, PackAuditReport report) {
    }

    private static String sourceHash(ResourcePack pack) {
        try {
            return PackVariantManager.sourceHash(pack);
        } catch (RuntimeException | LinkageError unavailable) {
            return "";
        }
    }
}
