package me.kylofz.fettle.geyser;

import me.kylofz.fettle.optimizer.PackAuditReport;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

record PackSetAudit(
        List<ResourcePack> allPacks,
        List<String> sourceHashes,
        List<ResourcePack> targetPacks,
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> preparedVariants,
        Map<UUID, Map<PackProfile, PackAuditReport>> variantReports,
        List<Set<UUID>> linkedProfileGroups,
        long fixedTextureMemoryBytes,
        long fixedTextureFileCount,
        long fixedTextureHandleUpperBound,
        boolean fixedMetricsComplete,
        boolean fixedHandleMetricsComplete) {

    PackSetAudit {
        allPacks = List.copyOf(allPacks);
        sourceHashes = List.copyOf(sourceHashes);
        targetPacks = List.copyOf(targetPacks);
        linkedProfileGroups = linkedProfileGroups.stream().map(Set::copyOf).toList();
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> prepared = new LinkedHashMap<>();
        preparedVariants.forEach((uuid, profiles) -> prepared.put(uuid, Map.copyOf(profiles)));
        preparedVariants = Collections.unmodifiableMap(prepared);
        Map<UUID, Map<PackProfile, PackAuditReport>> reports = new LinkedHashMap<>();
        variantReports.forEach((uuid, profiles) -> reports.put(uuid, Map.copyOf(profiles)));
        variantReports = Collections.unmodifiableMap(reports);
    }

    boolean matches(Collection<ResourcePack> packs) {
        if (allPacks.size() != packs.size() || sourceHashes.size() != allPacks.size()) return false;
        Iterator<ResourcePack> prepared = allPacks.iterator();
        int index = 0;
        // Geyser copies the session list but keeps the registered pack objects.
        for (ResourcePack candidate : packs) {
            if (prepared.next() != candidate) return false;
            String currentHash = sourceHash(candidate);
            if (currentHash.isEmpty() || !currentHash.equals(sourceHashes.get(index++))) return false;
        }
        return true;
    }

    static PackSetAudit forSession(
            PackSetAudit prepared,
            Collection<ResourcePack> packs,
            Predicate<ResourcePack> targetMatcher,
            PackVariantManager variantManager) {
        List<ResourcePack> snapshot = List.copyOf(packs);
        if (prepared != null && prepared.matches(snapshot)) return prepared;

        List<ResourcePack> targetPacks = snapshot.stream().filter(targetMatcher).toList();
        return collect(snapshot, targetPacks, targetMatcher, variantManager);
    }

    static PackSetAudit collect(
            Collection<ResourcePack> packs,
            List<ResourcePack> targetPacks,
            Predicate<ResourcePack> targetMatcher,
            PackVariantManager variantManager) {
        List<ResourcePack> allPacks = List.copyOf(packs);
        targetPacks = List.copyOf(targetPacks);
        List<String> sourceHashes = allPacks.stream().map(PackSetAudit::sourceHash).toList();
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> preparedVariants = new LinkedHashMap<>();
        Map<UUID, Map<PackProfile, PackAuditReport>> variantReports = new LinkedHashMap<>();
        for (ResourcePack pack : targetPacks) {
            Map<PackProfile, PackVariantManager.PreparedVariant> prepared =
                    variantManager == null ? Map.of() : variantManager.preparedVariantsIfCached(pack);
            preparedVariants.put(pack.uuid(), prepared);
            Map<PackProfile, PackAuditReport> reports = new LinkedHashMap<>();
            prepared.forEach((profile, variant) -> reports.put(profile, variant.audit()));
            variantReports.put(pack.uuid(), reports);
        }

        long fixedTextureMemory = 0;
        long fixedTextureFileCount = 0;
        long fixedTextureHandleUpperBound = 0;
        boolean fixedMetricsComplete = true;
        boolean fixedHandleMetricsComplete = true;
        for (ResourcePack pack : allPacks) {
            if (targetMatcher.test(pack)) continue;
            PackVariantManager.PreparedVariant full = variantManager == null
                    ? null : variantManager.preparedVariantIfCached(pack, PackProfile.FULL);
            PackAuditReport report = full == null ? null : full.audit();
            if (report == null || !report.getTextureMetricsComplete()) fixedMetricsComplete = false;
            if (report == null || !report.getTextureHandleScanComplete()) fixedHandleMetricsComplete = false;
            if (report == null) continue;

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
            long handles = report.getEstimatedTextureHandleCount();
            if (Long.MAX_VALUE - fixedTextureHandleUpperBound < handles) {
                fixedTextureHandleUpperBound = Long.MAX_VALUE;
                fixedHandleMetricsComplete = false;
            } else {
                fixedTextureHandleUpperBound += handles;
            }
        }

        return new PackSetAudit(allPacks, sourceHashes, targetPacks, preparedVariants, variantReports,
                variantManager == null ? List.of() : variantManager.linkedProfileGroups(),
                fixedTextureMemory, fixedTextureFileCount, fixedTextureHandleUpperBound,
                fixedMetricsComplete, fixedHandleMetricsComplete);
    }

    private static String sourceHash(ResourcePack pack) {
        try {
            return PackVariantManager.sourceHash(pack);
        } catch (RuntimeException | LinkageError unavailable) {
            return "";
        }
    }
}
