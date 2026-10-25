package me.kylofz.fettle.geyser;

import org.geysermc.geyser.api.pack.PackCodec;
import org.geysermc.geyser.api.pack.ResourcePack;
import org.geysermc.geyser.api.pack.ResourcePackManifest;
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
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void doesNotBuildOrReuseVariantsForOpaqueBedrockArchives() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("archived.mcpack");
        writeEntries(sourcePath, Map.of(
                "manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "__brarchive/textures.brarchive", new byte[]{0x42, 0x52, 0x41, 0x52}));
        UUID packId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        ResourcePack source = filePack(sourcePath, packId);
        Path cacheDirectory = temporaryDirectory.resolve("opaque-pack-cache");
        PackVariantManager manager = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));

        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> prepared =
                manager.prepareSet(List.of(source), PackVariantManager.PREWARM_PROFILES);
        PackVariantManager.PreparedVariant safe = manager.prepare(source, PackProfile.SAFE);

        assertEquals(java.util.Set.of(PackProfile.FULL), prepared.get(packId).keySet());
        assertTrue(prepared.get(packId).get(PackProfile.FULL).audit().getOpaqueBedrockArchivePresent());
        assertSame(source, safe.pack());
        assertNull(safe.file());
        try (Stream<Path> files = Files.list(cacheDirectory)) {
            assertEquals(0L, files.count());
        }
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
                "manifest.json", manifest(baseId),
                "attachables/base-reference.json", "{\"geometry\":{\"default\":\"geometry.scf.shared\"},"
                        .concat("\"textures\":{\"default\":\"textures/stitched/default\"}}")
                        .getBytes(StandardCharsets.UTF_8),
                "models/entity/shared.geo.json", geometry.getBytes(StandardCharsets.UTF_8)));
        writeEntries(overlayPath, Map.of(
                "manifest.json", manifest(overlayId),
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
        UUID generatedBaseId = prepared.get(baseId).get(PackProfile.OPTIMIZE_FULL).pack().uuid();
        UUID generatedOverlayId = prepared.get(overlayId).get(PackProfile.OPTIMIZE_FULL).pack().uuid();
        assertNotNull(baseVariant);
        assertNotNull(overlayVariant);
        assertFalse(baseId.equals(generatedBaseId));
        assertFalse(overlayId.equals(generatedOverlayId));
        assertFalse(generatedBaseId.equals(generatedOverlayId));
        try (ZipFile zip = new ZipFile(baseVariant.toFile())) {
            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"uuid\":\"" + generatedBaseId + "\""));
            String rewritten = new String(zip.getInputStream(zip.getEntry("models/entity/shared.geo.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(rewritten.contains("\"texture_width\":602"));
            assertTrue(rewritten.contains("\"texture_height\":602"));
            assertTrue(zip.stream().anyMatch(entry -> entry.getName().startsWith("textures/entity/fettle/atlases/")));
        }
        try (ZipFile zip = new ZipFile(overlayVariant.toFile())) {
            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"uuid\":\"" + generatedOverlayId + "\""));
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
            assertTrue(prepared.get(baseId).get(profile).audit().getTextureMetricsComplete());
            assertTrue(prepared.get(overlayId).get(profile).audit().getTextureMetricsComplete());
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
        assertTrue(restarted.matchesPreparedSet(List.of(base, overlay)));
        PackSetAudit perSessionSubset = PackSetAudit.forSession(
                null, List.of(base), pack -> pack.uuid().equals(baseId), restarted);
        assertFalse(perSessionSubset.preparedPackStackMatches());
        assertEquals(java.util.Set.of(PackProfile.FULL), perSessionSubset.variantReports().get(baseId).keySet());

        Path partialCacheDirectory = temporaryDirectory.resolve("partial-linked-cache");
        PackVariantManager cacheWriter = new PackVariantManager(partialCacheDirectory, factory);
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> losslessOnly =
                cacheWriter.prepareSet(List.of(base, overlay), List.of(PackProfile.OPTIMIZE_FULL));
        Path partialBaseLossless = losslessOnly.get(baseId).get(PackProfile.OPTIMIZE_FULL).file();
        Path partialOverlayLossless = losslessOnly.get(overlayId).get(PackProfile.OPTIMIZE_FULL).file();
        byte[] retainedBaseBytes = Files.readAllBytes(partialBaseLossless);
        Files.delete(partialOverlayLossless);

        PackVariantManager partialRecovery = new PackVariantManager(partialCacheDirectory, factory);
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> recoveredLossless =
                partialRecovery.prepareSet(List.of(base, overlay), List.of(PackProfile.OPTIMIZE_FULL));
        assertArrayEquals(retainedBaseBytes, Files.readAllBytes(partialBaseLossless));
        assertTrue(Files.isRegularFile(recoveredLossless.get(overlayId).get(PackProfile.OPTIMIZE_FULL).file()));

        Files.delete(recoveredLossless.get(overlayId).get(PackProfile.OPTIMIZE_FULL).file());
        PackVariantManager partialResizer = new PackVariantManager(partialCacheDirectory, factory);
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> resizedFromPartialCache =
                partialResizer.prepareSet(List.of(base, overlay), List.of(PackProfile.OPTIMIZE_SEMI));
        Path cleanCacheDirectory = temporaryDirectory.resolve("clean-linked-cache");
        PackVariantManager cleanResizer = new PackVariantManager(cleanCacheDirectory, factory);
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> resizedFromOriginals =
                cleanResizer.prepareSet(List.of(base, overlay), List.of(PackProfile.OPTIMIZE_SEMI));
        assertArrayEquals(
                Files.readAllBytes(resizedFromOriginals.get(baseId).get(PackProfile.OPTIMIZE_SEMI).file()),
                Files.readAllBytes(resizedFromPartialCache.get(baseId).get(PackProfile.OPTIMIZE_SEMI).file()));
        assertArrayEquals(
                Files.readAllBytes(resizedFromOriginals.get(overlayId).get(PackProfile.OPTIMIZE_SEMI).file()),
                Files.readAllBytes(resizedFromPartialCache.get(overlayId).get(PackProfile.OPTIMIZE_SEMI).file()));
    }

    @Test
    void keepsPreparedAuditsForTwoSessionSpecificPackStacks() throws Exception {
        UUID baseId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        UUID fullId = UUID.fromString("3c3de3ee-beb9-5f70-a3e5-2b99622b1c9f");
        UUID simpleId = UUID.fromString("4d4de4ff-cf09-6f80-b4f6-3c99622b1d0f");
        Path basePath = temporaryDirectory.resolve("session-base.mcpack");
        Path fullPath = temporaryDirectory.resolve("session-full.mcpack");
        Path simplePath = temporaryDirectory.resolve("session-simple.mcpack");
        writePack(basePath);
        writePack(fullPath);
        writePack(simplePath);
        ResourcePack base = filePack(basePath, baseId);
        ResourcePack full = filePack(fullPath, fullId);
        ResourcePack simple = filePack(simplePath, simpleId);
        List<PackProfile> profiles = List.of(PackProfile.FULL, PackProfile.OPTIMIZE_FULL,
                PackProfile.OPTIMIZE_SEMI, PackProfile.OPTIMIZE_MINI, PackProfile.SAFE);
        java.util.function.Function<Path, ResourcePack> factory = path -> {
            String filename = path.getFileName().toString();
            for (UUID id : List.of(baseId, fullId, simpleId)) {
                if (filename.startsWith(id + "-")) return filePack(path, id);
            }
            throw new IllegalArgumentException("Unrecognized cached pack " + filename);
        };
        PackVariantManager manager = new PackVariantManager(temporaryDirectory.resolve("session-pack-cache"), factory);

        List<ResourcePack> fullStack = List.of(base, full);
        manager.prepareSet(fullStack, profiles);
        PackSetAudit fullAudit = PackSetAudit.collect(fullStack, fullStack, pack -> true, manager);
        Path fullBaseVariant = fullAudit.preparedVariants().get(baseId).get(PackProfile.OPTIMIZE_MINI).file();

        List<ResourcePack> simpleStack = List.of(base, simple);
        manager.prepareSet(simpleStack, profiles);
        PackSetAudit simpleAudit = PackSetAudit.collect(simpleStack, simpleStack, pack -> true, manager);
        Path simpleBaseVariant = simpleAudit.preparedVariants().get(baseId).get(PackProfile.OPTIMIZE_MINI).file();

        assertTrue(fullAudit.preparedPackStackMatches());
        assertTrue(simpleAudit.preparedPackStackMatches());
        assertFalse(fullBaseVariant.equals(simpleBaseVariant));
        assertEquals(fullBaseVariant,
                fullAudit.preparedVariant(base, PackProfile.OPTIMIZE_MINI, manager).file());
        assertEquals(simpleBaseVariant,
                simpleAudit.preparedVariant(base, PackProfile.OPTIMIZE_MINI, manager).file());
        Files.delete(fullBaseVariant);
        assertNull(fullAudit.preparedVariant(base, PackProfile.OPTIMIZE_MINI, manager));
    }

    @Test
    void protectsSharedTexturePathsAndCacheAgainstTheFullRequiredPackStack() throws Exception {
        UUID baseId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        UUID overlayId = UUID.fromString("3c3de3ee-beb9-5f70-a3e5-2b99622b1c9f");
        UUID fixedId = UUID.fromString("4d4de4ff-cf09-6f80-b4f6-3c99622b1d0f");
        Path basePath = temporaryDirectory.resolve("stack-base.mcpack");
        Path overlayPath = temporaryDirectory.resolve("stack-overlay.mcpack");
        Path fixedPath = temporaryDirectory.resolve("stack-fixed.mcpack");
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, y, 0xff345678);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        byte[] identicalTexture = png.toByteArray();
        String model = "{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.scf.shared\"}}]}";
        String baseAttachable = "{\"minecraft:attachable\":{\"description\":{\"geometry\":{\"default\":\"geometry.scf.shared\"},"
                + "\"textures\":{\"default\":\"textures/model/base\"}}}}";
        String overlayAttachable = "{\"minecraft:attachable\":{\"description\":{\"geometry\":{\"default\":\"geometry.scf.shared\"},"
                + "\"textures\":{\"default\":\"textures/model/frame\"}}}}";
        writeEntries(basePath, Map.of(
                "manifest.json", manifest(baseId),
                "models/entity/shared.geo.json", model.getBytes(StandardCharsets.UTF_8),
                "attachables/base.json", baseAttachable.getBytes(StandardCharsets.UTF_8),
                "textures/model/base.png", identicalTexture));
        writeEntries(overlayPath, Map.of(
                "manifest.json", manifest(overlayId),
                "attachables/overlay.json", overlayAttachable.getBytes(StandardCharsets.UTF_8),
                "textures/model/frame.png", identicalTexture));
        writeEntries(fixedPath, Map.of(
                "manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "textures/model/base.png", new byte[]{1, 2, 3, 4}));

        ResourcePack base = filePack(basePath, baseId);
        ResourcePack overlay = filePack(overlayPath, overlayId);
        ResourcePack fixed = filePack(fixedPath, fixedId);
        java.util.function.Function<Path, ResourcePack> factory = path -> filePack(
                path, path.getFileName().toString().startsWith(baseId.toString()) ? baseId : overlayId);
        PackVariantManager manager = new PackVariantManager(
                temporaryDirectory.resolve("full-stack-cache"), factory);

        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> variants = manager.prepareSet(
                List.of(base, overlay), List.of(base, overlay, fixed),
                List.of(PackProfile.FULL, PackProfile.OPTIMIZE_FULL));

        Path overlayVariant = variants.get(overlayId).get(PackProfile.OPTIMIZE_FULL).file();
        try (ZipFile zip = new ZipFile(overlayVariant.toFile())) {
            assertTrue(zip.getEntry("textures/model/frame.png") != null);
            String attachable = new String(zip.getInputStream(zip.getEntry("attachables/overlay.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(attachable.contains("textures/model/frame"));
        }
        assertTrue(manager.matchesPreparedSet(List.of(base, overlay, fixed), List.of(base, overlay)));
        assertTrue(manager.matchesPreparedSet(List.of(fixed, overlay, base), List.of(overlay, base)));
        java.util.function.Predicate<ResourcePack> targets = pack ->
                pack.uuid().equals(baseId) || pack.uuid().equals(overlayId);
        PackSetAudit preparedAudit = PackSetAudit.forSession(
                null, List.of(base, overlay, fixed), targets, manager);
        assertSame(preparedAudit, PackSetAudit.forSession(
                preparedAudit, List.of(fixed, overlay, base), targets, manager));

        writeEntries(fixedPath, Map.of(
                "manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "attachables/consumer.json", "{\"textures\":{\"default\":\"textures/model/frame\"}}"
                        .getBytes(StandardCharsets.UTF_8)));
        ResourcePack referencingFixed = filePack(fixedPath, fixedId);
        PackVariantManager referenceGuard = new PackVariantManager(
                temporaryDirectory.resolve("full-stack-reference-cache"), factory);
        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> referencedVariants = referenceGuard.prepareSet(
                List.of(base, overlay), List.of(base, overlay, referencingFixed),
                List.of(PackProfile.FULL, PackProfile.OPTIMIZE_FULL));
        try (ZipFile zip = new ZipFile(referencedVariants.get(overlayId).get(PackProfile.OPTIMIZE_FULL).file().toFile())) {
            assertTrue(zip.getEntry("textures/model/frame.png") != null);
        }

        assertNotNull(referenceGuard.preparedVariantIfCached(base, PackProfile.OPTIMIZE_FULL));
        writeEntries(fixedPath, Map.of(
                "manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "textures/model/other.png", new byte[]{9, 8, 7, 6}));
        ResourcePack changedFixed = filePack(fixedPath, fixedId);
        PackSetAudit changedStack = PackSetAudit.forSession(
                null, List.of(base, overlay, changedFixed), pack -> pack.uuid().equals(baseId)
                        || pack.uuid().equals(overlayId), referenceGuard);
        assertFalse(changedStack.preparedPackStackMatches());
        assertNull(changedStack.preparedVariant(base, PackProfile.OPTIMIZE_FULL, referenceGuard));
        assertEquals(java.util.Set.of(PackProfile.FULL),
                changedStack.variantReports().get(baseId).keySet());
    }

    @Test
    void keepsRequiredPackIdentityWhenAnotherRequiredPackDependsOnIt() throws Exception {
        UUID targetId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        UUID dependentId = UUID.fromString("4d4de4ff-cf09-6f80-b4f6-3c99622b1d0f");
        Path targetPath = temporaryDirectory.resolve("dependency-target.mcpack");
        Path dependentPath = temporaryDirectory.resolve("dependency-consumer.mcpack");
        writeEntries(targetPath, Map.of("manifest.json", manifest(targetId), "textures/test.png", new byte[]{1, 2, 3}));
        writeEntries(dependentPath, Map.of("manifest.json", manifest(dependentId)));
        ResourcePack target = filePack(targetPath, targetId);
        ResourcePack dependent = filePack(dependentPath, dependentId, List.of(targetId));
        PackVariantManager manager = new PackVariantManager(
                temporaryDirectory.resolve("dependency-cache"), path -> filePack(path, targetId));

        Map<UUID, Map<PackProfile, PackVariantManager.PreparedVariant>> prepared = manager.prepareSet(
                List.of(target), List.of(target, dependent), List.of(PackProfile.FULL, PackProfile.OPTIMIZE_FULL));

        assertFalse(manager.variantIdentitiesSafe());
        assertEquals(java.util.Set.of(PackProfile.FULL), prepared.get(targetId).keySet());
        assertSame(target, prepared.get(targetId).get(PackProfile.FULL).pack());
        assertNull(manager.preparedVariantIfCached(target, PackProfile.OPTIMIZE_FULL));
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
    void packSetAuditSnapshotMatchesPackIdsAndSourceContentsRegardlessOfOrder() throws Exception {
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
        assertSame(snapshot, PackSetAudit.forSession(snapshot, List.of(fixed, target), matcher, null));

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
    void reportsPackCacheSizeWithoutDeletingFiles() throws Exception {
        Path cacheDirectory = temporaryDirectory.resolve("pack-cache");
        PackVariantManager manager = new PackVariantManager(cacheDirectory);
        Path oldVariant = cacheDirectory.resolve("old-variant.mcpack");
        Path interruptedWrite = cacheDirectory.resolve(".fettle-raw-interrupted.tmp");
        Path operatorFile = cacheDirectory.resolve("operator-note.txt");
        Files.write(oldVariant, new byte[3]);
        Files.write(interruptedWrite, new byte[5]);
        Files.write(operatorFile, new byte[7]);

        PackVariantManager.CacheUsage overTarget = manager.cacheUsage(10);
        PackVariantManager.CacheUsage withinTarget = manager.cacheUsage(15);

        assertEquals(15, overTarget.bytes());
        assertTrue(overTarget.overWarningThreshold());
        assertFalse(withinTarget.overWarningThreshold());
        assertTrue(Files.isRegularFile(oldVariant));
        assertTrue(Files.isRegularFile(interruptedWrite));
        assertTrue(Files.isRegularFile(operatorFile));
    }

    @Test
    void leavesInvalidCacheArtifactsUntouched() throws Exception {
        Path sourcePath = temporaryDirectory.resolve("invalid-cache-source.mcpack");
        writePack(sourcePath);
        UUID packId = UUID.fromString("2b2de2dd-aea9-4f60-92d4-1a99622b1c8f");
        ResourcePack source = filePack(sourcePath, packId);
        Path cacheDirectory = temporaryDirectory.resolve("invalid-cache");
        PackVariantManager first = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        Path cachedVariant = first.prepare(source, PackProfile.SAFE).file();
        byte[] existingBytes = "preserve this cache file".getBytes(StandardCharsets.UTF_8);
        Files.write(cachedVariant, existingBytes);

        PackVariantManager restarted = new PackVariantManager(cacheDirectory, path -> filePack(path, packId));
        PackVariantManager.PreparedVariant selected = restarted.prepare(source, PackProfile.SAFE);

        assertSame(source, selected.pack());
        assertNull(selected.file());
        assertArrayEquals(existingBytes, Files.readAllBytes(cachedVariant));
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
        manager.cacheUsage();
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

    private static byte[] manifest(UUID packId) {
        return ("{\"format_version\":2,\"header\":{\"name\":\"Fettle Test\",\"uuid\":\""
                + packId + "\",\"version\":[1,0,0]}}").getBytes(StandardCharsets.UTF_8);
    }

    private ResourcePack filePack(Path path, UUID packId) {
        return filePack(path, packId, new AtomicBoolean(true), List.of());
    }

    private ResourcePack filePack(Path path, UUID packId, AtomicBoolean serializationAllowed) {
        return filePack(path, packId, serializationAllowed, List.of());
    }

    private ResourcePack filePack(Path path, UUID packId, List<UUID> dependencyIds) {
        return filePack(path, packId, new AtomicBoolean(true), dependencyIds);
    }

    private ResourcePack filePack(
            Path path, UUID packId, AtomicBoolean serializationAllowed, List<UUID> dependencyIds) {
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
                    case "uuid" -> path.getFileName().toString().startsWith(packId + "-")
                            ? headerUuid(path, packId) : packId;
                    case "manifest" -> testManifest(dependencyIds);
                    case "toString" -> "test-pack:" + packId;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static ResourcePackManifest testManifest(List<UUID> dependencyIds) {
        return (ResourcePackManifest) Proxy.newProxyInstance(
                ResourcePackManifest.class.getClassLoader(),
                new Class<?>[]{ResourcePackManifest.class},
                (proxy, method, args) -> method.getName().equals("dependencies")
                        ? dependencyIds.stream().map(PackVariantManagerTest::testDependency).toList()
                        : null);
    }

    private static ResourcePackManifest.Dependency testDependency(UUID packId) {
        return (ResourcePackManifest.Dependency) Proxy.newProxyInstance(
                ResourcePackManifest.Dependency.class.getClassLoader(),
                new Class<?>[]{ResourcePackManifest.Dependency.class},
                (proxy, method, args) -> method.getName().equals("uuid") ? packId : null);
    }

    private static UUID headerUuid(Path path, UUID fallback) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("manifest.json");
            if (entry == null) return fallback;
            String json = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            int header = json.indexOf("\"header\"");
            int uuid = json.indexOf("\"uuid\"", header);
            int colon = json.indexOf(':', uuid);
            int start = json.indexOf('"', colon);
            int end = json.indexOf('"', start + 1);
            return UUID.fromString(json.substring(start + 1, end));
        } catch (Exception invalidOrMissingManifest) {
            return fallback;
        }
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
