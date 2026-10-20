package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import me.kylofz.fettle.optimizer.PackAuditReport;
import me.kylofz.fettle.optimizer.PackOptimizationReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import me.kylofz.fettle.optimizer.RawZipBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackVariantManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsLosslessVariantWhenDecodedResourcesImproveDespiteLargerArchive() {
        PackOptimizationReport rewrite = new PackOptimizationReport(100, 110, 10, 10, 0, 0, 0, 0);
        PackAuditReport source = audit(100, 10, 4, 200, 2, 20, true);
        PackAuditReport optimized = audit(110, 10, 4, 150, 2, 20, true);
        PackAuditReport unchanged = audit(110, 10, 4, 200, 2, 20, true);

        assertTrue(PackVariantManager.losslessRewriteUseful(rewrite, source, optimized));
        assertFalse(PackVariantManager.losslessRewriteUseful(rewrite, source, unchanged));
        assertFalse(PackVariantManager.losslessRewriteUseful(rewrite, source, audit(110, 10, 4, 100, 2, 20, false)));
    }

    @Test
    void preparesMeasuredVariantsAndReusesCachedFiles() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("source.mcpack");
        writePack(sourcePath);
        UUID packId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        ResourcePack source = filePack(sourcePath, packId);
        Path cacheDirectory = temporaryDirectory.resolve("pack-cache");

        PackVariantManager manager = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        PackVariantManager.PreparedVariant original = manager.prepare(source, PackProfile.FULL);
        PackVariantManager.PreparedVariant lossless = manager.prepare(source, PackProfile.OPTIMIZE_FULL);
        PackVariantManager.PreparedVariant semi = manager.prepare(source, PackProfile.OPTIMIZE_SEMI);
        PackVariantManager.PreparedVariant mini = manager.prepare(source, PackProfile.OPTIMIZE_MINI);
        PackVariantManager.PreparedVariant safe = manager.prepare(source, PackProfile.SAFE);

        assertEquals(1024L * 512L * 4L, original.audit().getTextureMemoryBytes());
        assertEquals(original.audit().getTextureMemoryBytes(), lossless.audit().getTextureMemoryBytes());
        assertEquals(512L * 256L * 4L, semi.audit().getTextureMemoryBytes());
        assertEquals(256L * 128L * 4L, mini.audit().getTextureMemoryBytes());
        assertEquals(128L * 64L * 4L, safe.audit().getTextureMemoryBytes());
        assertTrue(mini.audit().getTextureMetricsComplete());
        assertTrue(safe.audit().getTextureMetricsComplete());
        assertTrue(safe.audit().getTextureMemoryBytes() < mini.audit().getTextureMemoryBytes());
        assertEquals(5, manager.audits(source).size());
        assertEquals(source, manager.prepared(source, PackProfile.FULL));
        assertNotNull(manager.prepared(source, PackProfile.SAFE));
        assertTrue(Files.isRegularFile(safe.file()));

        PackVariantManager restartedManager = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        PackVariantManager.PreparedVariant reused = restartedManager.prepare(source, PackProfile.SAFE);
        assertEquals(safe.file(), reused.file());
        assertEquals(safe.audit().getTextureMemoryBytes(), reused.audit().getTextureMemoryBytes());
    }

    @Test
    void preparesAndCachesLinkedLosslessPackVariantsTogether() throws Exception {
        UUID baseId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        UUID overlayId = UUID.fromString("3c3de3ee-beb9-5f70-a3e5-2b99622b1c9f");
        Path basePath = temporaryDirectory.resolve("linked-base.mcpack");
        Path overlayPath = temporaryDirectory.resolve("linked-overlay.mcpack");
        BufferedImage frame = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
        for (int y = 8; y < 600; y++) for (int x = 8; x < 600; x++) frame.setRGB(x, y, 0xff34aabb);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(frame, "png", png);
        byte[] image = png.toByteArray();
        String geometry = "{\"format_version\":\"1.21.0\",\"minecraft:geometry\":[{\"description\":{"
                + "\"identifier\":\"geometry.scf.shared\",\"texture_width\":1024,\"texture_height\":1024},"
                + "\"bones\":[{\"name\":\"root\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],"
                + "\"uv\":{\"north\":{\"uv\":[8,8],\"uv_size\":[592,592]}}}]}]}]}";
        String attachable = "{\"minecraft:attachable\":{\"description\":{"
                + "\"geometry\":{\"default\":\"geometry.scf.shared\"},"
                + "\"textures\":{\"default\":\"textures/stitched/default\","
                + "\"frame_0\":\"textures/stitched/frame_0\",\"frame_1\":\"textures/stitched/frame_1\","
                + "\"enchanted\":\"textures/misc/glint\"},"
                + "\"materials\":{\"default\":\"entity_alphatest\",\"enchanted\":\"entity_alphatest_glint\"}}}}";
        writeEntries(basePath, Map.of(
                "manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "attachables/base-reference.json", "{\"geometry\":{\"default\":\"geometry.scf.shared\"},"
                        .concat("\"textures\":{\"default\":\"textures/stitched/default\"}}")
                        .getBytes(StandardCharsets.UTF_8),
                "models/entity/shared.geo.json", geometry.getBytes(StandardCharsets.UTF_8)));
        writeEntries(overlayPath, Map.of(
                "manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "attachables/frames.json", attachable.getBytes(StandardCharsets.UTF_8),
                "textures/stitched/default.png", image,
                "textures/stitched/frame_0.png", image,
                "textures/stitched/frame_1.png", image,
                "textures/misc/glint.png", image));

        ResourcePack base = filePack(basePath, baseId);
        ResourcePack overlay = filePack(overlayPath, overlayId);
        Path cacheDirectory = temporaryDirectory.resolve("linked-cache");
        java.util.function.Function<Path, ResourcePack> factory = path -> filePack(
                path, path.getFileName().toString().startsWith(baseId.toString()) ? baseId : overlayId);
        PackVariantManager manager = new PackVariantManager(cacheDirectory, factory);
        List<PackProfile> profiles = List.of(PackProfile.FULL, PackProfile.OPTIMIZE_FULL,
                PackProfile.OPTIMIZE_SEMI, PackProfile.OPTIMIZE_MINI, PackProfile.SAFE);

        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> prepared =
                manager.prepareSet(List.of(base, overlay), profiles);
        assertEquals(List.of(java.util.Set.of(baseId, overlayId)), manager.linkedProfileGroups());
        Path baseVariant = prepared.get(baseId).get(PackProfile.OPTIMIZE_FULL).file();
        Path overlayVariant = prepared.get(overlayId).get(PackProfile.OPTIMIZE_FULL).file();
        assertNotNull(baseVariant);
        assertNotNull(overlayVariant);
        try (ZipFile zip = new ZipFile(baseVariant.toFile())) {
            String rewritten = new String(zip.getInputStream(zip.getEntry("models/entity/shared.geo.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(rewritten.contains("\"texture_width\":602"));
            assertTrue(rewritten.contains("\"texture_height\":602"));
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().startsWith("textures/entity/fettle/atlases/")));
        }
        try (ZipFile zip = new ZipFile(overlayVariant.toFile())) {
            assertNull(zip.getEntry("textures/stitched/default.png"));
            assertNull(zip.getEntry("textures/stitched/frame_0.png"));
            assertNull(zip.getEntry("textures/stitched/frame_1.png"));
            String frames = new String(zip.getInputStream(zip.getEntry("attachables/frames.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(frames.contains("textures/entity/fettle/atlases/"));
            assertEquals(1024, ImageIO.read(zip.getInputStream(zip.getEntry("textures/misc/glint.png"))).getWidth());
        }

        int[] limits = {512, 256, 128};
        PackProfile[] resizedProfiles = {PackProfile.OPTIMIZE_SEMI, PackProfile.OPTIMIZE_MINI, PackProfile.SAFE};
        for (int index = 0; index < limits.length; index++) {
            int limit = limits[index];
            PackProfile profile = resizedProfiles[index];
            Path baseProfile = prepared.get(baseId).get(profile).file();
            Path overlayProfile = prepared.get(overlayId).get(profile).file();
            try (ZipFile zip = new ZipFile(baseProfile.toFile())) {
                String rewritten = new String(zip.getInputStream(zip.getEntry("models/entity/shared.geo.json")).readAllBytes(),
                        StandardCharsets.UTF_8);
                assertTrue(rewritten.contains("\"texture_width\":" + limit));
                assertTrue(rewritten.contains("\"texture_height\":" + limit));
                String atlas = zip.stream().map(entry -> entry.getName())
                        .filter(name -> name.startsWith("textures/entity/fettle/atlases/")).findFirst().orElseThrow();
                BufferedImage resized = ImageIO.read(zip.getInputStream(zip.getEntry(atlas)));
                assertEquals(limit, resized.getWidth());
                assertEquals(limit, resized.getHeight());
            }
            try (ZipFile zip = new ZipFile(overlayProfile.toFile())) {
                assertNull(zip.getEntry("textures/stitched/default.png"));
                assertNull(zip.getEntry("textures/stitched/frame_0.png"));
                assertNull(zip.getEntry("textures/stitched/frame_1.png"));
                String frames = new String(zip.getInputStream(zip.getEntry("attachables/frames.json")).readAllBytes(),
                        StandardCharsets.UTF_8);
                assertTrue(frames.contains("textures/entity/fettle/atlases/"));
            }
        }

        assertTrue(Files.isRegularFile(baseVariant));
        assertTrue(Files.isRegularFile(overlayVariant));

        PackVariantManager restarted = new PackVariantManager(cacheDirectory, factory);
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> reused =
                restarted.prepareSet(List.of(base, overlay), profiles);
        assertEquals(baseVariant, reused.get(baseId).get(PackProfile.OPTIMIZE_FULL).file());
        assertEquals(overlayVariant, reused.get(overlayId).get(PackProfile.OPTIMIZE_FULL).file());
    }

    @Test
    void cacheProbeLoadsOfflineVariantsButNeverBuildsAColdMiss() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("source.mcpack");
        writePack(sourcePath);
        UUID packId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        AtomicBoolean serializationAllowed = new AtomicBoolean(true);
        ResourcePack source = filePack(sourcePath, packId, serializationAllowed);
        Path cacheDirectory = temporaryDirectory.resolve("shared-cache");

        PackVariantManager offline = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        offline.prepare(source, PackProfile.FULL);
        PackVariantManager.PreparedVariant safe = offline.prepare(source, PackProfile.SAFE);
        serializationAllowed.set(false);

        PackVariantManager runtime = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        PackVariantManager.PreparedVariant cachedSafe = runtime.prepareIfCached(source, PackProfile.SAFE);

        assertNotNull(cachedSafe);
        assertEquals(safe.file(), cachedSafe.file());
        assertNull(runtime.prepareIfCached(source, PackProfile.OPTIMIZE_MINI));

        serializationAllowed.set(true);
        offline.prepare(source, PackProfile.OPTIMIZE_MINI);
        serializationAllowed.set(false);
        assertNotNull(runtime.prepareIfCached(source, PackProfile.OPTIMIZE_MINI));
    }

    @Test
    void packSetAuditSeparatesOptimizedTargetsFromFixedPackUsage() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("pack-set.mcpack");
        writePack(sourcePath);
        UUID targetId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        UUID fixedId = UUID.fromString("3c3de3ee-beb9-5f70-a3e5-2b99622b1c9f");
        ResourcePack target = filePack(sourcePath, targetId);
        ResourcePack fixed = filePack(sourcePath, fixedId);
        PackVariantManager manager = new PackVariantManager(
                temporaryDirectory.resolve("pack-set-cache"), path -> filePack(path, targetId));
        PackAuditReport fixedReport = manager.prepare(fixed, PackProfile.FULL).audit();
        manager.prepare(target, PackProfile.FULL);

        PackSetAudit audit = PackSetAudit.collect(
                List.of(target, fixed), List.of(target), pack -> pack.uuid().equals(targetId), manager);

        assertEquals(List.of(target), audit.targetPacks());
        assertTrue(audit.preparedVariants().get(targetId).containsKey(PackProfile.FULL));
        assertFalse(audit.preparedVariants().containsKey(fixedId));
        assertTrue(audit.variantReports().get(targetId).containsKey(PackProfile.FULL));
        assertFalse(audit.variantReports().containsKey(fixedId));
        assertEquals(fixedReport.getTextureMemoryBytes(), audit.fixedTextureMemoryBytes());
        assertEquals(fixedReport.getTextureFileCount(), audit.fixedTextureFileCount());
        assertTrue(audit.fixedMetricsComplete());
    }

    @Test
    void packSetAuditSnapshotRequiresSamePackInstancesAndSourceContents() throws Exception {
        UUID targetId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        UUID fixedId = UUID.fromString("3c3de3ee-beb9-5f70-a3e5-2b99622b1c9f");
        Path targetPath = temporaryDirectory.resolve("target.mcpack");
        Path fixedPath = temporaryDirectory.resolve("fixed.mcpack");
        Files.writeString(targetPath, "target-v1");
        Files.writeString(fixedPath, "fixed-v1");
        ResourcePack target = filePack(targetPath, targetId);
        ResourcePack fixed = filePack(fixedPath, fixedId);
        var matcher = (java.util.function.Predicate<ResourcePack>) pack -> pack.uuid().equals(targetId);

        PackSetAudit snapshot = PackSetAudit.forSession(null, List.of(target, fixed), matcher, null);

        assertSame(snapshot, PackSetAudit.forSession(snapshot, List.of(target, fixed), matcher, null));
        assertFalse(snapshot == PackSetAudit.forSession(snapshot, List.of(fixed, target), matcher, null));

        Files.writeString(targetPath, "target-content-changed");
        PackSetAudit changedContent = PackSetAudit.forSession(snapshot, List.of(target, fixed), matcher, null);
        assertFalse(snapshot == changedContent);

        ResourcePack changedTarget = filePack(temporaryDirectory.resolve("changed-target.mcpack"), targetId);
        Files.writeString(temporaryDirectory.resolve("changed-target.mcpack"), "target-v2");
        PackSetAudit changed = PackSetAudit.forSession(changedContent, List.of(changedTarget, fixed), matcher, null);
        assertFalse(snapshot == changed);
        assertSame(changedTarget, changed.targetPacks().get(0));
    }

    @Test
    void prunesOnlyExpiredGeneratedArtifactsOutsideTheActivePackSet() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("source.mcpack");
        writePack(sourcePath);
        UUID packId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        ResourcePack source = filePack(sourcePath, packId);
        Path cacheDirectory = temporaryDirectory.resolve("pruned-cache");
        PackVariantManager manager = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        Path activeVariant = manager.prepare(source, PackProfile.SAFE).file();
        String artifactPrefix = activeVariant.getFileName().toString().substring(0, 37);
        Instant expired = Instant.now().minus(Duration.ofDays(31));

        Path staleVariant = cacheDirectory.resolve(artifactPrefix + "a".repeat(12) + "b".repeat(12) + "-safe.mcpack");
        Path recentVariant = cacheDirectory.resolve(artifactPrefix + "c".repeat(12) + "d".repeat(12) + "-safe.mcpack");
        Path staleNoChange = cacheDirectory.resolve(artifactPrefix + "e".repeat(12) + "f".repeat(12)
                + "-optimize_full.mcpack.no-change");
        Path unrelated = cacheDirectory.resolve("notes.mcpack");
        Path staleRawTemp = cacheDirectory.resolve(".fettle-raw-orphan123.tmp");
        Path staleDeflateTemp = cacheDirectory.resolve(".fettle-deflate-orphan123.tmp");
        Path staleRewriteTemp = cacheDirectory.resolve("." + activeVariant.getFileName() + "orphan123.tmp");
        Path recentRawTemp = cacheDirectory.resolve(".fettle-raw-current123.tmp");
        Path interruptedSource = Files.createTempFile(cacheDirectory, "." + packId + "-", ".source.mcpack");
        Path recentSource = Files.createTempFile(cacheDirectory, "." + packId + "-", ".source.mcpack");
        Files.copy(activeVariant, staleVariant);
        Files.copy(activeVariant, recentVariant);
        Files.write(staleNoChange, new byte[0]);
        Files.write(unrelated, new byte[0]);
        Files.write(staleRawTemp, new byte[0]);
        Files.write(staleDeflateTemp, new byte[0]);
        Files.write(staleRewriteTemp, new byte[0]);
        Files.write(recentRawTemp, new byte[0]);
        Files.write(interruptedSource, new byte[0]);
        FileTime expiredTime = FileTime.from(expired);
        Files.setLastModifiedTime(activeVariant, expiredTime);
        Files.setLastModifiedTime(staleVariant, expiredTime);
        Files.setLastModifiedTime(staleNoChange, expiredTime);
        Files.setLastModifiedTime(unrelated, expiredTime);
        Files.setLastModifiedTime(staleRawTemp, expiredTime);
        Files.setLastModifiedTime(staleDeflateTemp, expiredTime);
        Files.setLastModifiedTime(staleRewriteTemp, expiredTime);
        Files.setLastModifiedTime(interruptedSource, expiredTime);

        PackVariantManager.CachePruneResult result = manager.pruneExpiredArtifacts(List.of(source));

        assertEquals(6, result.removedArtifacts());
        assertTrue(Files.isRegularFile(activeVariant));
        assertFalse(Files.exists(staleVariant));
        assertTrue(Files.isRegularFile(recentVariant));
        assertFalse(Files.exists(staleNoChange));
        assertTrue(Files.isRegularFile(unrelated));
        assertFalse(Files.exists(staleRawTemp));
        assertFalse(Files.exists(staleDeflateTemp));
        assertFalse(Files.exists(staleRewriteTemp));
        assertTrue(Files.isRegularFile(recentRawTemp));
        assertFalse(Files.exists(interruptedSource));
        assertTrue(Files.isRegularFile(recentSource));
    }

    @Test
    void capsGeneratedCacheBytesWithoutDeletingCurrentVariants() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("quota-source.mcpack");
        writePack(sourcePath);
        UUID packId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        ResourcePack source = filePack(sourcePath, packId);
        Path cacheDirectory = temporaryDirectory.resolve("quota-cache");
        PackVariantManager manager = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        Path activeVariant = manager.prepare(source, PackProfile.SAFE).file();
        String artifactPrefix = activeVariant.getFileName().toString().substring(0, 37);
        Path oldest = cacheDirectory.resolve(artifactPrefix + "a".repeat(12) + "b".repeat(12) + "-safe.mcpack");
        Path middle = cacheDirectory.resolve(artifactPrefix + "c".repeat(12) + "d".repeat(12) + "-safe.mcpack");
        Path newest = cacheDirectory.resolve(artifactPrefix + "e".repeat(12) + "f".repeat(12) + "-safe.mcpack");
        Files.copy(activeVariant, oldest);
        Files.copy(activeVariant, middle);
        Files.copy(activeVariant, newest);
        Instant now = Instant.now();
        Files.setLastModifiedTime(oldest, FileTime.from(now.minus(Duration.ofDays(3))));
        Files.setLastModifiedTime(middle, FileTime.from(now.minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(newest, FileTime.from(now.minus(Duration.ofDays(1))));

        long activeSize = Files.size(activeVariant);
        PackVariantManager.CachePruneResult result = manager.pruneExpiredArtifacts(List.of(source), activeSize * 2);

        assertEquals(2, result.removedArtifacts());
        assertFalse(result.activeExceedsLimit());
        assertTrue(Files.isRegularFile(activeVariant));
        assertFalse(Files.exists(oldest));
        assertFalse(Files.exists(middle));
        assertTrue(Files.isRegularFile(newest));

        PackVariantManager.CachePruneResult activeOverLimit =
                manager.pruneExpiredArtifacts(List.of(source), activeSize / 2);
        assertEquals(1, activeOverLimit.removedArtifacts());
        assertTrue(activeOverLimit.activeExceedsLimit());
        assertTrue(Files.isRegularFile(activeVariant));
        assertFalse(Files.exists(newest));
    }

    @Test
    void persistsLosslessNoChangeResultsAcrossRestarts() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("unchanged.mcpack");
        try (RawZipBuilder zip = new RawZipBuilder(sourcePath)) {
            zip.add("manifest.json", new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            zip.finish();
        }
        UUID packId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        AtomicBoolean serializationAllowed = new AtomicBoolean(true);
        ResourcePack source = filePack(sourcePath, packId, serializationAllowed);
        Path cacheDirectory = temporaryDirectory.resolve("negative-cache");
        PackVariantManager manager = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));

        manager.prepare(source, PackProfile.FULL);
        PackVariantManager.PreparedVariant noChange = manager.prepare(source, PackProfile.OPTIMIZE_FULL);

        assertSame(source, noChange.pack());
        assertNull(noChange.file());
        Path noChangeMarker;
        try (Stream<Path> files = Files.list(cacheDirectory)) {
            noChangeMarker = files.filter(file -> file.getFileName().toString().endsWith(".no-change"))
                    .findFirst().orElseThrow();
        }
        Files.setLastModifiedTime(noChangeMarker, FileTime.from(Instant.now().minus(Duration.ofDays(31))));
        assertEquals(0, manager.pruneExpiredArtifacts(List.of(source)).removedArtifacts());
        assertTrue(Files.isRegularFile(noChangeMarker));

        PackVariantManager restarted = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        restarted.prepare(source, PackProfile.FULL);
        serializationAllowed.set(false);
        PackVariantManager.PreparedVariant reusedNoChange = restarted.prepare(source, PackProfile.OPTIMIZE_FULL);

        assertSame(source, reusedNoChange.pack());
        assertNull(reusedNoChange.file());
    }

    private void writePack(Path path) throws Exception {
        BufferedImage image = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, (x * 31 + y * 17) | 0xff000000);
        }
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", imageBytes);

        Map<String, byte[]> entries = Map.of(
                "manifest.json",
                ("{\"format_version\":2,\"header\":{\"name\":\"Fettle Test\",\"description\":\"\","
                        + "\"uuid\":\"2b2de2dd-aea9-4f60-92d4-1a99622b1c8f\",\"version\":[1,0,0],"
                        + "\"min_engine_version\":[1,20,0]},\"modules\":[{\"type\":\"resources\","
                        + "\"uuid\":\"5a9a160a-d25e-4e26-9d99-2952d387f7a6\",\"version\":[1,0,0]}]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "textures/test.png", imageBytes.toByteArray());

        try (OutputStream output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private void writeEntries(Path path, Map<String, byte[]> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private ResourcePack filePack(Path path, UUID packId) {
        return filePack(path, packId, new AtomicBoolean(true));
    }

    private ResourcePack filePack(Path path, UUID packId, AtomicBoolean serializationAllowed) {
        PackCodec codec = new PackCodec() {
            @Override
            public byte[] sha256() {
                try {
                    return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }

            @Override
            public long size() {
                try {
                    return Files.size(path);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }

            @Override
            public SeekableByteChannel serialize() throws java.io.IOException {
                if (!serializationAllowed.get()) throw new java.io.IOException("Serialization disabled in test");
                return Files.newByteChannel(path, StandardOpenOption.READ);
            }

            @Override
            protected ResourcePack create() {
                return null;
            }

            @Override
            protected ResourcePack.Builder createBuilder() {
                return null;
            }
        };
        return (ResourcePack) Proxy.newProxyInstance(
                ResourcePack.class.getClassLoader(),
                new Class<?>[]{ResourcePack.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "codec" -> codec;
                    case "uuid" -> packId;
                    case "toString" -> "test-pack:" + packId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static PackAuditReport audit(
            long archiveBytes,
            long uncompressedBytes,
            int fileCount,
            long textureMemoryBytes,
            int modelCount,
            int maxCubes,
            boolean complete) {
        return new PackAuditReport(
                archiveBytes, uncompressedBytes, fileCount, 0, 4, textureMemoryBytes,
                null, 0, 0, 0, null, 0, 0, 0, modelCount, maxCubes, null,
                complete, true, true, complete ? 0 : 1, 0, java.util.List.of());
    }
}
