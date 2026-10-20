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
import java.nio.file.LinkOption;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class PackVariantManager {
    static final String REWRITE_REVISION = "device-tier-v38";
    static final List<PackProfile> PREWARM_PROFILES = List.of(
            PackProfile.FULL, PackProfile.OPTIMIZE_FULL, PackProfile.OPTIMIZE_SEMI,
            PackProfile.OPTIMIZE_MINI, PackProfile.SAFE);
    private static final Duration CACHE_RETENTION = Duration.ofDays(30);
    private static final Duration TEMP_FILE_RETENTION = Duration.ofDays(1);
    private static final long MAX_CACHE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final Pattern GENERATED_SOURCE_TEMP = Pattern.compile(
            "^\\.[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-[A-Za-z0-9_-]+\\.source\\.mcpack$");
    private static final Pattern GENERATED_CACHE_ARTIFACT = Pattern.compile(
            "^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-[0-9a-f]{12}[A-Za-z0-9_-]{12}"
                    + "-(?:safe|optimize_full|optimize_semi|optimize_mini)\\.mcpack(?:\\.no-change)?$");
    private static final Pattern GENERATED_REWRITE_TEMP = Pattern.compile(
            "^\\.[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-[0-9a-f]{12}[A-Za-z0-9_-]{12}"
                    + "-(?:safe|optimize_full|optimize_semi|optimize_mini)\\.mcpack[A-Za-z0-9_-]{3,}\\.tmp$");
    private static final Pattern GENERATED_ZIP_TEMP = Pattern.compile("^\\.fettle-(?:raw|deflate)-[A-Za-z0-9_-]{3,}\\.tmp$");
    private final Path cacheDirectory;
    private final PackOptimizer optimizer = new PackOptimizer();
    private final PackAuditor auditor = new PackAuditor();
    private final Function<Path, ResourcePack> packFactory;
    private final Map<String, PreparedVariant> cache = new ConcurrentHashMap<>();
    private String activeSetFingerprint = "";
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
        List<ResourcePack> packs = List.copyOf(originals);
        this.activeSetFingerprint = packSetFingerprint(packs);
        List<Path> groupSources = new ArrayList<>(packs.size());
        try {
            for (ResourcePack pack : packs) groupSources.add(materialize(pack));
            this.linkedProfileGroups = optimizer.linkedPackGroups(groupSources).stream()
                    .map(group -> group.stream().map(packs::get).map(ResourcePack::uuid)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                    .toList();
        } finally {
            for (Path source : groupSources) Files.deleteIfExists(source);
        }
        Map<UUID, Map<PackProfile, PreparedVariant>> prepared = new LinkedHashMap<>();
        for (ResourcePack pack : packs) prepared.put(pack.uuid(), new EnumMap<>(PackProfile.class));

        for (PackProfile profile : profiles) {
            if (profile == PackProfile.AUTO) {
                throw new IllegalArgumentException("AUTO is a selection strategy, not a generated pack");
            }
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

        List<Path> sources = new ArrayList<>(originals.size());
        List<Path> temporarySources = new ArrayList<>(originals.size());
        List<Path> temporaryOutputs = new ArrayList<>(originals.size());
        List<Path> finalOutputs = originals.stream()
                .map(original -> outputFile(original, fingerprint(original, profile), profile))
                .toList();
        try {
            for (int index = 0; index < originals.size(); index++) {
                ResourcePack source = originals.get(index);
                Path sourceFile = null;
                if (profile != PackProfile.OPTIMIZE_FULL) {
                    String losslessFingerprint = fingerprint(source, PackProfile.OPTIMIZE_FULL);
                    PreparedVariant losslessVariant = loadCached(source, PackProfile.OPTIMIZE_FULL,
                            losslessFingerprint, cacheKey(source, losslessFingerprint));
                    if (losslessVariant != null) {
                        source = losslessVariant.pack();
                        Path losslessFile = losslessVariant.file();
                        if (losslessFile != null && Files.isRegularFile(losslessFile)) sourceFile = losslessFile;
                    }
                }
                if (sourceFile == null) {
                    sourceFile = materialize(source);
                    temporarySources.add(sourceFile);
                }
                sources.add(sourceFile);
                temporaryOutputs.add(Files.createTempFile(
                        cacheDirectory, "." + finalOutputs.get(index).getFileName(), ".tmp"));
            }

            int maxTextureDimension = switch (profile) {
                case OPTIMIZE_FULL -> 0;
                case OPTIMIZE_SEMI -> 512;
                case OPTIMIZE_MINI -> 256;
                case SAFE -> 128;
                case AUTO, FULL -> throw new IllegalStateException("AUTO/FULL have no generated pack set");
            };
            List<PackOptimizationReport> rewrites = lossless
                    ? optimizer.optimizeForLowEndSet(sources, temporaryOutputs, 0)
                    : optimizer.optimizeResizedPackSet(sources, temporaryOutputs, maxTextureDimension);
            List<PackAuditReport> sourceAudits = new ArrayList<>(sources.size());
            List<PackAuditReport> outputAudits = new ArrayList<>(sources.size());
            for (int index = 0; index < sources.size(); index++) {
                sourceAudits.add(auditor.audit(sources.get(index)));
                outputAudits.add(auditor.audit(temporaryOutputs.get(index)));
            }

            Map<UUID, PreparedVariant> prepared = new LinkedHashMap<>();
            boolean useful = !lossless || losslessSetRewriteUseful(rewrites, sourceAudits, outputAudits);
            for (int index = 0; index < originals.size(); index++) {
                ResourcePack original = originals.get(index);
                String fingerprint = fingerprint(original, profile);
                Path output = finalOutputs.get(index);
                Path temporary = temporaryOutputs.get(index);
                PackAuditReport sourceAudit = sourceAudits.get(index);

                if (lossless && (!useful || !losslessRewriteChanged(rewrites.get(index)))) {
                    Files.deleteIfExists(temporary);
                    Files.deleteIfExists(output);
                    markNoChange(noChangeMarker(output));
                    prepared.put(original.uuid(), remember(
                            cacheKey(original, fingerprint),
                            new PreparedVariant(fingerprint, profile, original, null, sourceAudit)));
                    continue;
                }

                if (lossless) Files.deleteIfExists(noChangeMarker(output));
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

    synchronized CachePruneResult pruneExpiredArtifacts(Collection<ResourcePack> activePacks) throws IOException {
        return pruneExpiredArtifacts(activePacks, MAX_CACHE_BYTES);
    }

    synchronized CachePruneResult pruneExpiredArtifacts(
            Collection<ResourcePack> activePacks,
            long maxCacheBytes) throws IOException {
        if (maxCacheBytes < 0) throw new IllegalArgumentException("Pack cache limit cannot be negative");
        Set<Path> activeArtifacts = new HashSet<>();
        for (ResourcePack original : activePacks) {
            for (PackProfile profile : PREWARM_PROFILES) {
                if (profile == PackProfile.FULL) continue;
                Path output = outputFile(original, fingerprint(original, profile), profile);
                activeArtifacts.add(output.toAbsolutePath().normalize());
                if (isLossless(profile)) {
                    activeArtifacts.add(noChangeMarker(output).toAbsolutePath().normalize());
                }
            }
        }

        Instant cutoff = Instant.now().minus(CACHE_RETENTION);
        Instant tempCutoff = Instant.now().minus(TEMP_FILE_RETENTION);
        int removed = 0;
        long activeBytes = 0;
        long inactiveBytes = 0;
        List<CacheArtifact> inactiveArtifacts = new ArrayList<>();
        try (Stream<Path> files = Files.list(cacheDirectory)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                String fileName = file.getFileName().toString();
                boolean sourceTemp = GENERATED_SOURCE_TEMP.matcher(fileName).matches();
                boolean rewriteTemp = GENERATED_REWRITE_TEMP.matcher(fileName).matches()
                        || GENERATED_ZIP_TEMP.matcher(fileName).matches();
                if ((!sourceTemp && !rewriteTemp && !GENERATED_CACHE_ARTIFACT.matcher(fileName).matches())
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                FileTime modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
                if (sourceTemp || rewriteTemp) {
                    if (modified.toInstant().isBefore(tempCutoff) && Files.deleteIfExists(file)) removed++;
                    continue;
                }
                if (activeArtifacts.contains(file.toAbsolutePath().normalize())) {
                    long size = Files.size(file);
                    activeBytes = Long.MAX_VALUE - activeBytes < size ? Long.MAX_VALUE : activeBytes + size;
                    continue;
                }
                if (modified.toInstant().isBefore(cutoff) && Files.deleteIfExists(file)) removed++;
                else {
                    long size = Files.size(file);
                    inactiveArtifacts.add(new CacheArtifact(file, size, modified));
                    inactiveBytes = Long.MAX_VALUE - inactiveBytes < size ? Long.MAX_VALUE : inactiveBytes + size;
                }
            }
        }
        inactiveArtifacts.sort(Comparator.comparing(CacheArtifact::modified)
                .thenComparing(artifact -> artifact.path().getFileName().toString()));
        long allowedInactiveBytes = Math.max(0, maxCacheBytes - Math.min(activeBytes, maxCacheBytes));
        for (CacheArtifact artifact : inactiveArtifacts) {
            if (inactiveBytes <= allowedInactiveBytes) break;
            if (Files.deleteIfExists(artifact.path())) {
                inactiveBytes = Math.max(0, inactiveBytes - artifact.sizeBytes());
                removed++;
            }
        }
        return new CachePruneResult(removed, activeBytes, inactiveBytes, activeBytes > maxCacheBytes);
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
        linkedProfileGroups = List.of();
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
            return prepared;
        }
        if (profile == PackProfile.FULL) return null;

        Path output = outputFile(original, fingerprint, profile);
        if (Files.isRegularFile(output)) {
            try {
                PackAuditReport audit = auditor.audit(output);
                ResourcePack pack = packFactory.apply(output);
                return remember(cacheKey, new PreparedVariant(fingerprint, profile, pack, output, audit));
            } catch (Exception | LinkageError invalidCache) {
                Files.deleteIfExists(output);
            }
        }
        if (isLossless(profile) && Files.isRegularFile(noChangeMarker(output))) {
            return remember(cacheKey, new PreparedVariant(fingerprint, profile, original, null, sourceAudit(original)));
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

    private String packSetFingerprint(List<ResourcePack> packs) throws Exception {
        StringBuilder identity = new StringBuilder();
        for (ResourcePack pack : packs) identity.append(pack.uuid()).append(':').append(sourceHash(pack)).append('\n');
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private String fingerprint(ResourcePack original, PackProfile profile) {
        String setFingerprint = profile == PackProfile.FULL ? "individual" : activeSetFingerprint;
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

    private static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record PreparedVariant(
            String fingerprint,
            PackProfile profile,
            ResourcePack pack,
            Path file,
            PackAuditReport audit) {
    }

    record CachePruneResult(int removedArtifacts, long activeBytes, long inactiveBytes, boolean activeExceedsLimit) {
    }

    private record CacheArtifact(Path path, long sizeBytes, FileTime modified) {
    }
}
