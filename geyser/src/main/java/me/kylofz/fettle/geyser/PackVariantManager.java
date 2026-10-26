package me.kylofz.fettle.geyser;

import me.kylofz.fettle.optimizer.PackAuditReport;
import me.kylofz.fettle.optimizer.PackAuditor;
import me.kylofz.fettle.optimizer.PackOptimizationReport;
import me.kylofz.fettle.optimizer.PackOptimizer;
import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.PathPackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.UrlPackCodec;
import org.geysermc.geyser.api.pack.option.ResourcePackOption;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PackVariantManager {
    static final String REWRITE_REVISION = "device-tier-v42";
    static final List<PackProfile> PREWARM_PROFILES = List.of(
            PackProfile.FULL, PackProfile.OPTIMIZE_FULL, PackProfile.OPTIMIZE_SEMI,
            PackProfile.OPTIMIZE_MINI, PackProfile.SAFE);
    private static final long MAX_CACHE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final Pattern GENERATED_VARIANT = Pattern.compile(
            "^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-[0-9a-f]{24}-(?:optimize_full|optimize_semi|optimize_mini|safe)\\.mcpack$");
    private static final Pattern GENERATED_SOURCE_TEMP = Pattern.compile(
            "^\\.[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-[A-Za-z0-9_-]+\\.source\\.mcpack$");
    private static final Pattern ZIP_TEMP = Pattern.compile("^\\.fettle-(?:raw|deflate)-[A-Za-z0-9_-]+\\.tmp$");
    private static final long STALE_TEMP_AGE_MILLIS = 24L * 60L * 60L * 1000L;
    private final Path cacheDirectory;
    private final PackOptimizer optimizer = new PackOptimizer();
    private final PackAuditor auditor = new PackAuditor();
    private final Function<Path, ResourcePack> packFactory;
    private final Map<String, PreparedVariant> cache = new ConcurrentHashMap<>();
    private final Map<Path, VerifiedArtifact> verifiedArtifacts = new HashMap<>();
    private String activeSetFingerprint = "";
    private String activePackStackFingerprint = "";
    private List<ResourcePack> activeOriginals = List.of();
    private Map<UUID, String> activePackOptionFingerprints = Map.of();
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

        String unsupportedReason = unsupportedCodecReason(original);
        if (unsupportedReason != null && (profile != PackProfile.FULL || !(original.codec() instanceof PathPackCodec))) {
            return unsupportedVariant(original, profile, unsupportedReason);
        }
        if (profile != PackProfile.FULL && hasContentKey(original)) {
            return unsupportedVariant(original, profile,
                    "The pack has a content key, so Fettle cannot safely change its content identity.");
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
        Path temporary = null;
        try {
            if (auditor.hasOpaqueBedrockArchive(source)) {
                return new PreparedVariant(fingerprint, profile, original, null, auditor.audit(source));
            }
            temporary = Files.createTempFile(cacheDirectory, "." + output.getFileName(), ".tmp");
            PackOptimizationReport rewrite = switch (profile) {
                case AUTO, FULL -> throw new IllegalStateException("AUTO/FULL have no generated pack");
                case OPTIMIZE_FULL -> optimizer.optimizeForLowEnd(source, temporary);
                case OPTIMIZE_SEMI -> optimizer.optimizeMini(source, temporary, List.of(), 512);
                case OPTIMIZE_MINI -> optimizer.optimizeMini(source, temporary, List.of(), 256);
                case SAFE -> optimizer.optimizeSafe(source, temporary, 128);
            };
            PackAuditReport audit = auditor.audit(temporary);
            if (lossless && rewrite.getOutputBytes() >= rewrite.getInputBytes()) {
                PackAuditReport sourceAudit = auditor.audit(source);
                if (!losslessRewriteUseful(rewrite, sourceAudit, audit)) {
                    Files.deleteIfExists(temporary);
                    temporary = null;
                    markNoChange(noChangeMarker(output), fingerprint);
                    return remember(cacheKey, new PreparedVariant(fingerprint, profile, original, null, sourceAudit));
                }
            }

            moveIntoPlace(temporary, output);
            temporary = null;
            ResourcePack pack = packFactory.apply(output);
            writeIntegrity(output, fingerprint);
            return remember(cacheKey, new PreparedVariant(fingerprint, profile, pack, output, audit));
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
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
        return prepareSet(originals, packStack, profiles, Map.of());
    }

    synchronized Map<UUID, Map<PackProfile, PreparedVariant>> prepareSet(
            Collection<ResourcePack> originals,
            Collection<ResourcePack> packStack,
            Collection<PackProfile> profiles,
            Map<UUID, ? extends Collection<ResourcePackOption<?>>> packOptions) throws Exception {
        List<ResourcePack> packs = List.copyOf(originals);
        List<ResourcePack> stack = List.copyOf(packStack);
        this.activeOriginals = packs;
        this.activePackOptionFingerprints = optionFingerprints(packOptions);
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
        this.activeSetFingerprint = packSetFingerprint(packs, stackHashes, this.activePackOptionFingerprints);
        this.activePackStackFingerprint = packSetFingerprint(stack, stackHashes, this.activePackOptionFingerprints);
        Set<UUID> targetIds = packs.stream().map(ResourcePack::uuid).collect(java.util.stream.Collectors.toSet());
        this.activeVariantIdentitiesSafe = canAssignDistinctVariantIds(stack, targetIds);
        if (stack.stream().anyMatch(pack -> unsupportedCodecReason(pack) != null || hasContentKey(pack))) {
            this.activeLinkedPackGroupIndexes = List.of();
            this.linkedProfileGroups = List.of();
            Map<UUID, Map<PackProfile, PreparedVariant>> originalsOnly = new LinkedHashMap<>();
            for (ResourcePack pack : packs) {
                originalsOnly.put(pack.uuid(), Map.of(PackProfile.FULL, prepare(pack, PackProfile.FULL)));
            }
            return Map.copyOf(originalsOnly);
        }
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
        if (originals.isEmpty()) return Map.of();
        boolean lossless = isLossless(profile);
        Path completionRecord = setCompletionFile(originals, profile);
        Map<UUID, PreparedVariant> cached = new LinkedHashMap<>();
        boolean allCached = true;
        for (ResourcePack original : originals) {
            String fingerprint = fingerprint(original, profile);
            PreparedVariant variant = loadCached(
                    original, profile, fingerprint, cacheKey(original, fingerprint));
            if (variant == null) allCached = false;
            else cached.put(original.uuid(), variant);
        }
        if (allCached) {
            if (!isValidSetCompletion(completionRecord, originals, profile)) {
                writeSetCompletion(completionRecord, originals, profile);
            }
            return Map.copyOf(cached);
        }
        Files.deleteIfExists(completionRecord);

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
                    markNoChange(noChangeMarker(output), fingerprint);
                    prepared.put(original.uuid(), remember(
                            cacheKey(original, fingerprint),
                            new PreparedVariant(fingerprint, profile, original, null, sourceAudit)));
                    continue;
                }

                moveIntoPlace(temporary, output);
                ResourcePack pack = packFactory.apply(output);
                writeIntegrity(output, fingerprint);
                prepared.put(original.uuid(), remember(
                        cacheKey(original, fingerprint),
                        new PreparedVariant(fingerprint, profile, pack, output, outputAudits.get(index))));
            }
            Map<UUID, PreparedVariant> result = Map.copyOf(prepared);
            if (!completeSetMembers(originals, profile, result)) {
                throw new IOException("Generated linked pack set failed its publication checks");
            }
            writeSetCompletion(completionRecord, originals, profile);
            return result;
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
        String unsupportedReason = unsupportedCodecReason(original);
        if (unsupportedReason != null && (profile != PackProfile.FULL || !(original.codec() instanceof PathPackCodec))) {
            return unsupportedVariant(original, profile, unsupportedReason);
        }
        if (profile != PackProfile.FULL && hasContentKey(original)) {
            return unsupportedVariant(original, profile,
                    "The pack has a content key, so Fettle cannot safely change its content identity.");
        }
        String fingerprint = fingerprint(original, profile);
        return loadCached(original, profile, fingerprint, cacheKey(original, fingerprint));
    }

    synchronized boolean isPreparedVariantValid(PreparedVariant variant) {
        return variant.file() == null || isVerifiedArtifact(variant.file(), variant.fingerprint());
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
        return matchesPreparedSet(packStack, originals, sourceHashes, Map.of());
    }

    synchronized boolean matchesPreparedSet(
            Collection<ResourcePack> packStack,
            Collection<ResourcePack> originals,
            Map<UUID, String> sourceHashes,
            Map<UUID, String> optionFingerprints) {
        if (activeSetFingerprint.isEmpty() || activePackStackFingerprint.isEmpty()) return false;
        try {
            return activeSetFingerprint.equals(packSetFingerprint(List.copyOf(originals), sourceHashes, optionFingerprints))
                    && activePackStackFingerprint.equals(packSetFingerprint(List.copyOf(packStack), sourceHashes, optionFingerprints));
        } catch (Exception | LinkageError invalidPackSet) {
            return false;
        }
    }

    PreparedVariant preparedVariantIfCached(ResourcePack original, PackProfile profile) {
        if (profile != PackProfile.FULL && activeOriginals.stream().anyMatch(pack -> pack.uuid().equals(original.uuid()))
                && !activeSetCompletionValid(profile)) return null;
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

    synchronized CacheCleanup pruneGeneratedArtifacts() throws IOException {
        return pruneGeneratedArtifacts(MAX_CACHE_BYTES);
    }

    synchronized CacheCleanup pruneGeneratedArtifacts(long retainedByteLimit) throws IOException {
        if (retainedByteLimit < 0) throw new IllegalArgumentException("Pack cache retention limit cannot be negative");
        Set<Path> activeFiles = cache.values().stream()
                .map(PreparedVariant::file)
                .filter(java.util.Objects::nonNull)
                .map(PackVariantManager::normalizedPath)
                .collect(java.util.stream.Collectors.toSet());
        if (!activeOriginals.isEmpty()) {
            PREWARM_PROFILES.stream().filter(profile -> profile != PackProfile.FULL)
                    .map(profile -> setCompletionFile(activeOriginals, profile))
                    .map(PackVariantManager::normalizedPath).forEach(activeFiles::add);
        }
        Map<String, List<Path>> pathsByVariant = new HashMap<>();
        List<CachedFile> staleTemporaryFiles = new ArrayList<>();
        long staleBefore = System.currentTimeMillis() - STALE_TEMP_AGE_MILLIS;
        try (Stream<Path> files = Files.list(cacheDirectory)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                String name = file.getFileName().toString();
                String variantName = generatedVariantName(name);
                if (variantName != null) {
                    pathsByVariant.computeIfAbsent(variantName, ignored -> new ArrayList<>()).add(file);
                } else if (isOwnedTemporaryArtifact(name)) {
                    try {
                        if (Files.getLastModifiedTime(file, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis() < staleBefore) {
                            staleTemporaryFiles.add(new CachedFile(file, Files.size(file)));
                        }
                    } catch (IOException unavailableMetadata) {
                        // Keep temporary files whose age or size cannot be checked safely.
                    }
                }
            }
        }

        List<GeneratedCacheGroup> groups = new ArrayList<>(pathsByVariant.size());
        long retainedBytes = 0;
        for (Map.Entry<String, List<Path>> entry : pathsByVariant.entrySet()) {
            List<CachedFile> files = new ArrayList<>(entry.getValue().size());
            long groupBytes = 0;
            long newestModification = Long.MIN_VALUE;
            boolean active = activeFiles.contains(normalizedPath(cacheDirectory.resolve(entry.getKey())))
                    || entry.getValue().stream().map(PackVariantManager::normalizedPath).anyMatch(activeFiles::contains);
            for (Path file : entry.getValue()) {
                try {
                    long size = Files.size(file);
                    FileTime modified = Files.getLastModifiedTime(file, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    files.add(new CachedFile(file, size));
                    groupBytes = saturatedAdd(groupBytes, size);
                    newestModification = Math.max(newestModification, modified.toMillis());
                } catch (IOException unavailableMetadata) {
                    active = true;
                    files.add(new CachedFile(file, 0));
                    newestModification = Long.MAX_VALUE;
                }
            }
            retainedBytes = saturatedAdd(retainedBytes, groupBytes);
            groups.add(new GeneratedCacheGroup(entry.getKey(), files, groupBytes, newestModification, active));
        }
        groups.sort(Comparator.comparingLong(GeneratedCacheGroup::newestModification)
                .thenComparing(GeneratedCacheGroup::variantName));

        long deletedBytes = 0;
        int deletedFiles = 0;
        int failedDeletes = 0;
        for (CachedFile file : staleTemporaryFiles) {
            try {
                if (Files.deleteIfExists(file.path())) {
                    deletedBytes = saturatedAdd(deletedBytes, file.bytes());
                    deletedFiles++;
                }
            } catch (IOException failedDelete) {
                failedDeletes++;
            }
        }
        for (GeneratedCacheGroup group : groups) {
            if (retainedBytes <= retainedByteLimit) break;
            if (group.active()) continue;
            List<CachedFile> files = group.files().stream()
                    .sorted(Comparator.comparing(file -> file.path().getFileName().toString().equals(group.variantName())))
                    .toList();
            long groupDeletedBytes = 0;
            for (CachedFile file : files) {
                try {
                    if (Files.deleteIfExists(file.path())) {
                        deletedBytes = saturatedAdd(deletedBytes, file.bytes());
                        groupDeletedBytes = saturatedAdd(groupDeletedBytes, file.bytes());
                        deletedFiles++;
                    }
                } catch (IOException failedDelete) {
                    failedDeletes++;
                }
            }
            retainedBytes = Math.max(0, retainedBytes - groupDeletedBytes);
        }
        return new CacheCleanup(deletedFiles, deletedBytes, retainedBytes, failedDeletes);
    }

    private static String generatedVariantName(String name) {
        String candidate = name;
        if (candidate.endsWith(".integrity")) candidate = candidate.substring(0, candidate.length() - ".integrity".length());
        else if (candidate.endsWith(".no-change")) candidate = candidate.substring(0, candidate.length() - ".no-change".length());
        else if (candidate.endsWith(".set-complete")) candidate = candidate.substring(0, candidate.length() - ".set-complete".length());
        return GENERATED_VARIANT.matcher(candidate).matches() ? candidate : null;
    }

    private static boolean isOwnedTemporaryArtifact(String name) {
        if (ZIP_TEMP.matcher(name).matches() || GENERATED_SOURCE_TEMP.matcher(name).matches()) return true;
        String candidate = name;
        while (candidate.startsWith(".")) candidate = candidate.substring(1);
        int marker = candidate.indexOf(".mcpack");
        if (marker < 0) return false;
        String base = candidate.substring(0, marker + ".mcpack".length());
        String suffix = candidate.substring(marker + ".mcpack".length());
        return GENERATED_VARIANT.matcher(base).matches()
                && (suffix.matches("[A-Za-z0-9._-]+\\.tmp")
                || suffix.matches("\\.no-change[A-Za-z0-9_-]+\\.tmp")
                || suffix.matches("\\.set-complete[A-Za-z0-9_-]+\\.tmp"));
    }

    private static Path normalizedPath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static long saturatedAdd(long first, long second) {
        return Long.MAX_VALUE - first < second ? Long.MAX_VALUE : first + second;
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

    synchronized void clear() {
        cache.clear();
        verifiedArtifacts.clear();
        activeSetFingerprint = "";
        activePackStackFingerprint = "";
        activeOriginals = List.of();
        activePackOptionFingerprints = Map.of();
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
                (prepared.file() == null || isVerifiedArtifact(prepared.file(), fingerprint))) {
            if (profile != PackProfile.FULL && prepared.audit().getOpaqueBedrockArchivePresent()) return null;
            return prepared;
        }
        if (profile == PackProfile.FULL) return null;

        Path output = outputFile(original, fingerprint, profile);
        if (isVerifiedArtifact(output, fingerprint)) {
            try {
                PackAuditReport audit = auditor.audit(output);
                if (audit.getOpaqueBedrockArchivePresent()) return null;
                ResourcePack pack = packFactory.apply(output);
                return remember(cacheKey, new PreparedVariant(fingerprint, profile, pack, output, audit));
            } catch (Exception | LinkageError ignored) {
                return null;
            }
        }
        if (isLossless(profile) && isValidNoChangeMarker(noChangeMarker(output), fingerprint)) {
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

    private PreparedVariant unsupportedVariant(ResourcePack original, PackProfile profile, String reason) {
        String fingerprint = sourceHash(original) + "-" + REWRITE_REVISION + "-unsupported-" + profile.name();
        return remember(cacheKey(original, fingerprint), new PreparedVariant(
                fingerprint, profile, original, null, PackAuditReport.unsupported(reason)));
    }

    private static String unsupportedCodecReason(ResourcePack pack) {
        if (pack.codec() instanceof UrlPackCodec) {
            return "URL-delivered packs cannot be rewritten without changing Geyser CDN delivery behavior.";
        }
        if (!(pack.codec() instanceof PathPackCodec)) {
            return "Fettle can only analyze Geyser path-backed resource packs.";
        }
        return null;
    }

    private static boolean hasContentKey(ResourcePack pack) {
        String contentKey = pack.contentKey();
        return contentKey != null && !contentKey.isBlank();
    }

    private Path setCompletionFile(List<ResourcePack> originals, PackProfile profile) {
        ResourcePack anchor = originals.get(0);
        Path output = outputFile(anchor, fingerprint(anchor, profile), profile);
        return output.resolveSibling(output.getFileName() + ".set-complete");
    }

    private String setCompletionValue(List<ResourcePack> originals, PackProfile profile) throws Exception {
        StringBuilder identity = new StringBuilder(REWRITE_REVISION).append('\n')
                .append(profile.name()).append('\n')
                .append(activeSetFingerprint).append('\n')
                .append(activePackStackFingerprint).append('\n');
        for (ResourcePack original : originals) {
            identity.append(original.uuid()).append(':').append(fingerprint(original, profile)).append('\n');
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.toString().getBytes(StandardCharsets.UTF_8))) + "\n";
    }

    private boolean isValidSetCompletion(Path marker, List<ResourcePack> originals, PackProfile profile) throws Exception {
        return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.size(marker) <= 128
                && Files.readString(marker, StandardCharsets.UTF_8).equals(setCompletionValue(originals, profile));
    }

    private void writeSetCompletion(Path marker, List<ResourcePack> originals, PackProfile profile) throws Exception {
        Path temporary = Files.createTempFile(cacheDirectory, "." + marker.getFileName() + "-", ".tmp");
        try {
            Files.writeString(temporary, setCompletionValue(originals, profile), StandardCharsets.UTF_8);
            moveIntoPlace(temporary, marker);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean completeSetMembers(
            List<ResourcePack> originals,
            PackProfile profile,
            Map<UUID, PreparedVariant> variants) {
        if (variants.size() != originals.size()) return false;
        for (ResourcePack original : originals) {
            PreparedVariant variant = variants.get(original.uuid());
            if (variant == null || !variant.fingerprint().equals(fingerprint(original, profile))
                    || variant.audit().getOpaqueBedrockArchivePresent()) return false;
            if (variant.file() != null) {
                if (!isVerifiedArtifact(variant.file(), variant.fingerprint())) return false;
            } else if (profile != PackProfile.OPTIMIZE_FULL || variant.pack() != original) {
                return false;
            }
        }
        return true;
    }

    private boolean activeSetCompletionValid(PackProfile profile) {
        if (activeOriginals.isEmpty()) return false;
        try {
            return isValidSetCompletion(setCompletionFile(activeOriginals, profile), activeOriginals, profile);
        } catch (Exception | LinkageError invalidCompletionRecord) {
            return false;
        }
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

    private void markNoChange(Path marker, String fingerprint) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(cacheDirectory, "." + marker.getFileName(), ".tmp");
            Files.writeString(temporary, fingerprint + "\n", StandardCharsets.UTF_8);
            moveIntoPlace(temporary, marker);
            temporary = null;
        } catch (IOException ignored) {
            // shortcut: marker-write failure repeats lossless CPU work on restart but does not affect pack safety.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void writeIntegrity(Path output, String fingerprint) throws IOException {
        Path temporary = Files.createTempFile(cacheDirectory, "." + output.getFileName(), ".integrity.tmp");
        try {
            Files.writeString(temporary, fingerprint + "\n" + sha256(output) + "\n", StandardCharsets.UTF_8);
            moveIntoPlace(temporary, integrityFile(output));
            rememberVerifiedArtifact(output, fingerprint);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean isVerifiedArtifact(Path output, String fingerprint) {
        Path integrity = integrityFile(output);
        Path key = normalizedPath(output);
        try {
            FileStamp outputStamp = fileStamp(output);
            FileStamp integrityStamp = fileStamp(integrity);
            if (outputStamp == null || integrityStamp == null || integrityStamp.size() > 2048) {
                verifiedArtifacts.remove(key);
                return false;
            }
            VerifiedArtifact remembered = verifiedArtifacts.get(key);
            if (remembered != null && remembered.fingerprint().equals(fingerprint)
                    && remembered.output().equals(outputStamp) && remembered.integrity().equals(integrityStamp)) {
                return true;
            }
            boolean valid = Files.readString(integrity, StandardCharsets.UTF_8)
                    .equals(fingerprint + "\n" + sha256(output) + "\n");
            if (valid) verifiedArtifacts.put(key, new VerifiedArtifact(fingerprint, outputStamp, integrityStamp));
            else verifiedArtifacts.remove(key);
            return valid;
        } catch (IOException invalidArtifact) {
            verifiedArtifacts.remove(key);
            return false;
        }
    }

    private void rememberVerifiedArtifact(Path output, String fingerprint) {
        try {
            FileStamp outputStamp = fileStamp(output);
            FileStamp integrityStamp = fileStamp(integrityFile(output));
            if (outputStamp != null && integrityStamp != null) {
                verifiedArtifacts.put(normalizedPath(output),
                        new VerifiedArtifact(fingerprint, outputStamp, integrityStamp));
            }
        } catch (IOException ignored) {
            // A missing metadata snapshot only makes the next cache read verify the digest again.
        }
    }

    private static FileStamp fileStamp(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) return null;
        return new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    private static boolean isValidNoChangeMarker(Path marker, String fingerprint) {
        try {
            return Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.size(marker) <= 2048
                    && Files.readString(marker, StandardCharsets.UTF_8).equals(fingerprint + "\n");
        } catch (IOException invalidMarker) {
            return false;
        }
    }

    private static Path integrityFile(Path output) {
        return output.resolveSibling(output.getFileName() + ".integrity");
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
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
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Atomic cache publication is not supported for " + output.getFileName(), unsupported);
        }
    }

    private String packSetFingerprint(
            List<ResourcePack> packs,
            Map<UUID, String> sourceHashes,
            Map<UUID, String> optionFingerprints) throws Exception {
        StringBuilder identity = new StringBuilder();
        for (ResourcePack pack : packs) {
            identity.append(pack.uuid()).append(':')
                .append(sourceHashes.getOrDefault(pack.uuid(), "")).append(':')
                .append(optionFingerprints.getOrDefault(pack.uuid(), "")).append('\n');
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static String optionFingerprint(Collection<ResourcePackOption<?>> options) {
        return options.stream().filter(java.util.Objects::nonNull)
                .map(option -> option.type().name() + "=" + String.valueOf(option.value()))
                .sorted().collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Map<UUID, String> optionFingerprints(
            Map<UUID, ? extends Collection<ResourcePackOption<?>>> options) {
        if (options == null || options.isEmpty()) return Map.of();
        Map<UUID, String> fingerprints = new HashMap<>();
        options.forEach((uuid, values) -> fingerprints.put(uuid,
                optionFingerprint(values == null ? List.of() : values)));
        return Map.copyOf(fingerprints);
    }

    private String fingerprint(ResourcePack original, PackProfile profile) {
        String setFingerprint = profile == PackProfile.FULL ? "individual"
                : activeSetFingerprint + "-" + activePackStackFingerprint;
        return sourceHash(original) + "-" + REWRITE_REVISION + "-" + setFingerprint + "-" + profile.name();
    }

    static String sourceHash(ResourcePack original) {
        var codec = original.codec();
        if (!(codec instanceof PathPackCodec)) {
            String providerIdentity = codec instanceof UrlPackCodec urlCodec
                    ? "url:" + urlCodec.url()
                    : "unsupported:" + codec.getClass().getName() + ":" + System.identityHashCode(original);
            String identity = original.uuid() + ":" + providerIdentity + ":" + String.valueOf(original.contentKey());
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(identity.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }
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

    record CacheCleanup(int deletedFiles, long deletedBytes, long retainedGeneratedBytes, int failedDeletes) {
    }

    private record CachedFile(Path path, long bytes) {
    }

    private record FileStamp(long size, FileTime modified, Object fileKey) {
    }

    private record VerifiedArtifact(String fingerprint, FileStamp output, FileStamp integrity) {
    }

    private record GeneratedCacheGroup(
            String variantName,
            List<CachedFile> files,
            long bytes,
            long newestModification,
            boolean active) {
    }
}
