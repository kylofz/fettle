package me.kylofz.fettle.geyser;

import me.kylofz.fettle.optimizer.PackAuditReport;
import me.kylofz.fettle.optimizer.PackAuditor;
import me.kylofz.fettle.optimizer.PackOptimizationReport;
import me.kylofz.fettle.optimizer.PackOptimizer;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PackVariantManager {
    static final String REWRITE_REVISION = "device-tier-v40";
    static final List<PackProfile> PREWARM_PROFILES = List.of(
            PackProfile.FULL, PackProfile.OPTIMIZE_FULL, PackProfile.OPTIMIZE_SEMI,
            PackProfile.OPTIMIZE_MINI, PackProfile.SAFE);
    private static final long MAX_CACHE_BYTES = 2L * 1024L * 1024L * 1024L;
    private final Path cacheDirectory;
    private final PackOptimizer optimizer = new PackOptimizer();
    private final PackAuditor auditor = new PackAuditor();
    private final Function<Path, ResourcePack> packFactory;
    private final Map<String, PreparedVariant> cache = new ConcurrentHashMap<>();
    private String activeSetFingerprint = "";
    private String activePackStackFingerprint = "";
    private List<Set<Integer>> activeLinkedPackGroupIndexes = List.of();
    private Set<String> activeProtectedTexturePaths = Set.of();
    private boolean activeCrossPackTextureDedupAllowed = true;
    private boolean activeVariantIdentitiesSafe;
    private List<Set<UUID>> linkedProfileGroups = List.of();

    PackVariantManager(Path cacheDirectory) throws IOException {
        this(cacheDirectory, path -> ResourcePack.create(PackCodec.path(path)));
    }

    PackVariantManager(Path cacheDirectory, Function<Path, ResourcePack> packFactory) throws IOException {
        this.cacheDirectory = cacheDirectory;
        this.packFactory = packFactory;
        Files.createDirectories(cacheDirectory);
    }

    synchronized PreparedVariant prepare(ResourcePack original, PackProfile profile) throws Exception {
        if (profile == PackProfile.AUTO) {
            throw new IllegalArgumentException("AUTO is a selection strategy, not a generated pack");
        }

        String fingerprint = fingerprint(original, profile);
        String cacheKey = cacheKey(original, fingerprint);
        PreparedVariant cached = loadCached(original, profile, fingerprint, cacheKey);
        if (cached != null) return cached;

        if (profile == PackProfile.FULL) {
            Path source = materialize(original);
            try {
                return remember(cacheKey, new PreparedVariant(
                        fingerprint, profile, original, null, auditor.audit(source)));
            } finally {
                Files.deleteIfExists(source);
            }
        }

        Path output = outputFile(original, fingerprint, profile);
        boolean lossless = isLossless(profile);
        Path source = materialize(original);
        try {
            if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return new PreparedVariant(fingerprint, profile, original, null, auditor.audit(source));
            }
            if (auditor.hasOpaqueBedrockArchive(source)) {
                return new PreparedVariant(fingerprint, profile, original, null, auditor.audit(source));
            }
            PackOptimizationReport rewrite = switch (profile) {
                case AUTO, FULL -> throw new IllegalStateException("AUTO/FULL have no generated pack");
                case OPTIMIZE_FULL -> optimizer.optimizeForLowEnd(source, output);
                case OPTIMIZE_SEMI -> optimizer.optimizeMini(source, output, List.of(), 512);
                case OPTIMIZE_MINI -> optimizer.optimizeMini(source, output, List.of(), 256);
                case SAFE -> optimizer.optimizeSafe(source, output, 128);
            };
            PackAuditReport audit = auditor.audit(output);
            if (lossless && rewrite.getOutputBytes() >= rewrite.getInputBytes()) {
                PackAuditReport sourceAudit = auditor.audit(source);
                if (!losslessRewriteUseful(rewrite, sourceAudit, audit)) {
                    Files.deleteIfExists(output);
                    markNoChange(noChangeMarker(output));
                    return remember(cacheKey, new PreparedVariant(fingerprint, profile, original, null, sourceAudit));
                }
            }

            ResourcePack pack = packFactory.apply(output);
            return remember(cacheKey, new PreparedVariant(fingerprint, profile, pack, output, audit));
        } finally {
            Files.deleteIfExists(source);
        }
    }

    synchronized Map<UUID, Map<PackProfile, PreparedVariant>> prepareSet(
            Collection<ResourcePack> originals,
            Collection<PackProfile> profiles) throws Exception {
        return prepareSet(originals, originals, profiles);
    }

    synchronized Map<UUID, Map<PackProfile, PreparedVariant>> prepareSet(
            Collection<ResourcePack> originals,
            Collection<ResourcePack> packStack,
            Collection<PackProfile> profiles) throws Exception {
        List<ResourcePack> packs = List.copyOf(originals);
        List<ResourcePack> stack = List.copyOf(packStack);
        Map<UUID, String> stackHashes = new HashMap<>();
        for (ResourcePack pack : stack) {
            if (stackHashes.put(pack.uuid(), sourceHash(pack)) != null) {
                throw new IllegalArgumentException("Resource-pack stack contains duplicate UUID " + pack.uuid());
            }
        }
        for (ResourcePack pack : packs) {
            if (!sourceHash(pack).equals(stackHashes.get(pack.uuid()))) {
                throw new IllegalArgumentException("Every optimized pack must be present unchanged in the full stack");
            }
        }
        this.activeSetFingerprint = packSetFingerprint(packs, stackHashes);
        this.activePackStackFingerprint = packSetFingerprint(stack, stackHashes);
        Set<UUID> targetIds = packs.stream().map(ResourcePack::uuid).collect(java.util.stream.Collectors.toSet());
        this.activeVariantIdentitiesSafe = canAssignDistinctVariantIds(stack, targetIds);
        List<Path> groupSources = new ArrayList<>(packs.size());
        List<Path> fixedPackSources = new ArrayList<>();
        try {
            for (ResourcePack pack : packs) {
                Path source = materialize(pack);
                groupSources.add(source);
            }
            this.activeLinkedPackGroupIndexes = optimizer.linkedPackGroups(groupSources);
            this.linkedProfileGroups = activeLinkedPackGroupIndexes.stream()
                    .map(group -> group.stream().map(packs::get).map(ResourcePack::uuid)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                    .toList();
            Set<String> protectedPaths = new java.util.HashSet<>();
            boolean opaqueFixedPack = false;
            boolean fixedReferencesComplete = true;
            for (ResourcePack pack : stack) {
                if (targetIds.contains(pack.uuid())) continue;
                try {
                    Path source = materialize(pack);
                    fixedPackSources.add(source);
                    opaqueFixedPack |= auditor.hasOpaqueBedrockArchive(source);
                    protectedPaths.addAll(texturePaths(source));
                    Set<String> references = auditor.texturePathReferences(source);
                    if (references == null) fixedReferencesComplete = false;
                    else protectedPaths.addAll(references);
                } catch (IOException unmeasurableFixedPack) {
                    opaqueFixedPack = true;
                }
            }
            this.activeProtectedTexturePaths = opaqueFixedPack ? null : Set.copyOf(protectedPaths);
            this.activeCrossPackTextureDedupAllowed = !opaqueFixedPack && fixedReferencesComplete;
        } finally {
            for (Path source : groupSources) Files.deleteIfExists(source);
            for (Path source : fixedPackSources) Files.deleteIfExists(source);
        }
        Map<UUID, Map<PackProfile, PreparedVariant>> prepared = new LinkedHashMap<>();
        for (ResourcePack pack : packs) prepared.put(pack.uuid(), new EnumMap<>(PackProfile.class));

        for (PackProfile profile : profiles) {
            if (profile == PackProfile.AUTO) {
                throw new IllegalArgumentException("AUTO is a selection strategy, not a generated pack");
            }
            if (profile != PackProfile.FULL && !activeVariantIdentitiesSafe) continue;
            Map<UUID, PreparedVariant> setVariants = profile == PackProfile.FULL
                    ? prepareOriginalSet(packs)
                    : prepareSetVariant(packs, profile);
            setVariants.forEach((uuid, variant) -> prepared.get(uuid).put(profile, variant));
        }

        Map<UUID, Map<PackProfile, PreparedVariant>> result = new LinkedHashMap<>();
        prepared.forEach((uuid, variants) -> result.put(uuid, Map.copyOf(variants)));
        return Map.copyOf(result);
    }

    private Map<UUID, PreparedVariant> prepareOriginalSet(List<ResourcePack> originals) throws Exception {
        Map<UUID, PreparedVariant> prepared = new LinkedHashMap<>();
        for (ResourcePack original : originals) prepared.put(original.uuid(), prepare(original, PackProfile.FULL));
        return Map.copyOf(prepared);
    }

    private Map<UUID, PreparedVariant> prepareSetVariant(
            List<ResourcePack> originals,
            PackProfile profile) throws Exception {
        boolean lossless = isLossless(profile);
        Map<UUID, PreparedVariant> cached = new LinkedHashMap<>();
        boolean allCached = true;
        for (ResourcePack original : originals) {
            String fingerprint = fingerprint(original, profile);
            PreparedVariant variant = loadCached(
                    original, profile, fingerprint, cacheKey(original, fingerprint));
            if (variant == null) allCached = false;
            else cached.put(original.uuid(), variant);
        }
        if (allCached) return Map.copyOf(cached);

        boolean occupiedCacheArtifact = originals.stream().anyMatch(original -> {
            if (cached.containsKey(original.uuid())) return false;
            Path output = outputFile(original, fingerprint(original, profile), profile);
            return Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || isLossless(profile) && Files.exists(noChangeMarker(output), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        });
        if (occupiedCacheArtifact) return Map.of();

        Map<UUID, PreparedVariant> losslessSources = new LinkedHashMap<>();
        boolean allLosslessSourcesCached = profile != PackProfile.OPTIMIZE_FULL;
        if (allLosslessSourcesCached) {
            for (ResourcePack original : originals) {
                String fingerprint = fingerprint(original, PackProfile.OPTIMIZE_FULL);
                PreparedVariant variant = loadCached(original, PackProfile.OPTIMIZE_FULL,
                        fingerprint, cacheKey(original, fingerprint));
                if (variant == null) {
                    allLosslessSourcesCached = false;
                    losslessSources.clear();
                    break;
                }
                losslessSources.put(original.uuid(), variant);
            }
        }

        List<Path> sources = new ArrayList<>(originals.size());
        List<Path> temporarySources = new ArrayList<>(originals.size());
        List<Path> temporaryOutputs = new ArrayList<>(originals.size());
        List<Path> finalOutputs = originals.stream()
                .map(original -> outputFile(original, fingerprint(original, profile), profile))
                .toList();
        List<UUID> variantUuids = originals.stream()
                .map(original -> variantUuid(original, fingerprint(original, profile)))
                .toList();
        try {
            for (int index = 0; index < originals.size(); index++) {
                ResourcePack source = originals.get(index);
                Path sourceFile = null;
                if (allLosslessSourcesCached) {
                    PreparedVariant losslessVariant = losslessSources.get(source.uuid());
                    source = losslessVariant.pack();
                    Path losslessFile = losslessVariant.file();
                    if (losslessFile != null && Files.isRegularFile(losslessFile)) sourceFile = losslessFile;
                }
                if (sourceFile == null) {
                    sourceFile = materialize(source);
                    temporarySources.add(sourceFile);
                }
                sources.add(sourceFile);
                temporaryOutputs.add(Files.createTempFile(
                        cacheDirectory, "." + finalOutputs.get(index).getFileName(), ".tmp"));
            }

            if (sources.stream().anyMatch(auditor::hasOpaqueBedrockArchive)) {
                return Map.of();
            }

            int maxTextureDimension = switch (profile) {
                case OPTIMIZE_FULL -> 0;
                case OPTIMIZE_SEMI -> 512;
                case OPTIMIZE_MINI -> 256;
                case SAFE -> 128;
                case AUTO, FULL -> throw new IllegalStateException("AUTO/FULL have no generated pack set");
            };
            List<PackOptimizationReport> rewrites = lossless
                    ? optimizer.optimizeForLowEndSet(
                            sources, temporaryOutputs, 0, activeLinkedPackGroupIndexes, activeProtectedTexturePaths,
                            activeCrossPackTextureDedupAllowed, variantUuids)
                    : optimizer.optimizeResizedPackSet(sources, temporaryOutputs, maxTextureDimension, variantUuids);
            List<PackAuditReport> sourceAudits = lossless ? new ArrayList<>(sources.size()) : List.of();
            List<PackAuditReport> outputAudits = new ArrayList<>(sources.size());
            for (int index = 0; index < sources.size(); index++) {
                if (lossless) sourceAudits.add(auditor.audit(sources.get(index)));
                outputAudits.add(auditor.audit(temporaryOutputs.get(index)));
            }

            Map<UUID, PreparedVariant> prepared = new LinkedHashMap<>();
            boolean useful = !lossless || losslessSetRewriteUseful(rewrites, sourceAudits, outputAudits);
            for (int index = 0; index < originals.size(); index++) {
                ResourcePack original = originals.get(index);
                String fingerprint = fingerprint(original, profile);
                Path output = finalOutputs.get(index);
                Path temporary = temporaryOutputs.get(index);
                PackAuditReport sourceAudit = lossless ? sourceAudits.get(index) : null;
                PreparedVariant cachedVariant = cached.get(original.uuid());
                if (cachedVariant != null) {
                    Files.deleteIfExists(temporary);
                    prepared.put(original.uuid(), cachedVariant);
                    continue;
                }

                if (lossless && (!useful || !losslessRewriteChanged(rewrites.get(index)))) {
                    Files.deleteIfExists(temporary);
                    Files.deleteIfExists(output);
                    markNoChange(noChangeMarker(output));
                    prepared.put(original.uuid(), remember(
                            cacheKey(original, fingerprint),
                            new PreparedVariant(fingerprint, profile, original, null, sourceAudit)));
                    continue;
                }

                moveIntoPlace(temporary, output);
                ResourcePack pack = packFactory.apply(output);
                prepared.put(original.uuid(), remember(
                        cacheKey(original, fingerprint),
                        new PreparedVariant(fingerprint, profile, pack, output, outputAudits.get(index))));
            }
            return Map.copyOf(prepared);
        } catch (Exception | LinkageError failure) {
            for (Path temporary : temporaryOutputs) Files.deleteIfExists(temporary);
            throw failure;
        } finally {
            for (Path source : temporarySources) Files.deleteIfExists(source);
            for (Path temporary : temporaryOutputs) Files.deleteIfExists(temporary);
        }
    }

    synchronized PreparedVariant prepareIfCached(ResourcePack original, PackProfile profile) throws Exception {
        if (profile == PackProfile.AUTO) {
            throw new IllegalArgumentException("AUTO is a selection strategy, not a generated pack");
        }
        String fingerprint = fingerprint(original, profile);
        return loadCached(original, profile, fingerprint, cacheKey(original, fingerprint));
    }

    synchronized boolean matchesPreparedSet(Collection<ResourcePack> originals) {
        return matchesPreparedSet(originals, originals);
    }

    synchronized boolean matchesPreparedSet(
            Collection<ResourcePack> packStack,
            Collection<ResourcePack> originals) {
        try {
            Map<UUID, String> sourceHashes = new HashMap<>();
            for (ResourcePack pack : packStack) sourceHashes.put(pack.uuid(), sourceHash(pack));
            return matchesPreparedSet(packStack, originals, sourceHashes);
        } catch (RuntimeException | LinkageError invalidPackStack) {
            return false;
        }
    }

    synchronized boolean matchesPreparedSet(
            Collection<ResourcePack> packStack,
            Collection<ResourcePack> originals,
            Map<UUID, String> sourceHashes) {
        if (activeSetFingerprint.isEmpty() || activePackStackFingerprint.isEmpty()) return false;
        try {
            return activeSetFingerprint.equals(packSetFingerprint(List.copyOf(originals), sourceHashes))
                    && activePackStackFingerprint.equals(packSetFingerprint(List.copyOf(packStack), sourceHashes));
        } catch (Exception | LinkageError invalidPackSet) {
            return false;
        }
    }

    PreparedVariant preparedVariantIfCached(ResourcePack original, PackProfile profile) {
        try {
            return prepareIfCached(original, profile);
        } catch (Exception | LinkageError invalidCache) {
            return null;
        }
    }

    Map<PackProfile, PreparedVariant> preparedVariantsIfCached(ResourcePack original) {
        Map<PackProfile, PreparedVariant> result = new EnumMap<>(PackProfile.class);
        for (PackProfile profile : PREWARM_PROFILES) {
            PreparedVariant variant = preparedVariantIfCached(original, profile);
            if (variant != null) result.put(profile, variant);
        }
        return Map.copyOf(result);
    }

    ResourcePack prepared(ResourcePack original, PackProfile profile) {
        PreparedVariant variant = preparedVariantIfCached(original, profile);
        return variant == null ? null : variant.file() == null ? original : variant.pack();
    }

    Map<PackProfile, PackAuditReport> audits(ResourcePack original) {
        Map<PackProfile, PackAuditReport> result = new EnumMap<>(PackProfile.class);
        preparedVariantsIfCached(original).forEach((profile, variant) -> result.put(profile, variant.audit()));
        return result;
    }

    synchronized CacheUsage cacheUsage() throws IOException {
        return cacheUsage(MAX_CACHE_BYTES);
    }

    synchronized CacheUsage cacheUsage(long warningThresholdBytes) throws IOException {
        if (warningThresholdBytes < 0) throw new IllegalArgumentException("Pack cache warning threshold cannot be negative");
        long bytes = 0;
        try (Stream<Path> files = Files.list(cacheDirectory)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                long size = Files.size(file);
                bytes = Long.MAX_VALUE - bytes < size ? Long.MAX_VALUE : bytes + size;
            }
        }
        return new CacheUsage(bytes, bytes > warningThresholdBytes);
    }

    List<PreparedVariant> cachedVariants() {
        return new ArrayList<>(cache.values());
    }

    int size() {
        return cache.size();
    }

    List<Set<UUID>> linkedProfileGroups() {
        return linkedProfileGroups;
    }

    void clear() {
        cache.clear();
        activeSetFingerprint = "";
        activePackStackFingerprint = "";
        activeLinkedPackGroupIndexes = List.of();
        activeProtectedTexturePaths = Set.of();
        activeCrossPackTextureDedupAllowed = true;
        activeVariantIdentitiesSafe = false;
        linkedProfileGroups = List.of();
    }

    boolean variantIdentitiesSafe() {
        return activeVariantIdentitiesSafe;
    }

    private PreparedVariant remember(String key, PreparedVariant variant) {
        cache.put(key, variant);
        return variant;
    }

    private PreparedVariant loadCached(ResourcePack original, PackProfile profile, String fingerprint, String cacheKey)
            throws IOException {
        PreparedVariant prepared = cache.get(cacheKey);
        if (prepared != null && prepared.fingerprint().equals(fingerprint) &&
                (prepared.file() == null || Files.isRegularFile(prepared.file()))) {
            if (profile != PackProfile.FULL && prepared.audit().getOpaqueBedrockArchivePresent()) return null;
            return prepared;
        }
        if (profile == PackProfile.FULL) return null;

        Path output = outputFile(original, fingerprint, profile);
        if (Files.isRegularFile(output)) {
            try {
                PackAuditReport audit = auditor.audit(output);
                if (audit.getOpaqueBedrockArchivePresent()) return null;
                ResourcePack pack = packFactory.apply(output);
                return remember(cacheKey, new PreparedVariant(fingerprint, profile, pack, output, audit));
            } catch (Exception | LinkageError ignored) {
                return null;
            }
        }
        if (isLossless(profile) && Files.isRegularFile(noChangeMarker(output))) {
            PackAuditReport audit = sourceAudit(original);
            if (!audit.getOpaqueBedrockArchivePresent()) {
                return remember(cacheKey, new PreparedVariant(fingerprint, profile, original, null, audit));
            }
        }
        return null;
    }

    private Path outputFile(ResourcePack original, String fingerprint, PackProfile profile) {
        return cacheDirectory.resolve(
                original.uuid() + "-" + shortHash(fingerprint) + "-" + profile.name().toLowerCase(Locale.ROOT) + ".mcpack");
    }

    private static Path noChangeMarker(Path output) {
        return output.resolveSibling(output.getFileName() + ".no-change");
    }

    private static boolean isLossless(PackProfile profile) {
        return profile == PackProfile.OPTIMIZE_FULL;
    }

    private PackAuditReport sourceAudit(ResourcePack original) throws IOException {
        String fullFingerprint = fingerprint(original, PackProfile.FULL);
        PreparedVariant full = cache.get(cacheKey(original, fullFingerprint));
        if (full != null && full.fingerprint().equals(fullFingerprint)) return full.audit();
        Path source = materialize(original);
        try {
            return auditor.audit(source);
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private static void markNoChange(Path marker) {
        try {
            Files.createFile(marker);
        } catch (FileAlreadyExistsException ignored) {
        } catch (IOException ignored) {
            // shortcut: marker-write failure repeats lossless CPU work on restart but does not affect pack safety.
        }
    }

    static boolean losslessRewriteUseful(
            PackOptimizationReport rewrite,
            PackAuditReport source,
            PackAuditReport output) {
        if (rewrite.getOutputBytes() < rewrite.getInputBytes()) return true;
        if (source == null || output == null || !source.getTextureMetricsComplete() || !output.getTextureMetricsComplete()) {
            return false;
        }
        return output.getTextureMemoryBytes() < source.getTextureMemoryBytes()
                || source.getTextureHandleScanComplete() && output.getTextureHandleScanComplete()
                && output.getEstimatedTextureHandleCount() < source.getEstimatedTextureHandleCount()
                || output.getTextureFileCount() < source.getTextureFileCount()
                || output.getFileCount() < source.getFileCount()
                || output.getModelFileCount() < source.getModelFileCount()
                || output.getMaxModelCubes() < source.getMaxModelCubes();
    }

    static boolean losslessSetRewriteUseful(
            List<PackOptimizationReport> rewrites,
            List<PackAuditReport> sources,
            List<PackAuditReport> outputs) {
        if (rewrites.isEmpty() || rewrites.size() != sources.size() || sources.size() != outputs.size()) return false;
        long sourceBytes = 0;
        long outputBytes = 0;
        for (PackOptimizationReport rewrite : rewrites) {
            sourceBytes += rewrite.getInputBytes();
            outputBytes += rewrite.getOutputBytes();
        }
        if (outputBytes < sourceBytes) return true;

        if (sources.stream().allMatch(PackAuditReport::getTextureMetricsComplete)
                && outputs.stream().allMatch(PackAuditReport::getTextureMetricsComplete)) {
            if (sumTextureMemory(outputs) < sumTextureMemory(sources)
                    || sumTextureFiles(outputs) < sumTextureFiles(sources)
                    || sumFiles(outputs) < sumFiles(sources)
                    || sumModels(outputs) < sumModels(sources)
                    || maxModelCubes(outputs) < maxModelCubes(sources)) return true;
        }
        return sources.stream().allMatch(PackAuditReport::getTextureHandleScanComplete)
                && outputs.stream().allMatch(PackAuditReport::getTextureHandleScanComplete)
                && sumTextureHandles(outputs) < sumTextureHandles(sources);
    }

    private static long sumTextureMemory(List<PackAuditReport> reports) {
        return reports.stream().mapToLong(PackAuditReport::getTextureMemoryBytes).sum();
    }

    private static long sumTextureHandles(List<PackAuditReport> reports) {
        return reports.stream().mapToLong(PackAuditReport::getEstimatedTextureHandleCount).sum();
    }

    private static long sumTextureFiles(List<PackAuditReport> reports) {
        return reports.stream().mapToLong(PackAuditReport::getTextureFileCount).sum();
    }

    private static long sumFiles(List<PackAuditReport> reports) {
        return reports.stream().mapToLong(PackAuditReport::getFileCount).sum();
    }

    private static long sumModels(List<PackAuditReport> reports) {
        return reports.stream().mapToLong(PackAuditReport::getModelFileCount).sum();
    }

    private static int maxModelCubes(List<PackAuditReport> reports) {
        return reports.stream().mapToInt(PackAuditReport::getMaxModelCubes).max().orElse(0);
    }

    private static boolean losslessRewriteChanged(PackOptimizationReport rewrite) {
        return rewrite.getOutputBytes() < rewrite.getInputBytes()
                || rewrite.getEntriesFiltered() > 0
                || rewrite.getJsonEntriesMinified() > 0
                || rewrite.getPngEntriesOptimized() > 0
                || rewrite.getOtherRasterEntriesResized() > 0;
    }

    private static void moveIntoPlace(Path temp, Path output) throws IOException {
        try {
            Files.move(temp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String packSetFingerprint(List<ResourcePack> packs, Map<UUID, String> sourceHashes) throws Exception {
        StringBuilder identity = new StringBuilder();
        for (ResourcePack pack : packs) identity.append(pack.uuid()).append(':')
                .append(sourceHashes.getOrDefault(pack.uuid(), "")).append('\n');
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private String fingerprint(ResourcePack original, PackProfile profile) {
        String setFingerprint = profile == PackProfile.FULL ? "individual"
                : activeSetFingerprint + "-" + activePackStackFingerprint;
        return sourceHash(original) + "-" + REWRITE_REVISION + "-" + setFingerprint + "-" + profile.name();
    }

    static String sourceHash(ResourcePack original) {
        var codec = original.codec();
        synchronized (codec) {
            return HexFormat.of().formatHex(codec.sha256());
        }
    }

    private String cacheKey(ResourcePack original, String fingerprint) {
        return original.uuid() + ":" + fingerprint;
    }

    private Path materialize(ResourcePack pack) throws IOException {
        Path source = Files.createTempFile(cacheDirectory, "." + pack.uuid() + "-", ".source.mcpack");
        try (
                SeekableByteChannel channel = pack.codec().serialize();
                InputStream input = Channels.newInputStream(channel);
                OutputStream output = Files.newOutputStream(source)) {
            input.transferTo(output);
            return source;
        } catch (Throwable throwable) {
            Files.deleteIfExists(source);
            throw throwable;
        }
    }

    private Set<String> texturePaths(Path pack) throws IOException {
        Set<String> paths = new java.util.HashSet<>();
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            int scanned = 0;
            var entries = zip.stream().iterator();
            while (entries.hasNext()) {
                ZipEntry entry = entries.next();
                if (++scanned > PackAuditor.MAX_FILE_COUNT) throw new IOException("Fixed pack exceeds the bounded entry scan");
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".png.mcmeta")) {
                    paths.add(name.substring(0, name.length() - ".mcmeta".length()));
                } else if (name.endsWith(".png") || name.endsWith(".tga") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                    paths.add(name);
                }
            }
        }
        return paths;
    }

    private static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static UUID variantUuid(ResourcePack original, String fingerprint) {
        return UUID.nameUUIDFromBytes(("fettle:variant:" + original.uuid() + ":" + fingerprint)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static boolean canAssignDistinctVariantIds(List<ResourcePack> stack, Set<UUID> targetIds) {
        try {
            for (ResourcePack pack : stack) {
                var dependencies = pack.manifest().dependencies();
                if (dependencies == null) return false;
                for (var dependency : dependencies) {
                    if (dependency == null || dependency.uuid() == null || targetIds.contains(dependency.uuid())) {
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException | LinkageError unverifiedDependencies) {
            return false;
        }
    }

    record PreparedVariant(
            String fingerprint,
            PackProfile profile,
            ResourcePack pack,
            Path file,
            PackAuditReport audit) {
    }

    record CacheUsage(long bytes, boolean overWarningThreshold) {
    }
}
