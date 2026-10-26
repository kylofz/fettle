package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.optimizer.PackAuditReport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceSelectionPlannerTest {
    private static final long MIB = 1024L * 1024L;
    private static final UUID PACK_ID = UUID.fromString("e64da2bc-9df9-4d1a-975c-32f472350d70");
    private static final DeviceSelectionPlanner PLANNER = new DeviceSelectionPlanner();

    @Test
    void everyBedrockMemoryTierSelectsAutomaticallyWithoutPairing() {
        PackSetAudit candidates = auditSet(1100 * MIB, 900 * MIB, 420 * MIB, 210 * MIB, 100 * MIB);
        PackProfile[] expected = {
                PackProfile.OPTIMIZE_MINI,
                PackProfile.OPTIMIZE_MINI,
                PackProfile.OPTIMIZE_MINI,
                PackProfile.OPTIMIZE_SEMI,
                PackProfile.OPTIMIZE_FULL,
        };
        long[] budgets = {300, 300, 300, 450, 1200};

        for (int tier = 0; tier <= 4; tier++) {
            DeviceSelectionPlanner.Plan plan = plan(capabilities("ANDROID", tier), candidates, Map.of());
            assertEquals(budgets[tier] * MIB, plan.selection().textureBudgetBytes());
            assertEquals(expected[tier], plan.selection().profiles().get(PACK_ID));
            assertTrue(plan.defaultTierBudget());
            assertTrue(plan.selection().withinBudget());
        }
    }

    @Test
    void fixedPackUnsupportedForOptimizationDoesNotRejectMeasuredAutomaticSelection() {
        UUID fixedId = UUID.fromString("d64da2bc-9df9-4d1a-975c-32f472350d71");
        PackSetAudit candidates = auditSet(1100 * MIB, 900 * MIB, 420 * MIB, 210 * MIB, 100 * MIB);
        PackAuditReport fixed = new PackAuditReport(
                0, 0, 2, 0, 1, 10 * MIB,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, true, 0, 0, List.of(),
                List.of(), 0, 0, 0, 0, 0, false, true);
        PackSetAudit withUnchangedFixedPack = new PackSetAudit(
                List.of(), List.of(), List.of(), Map.of(), candidates.variantReports(), Map.of(fixedId, fixed),
                List.of(), 10 * MIB, 1, 0, 0, true, 1, true, true);

        DeviceProfilePolicy.Selection selection = new DeviceSelectionPlanner().plan(
                capabilities("ANDROID", 2), PackProfile.AUTO, null, Map.of(), withUnchangedFixedPack).selection();

        assertEquals(PackProfile.OPTIMIZE_MINI, selection.profiles().get(PACK_ID));
        assertEquals(DeviceProfilePolicy.Status.DEGRADED, selection.status());
        assertTrue(selection.satisfiesPolicy());
        assertTrue(selection.withinBudget());
    }

    @Test
    void selectionUsesMemoryTierInsteadOfDeviceOs() {
        PackSetAudit candidates = auditSet(1100 * MIB, 900 * MIB, 420 * MIB, 210 * MIB, 100 * MIB);
        DeviceSelectionPlanner.Plan android = plan(capabilities("ANDROID", 3), candidates, Map.of());
        DeviceSelectionPlanner.Plan windows = plan(capabilities("WINDOWS", 3), candidates, Map.of());
        DeviceSelectionPlanner.Plan ios = plan(new BedrockDeviceCapabilities(
                "IOS", 2, "iPhone", 3, 24, 2, true, "1.26.10"), candidates, Map.of());

        assertEquals(PackProfile.OPTIMIZE_SEMI, android.selection().profiles().get(PACK_ID));
        assertEquals(android.selection().profiles(), windows.selection().profiles());
        assertEquals(android.selection().profiles(), ios.selection().profiles());
        assertEquals(android.selection().textureBudgetBytes(), windows.selection().textureBudgetBytes());
        assertEquals(android.selection().textureBudgetBytes(), ios.selection().textureBudgetBytes());
        assertSame(android.selection(), windows.selection());
        assertSame(android.selection(), ios.selection());
    }

    @Test
    void selectionCacheEvictsOldPackPolicies() throws Exception {
        DeviceSelectionPlanner planner = new DeviceSelectionPlanner();
        PackSetAudit candidates = auditSet(1100 * MIB, 900 * MIB, 420 * MIB, 210 * MIB, 100 * MIB);
        for (int budget = 1; budget <= 129; budget++) {
            planner.plan(capabilities("WINDOWS", 3), PackProfile.AUTO, null,
                    Map.of(3, budget * MIB), candidates);
        }

        Field field = DeviceSelectionPlanner.class.getDeclaredField("selections");
        field.setAccessible(true);
        assertEquals(128, ((Map<?, ?>) field.get(planner)).size());
    }

    @Test
    void operatorBudgetOverridesAutomaticMemoryTierBudget() {
        DeviceSelectionPlanner.Plan plan = plan(
                capabilities("WINDOWS", 3),
                auditSet(1100 * MIB, 900 * MIB, 420 * MIB, 210 * MIB, 100 * MIB),
                Map.of(3, 250 * MIB));

        assertEquals(250 * MIB, plan.selection().textureBudgetBytes());
        assertEquals(PackProfile.OPTIMIZE_MINI, plan.selection().profiles().get(PACK_ID));
        assertTrue(plan.selection().withinBudget());
    }

    @Test
    void suppliedScaffoldingFormatsSelectMeasuredProfilesForEveryMemoryTier() {
        UUID base = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID animated = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID animatedSimple = UUID.fromString("00000000-0000-4000-8000-000000000003");
        UUID blocks = UUID.fromString("00000000-0000-4000-8000-000000000004");
        UUID glyphs = UUID.fromString("00000000-0000-4000-8000-000000000005");
        UUID interfacePack = UUID.fromString("00000000-0000-4000-8000-000000000006");
        UUID nonSplit = UUID.fromString("00000000-0000-4000-8000-000000000007");
        Set<UUID> linkedAnimations = Set.of(base, animated, animatedSimple);
        PackSetAudit nonSplitSet = measuredSet(List.of(nonSplit), nonSplit, Set.of(), Map.of(
                PackProfile.FULL, auditMiB(1176, 1083),
                PackProfile.OPTIMIZE_FULL, auditMiB(1116, 1025),
                PackProfile.OPTIMIZE_SEMI, auditMiB(275, 269),
                PackProfile.OPTIMIZE_MINI, auditMiB(117, 114),
                PackProfile.SAFE, auditMiB(58, 57)));
        PackSetAudit splitFullSet = measuredSet(
                List.of(base, animated, blocks, glyphs, interfacePack), base, linkedAnimations,
                Map.of(
                        PackProfile.FULL, audit(3_998_452_736L, 3_889_766_400L),
                        PackProfile.OPTIMIZE_FULL, audit(2_680_668_668L, 2_473_299_780L),
                        PackProfile.OPTIMIZE_SEMI, audit(311_454_804L, 285_215_696L),
                        PackProfile.OPTIMIZE_MINI, audit(152_976_360L, 137_954_156L),
                        PackProfile.SAFE, audit(111_395_280L, 99_995_852L)));
        PackSetAudit splitSimpleSet = measuredSet(
                List.of(base, animatedSimple, blocks, glyphs, interfacePack), base, linkedAnimations,
                Map.of(
                        PackProfile.FULL, audit(6_558_907_392L, 6_450_221_056L),
                        PackProfile.OPTIMIZE_FULL, audit(2_288_402_984L, 1_911_730_548L),
                        PackProfile.OPTIMIZE_SEMI, audit(260_351_632L, 222_018_384L),
                        PackProfile.OPTIMIZE_MINI, audit(139_722_168L, 121_953_276L),
                        PackProfile.SAFE, audit(107_606_884L, 95_554_692L)));

        assertAutomaticProfiles(nonSplitSet, List.of(nonSplit), new PackProfile[]{
                PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_MINI,
                PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_MINI});
        assertAutomaticProfiles(splitFullSet, List.of(base, animated), new PackProfile[]{
                PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_MINI,
                PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_MINI});
        assertAutomaticProfiles(splitSimpleSet, List.of(base, animatedSimple), new PackProfile[]{
                PackProfile.OPTIMIZE_SEMI, PackProfile.OPTIMIZE_SEMI, PackProfile.OPTIMIZE_SEMI,
                PackProfile.OPTIMIZE_SEMI, PackProfile.OPTIMIZE_SEMI});
        assertUnknownIosProfile(splitFullSet, List.of(base, animated), PackProfile.OPTIMIZE_MINI);
        assertUnknownIosProfile(splitSimpleSet, List.of(base, animatedSimple), PackProfile.OPTIMIZE_SEMI);
    }

    private static PackSetAudit measuredSet(
            List<UUID> packIds,
            UUID aggregatePack,
            Set<UUID> linkedGroup,
            Map<PackProfile, PackAuditReport> aggregateReports) {
        // Whole-set costs are carried by the linked base so the fixture tests real atlas limits.
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = new HashMap<>();
        for (UUID id : packIds) {
            variants.put(id, id.equals(aggregatePack)
                    ? aggregateReports
                    : Map.of(
                            PackProfile.FULL, audit(0, 0),
                            PackProfile.OPTIMIZE_FULL, audit(0, 0),
                            PackProfile.OPTIMIZE_SEMI, audit(0, 0),
                            PackProfile.OPTIMIZE_MINI, audit(0, 0),
                            PackProfile.SAFE, audit(0, 0)));
        }
        return new PackSetAudit(List.of(), List.of(), List.of(), Map.of(), variants, Map.of(),
                linkedGroup.isEmpty() ? List.of() : List.of(linkedGroup), 0, 0, 0, 0, true, 0, true, true);
    }

    private static void assertAutomaticProfiles(
            PackSetAudit candidates,
            List<UUID> linkedPacks,
            PackProfile[] expectedByTier) {
        for (int tier = 0; tier <= 4; tier++) {
            DeviceProfilePolicy.Selection selection = plan(
                    capabilities("ANDROID", tier), candidates, Map.of()).selection();
            assertEquals(candidates.variantReports().keySet(), selection.profiles().keySet());
            for (UUID packId : linkedPacks) {
                assertEquals(expectedByTier[tier], selection.profiles().get(packId),
                        "MemoryTier " + tier + " pack " + packId);
            }
            assertTrue(selection.atlasMetricsComplete(), "atlas metrics for MemoryTier " + tier);
            assertTrue(selection.withinStackAtlasPolicy(), "stack atlas policy for MemoryTier " + tier);
            assertTrue(selection.withinBudget(), "MemoryTier " + tier);
        }
    }

    private static void assertUnknownIosProfile(
            PackSetAudit candidates,
            List<UUID> linkedPacks,
            PackProfile expected) {
        var selection = plan(new BedrockDeviceCapabilities("IOS", 2, "iPhone", null,
                null, null, null, "1.26.52"), candidates, Map.of()).selection();

        assertEquals(300 * MIB, selection.textureBudgetBytes());
        assertFalse(selection.budgetKnown());
        assertFalse(selection.withinBudget());
        assertTrue(selection.satisfiesPolicy());
        assertEquals(DeviceProfilePolicy.Status.DEGRADED, selection.status());
        assertTrue(selection.atlasMetricsComplete());
        assertTrue(selection.withinStackAtlasPolicy());
        linkedPacks.forEach(id -> assertEquals(expected, selection.profiles().get(id)));
    }

    @Test
    void snapshotMatchesThePackSetActuallySelectedForTheSession() {
        DeviceSelectionPlanner.Plan plan = plan(
                capabilities("NINTENDO", 2),
                auditSet(1100 * MIB, 900 * MIB, 420 * MIB, 210 * MIB, 100 * MIB),
                Map.of());
        SessionDeviceSnapshot snapshot = plan.snapshot(Map.of(PACK_ID, PackProfile.OPTIMIZE_MINI), "SIMPLE", 1234);

        assertEquals(1234, snapshot.geyserProtocolVersion());
        assertEquals(2, snapshot.selectionMemoryTier());
        assertEquals(PackProfile.OPTIMIZE_MINI, snapshot.selectedProfiles().get(PACK_ID));
        assertEquals(210 * MIB, snapshot.estimatedTextureBytes());
        assertEquals("SIMPLE", snapshot.scaffoldingAnimationVariant());
        assertTrue(snapshot.defaultTierBudget());
        assertTrue(snapshot.withinBudget());
    }

    @Test
    void snapshotIncludesPerPackAuditsForIncompleteTargetAndFixedPacks() {
        UUID fixedId = UUID.fromString("d64da2bc-9df9-4d1a-975c-32f472350d71");
        PackAuditReport incomplete = new PackAuditReport(
                0, 0, 1, 0, 2, 80 * MIB,
                null, 0, 0, 0,
                null, 0, 0, 0,
                17, 1520, "models/entity/heavy.geo.json",
                false, true, true, 1, 0, List.of("one texture could not be measured"));
        PackAuditReport fixed = audit(150 * MIB);
        PackSetAudit candidates = new PackSetAudit(
                List.of(), List.of(), List.of(), Map.of(),
                Map.of(PACK_ID, Map.of(PackProfile.FULL, incomplete)),
                Map.of(fixedId, fixed), List.of(),
                150 * MIB, 1, 0, 0, true, 0, true, true);

        SessionDeviceSnapshot snapshot = plan(capabilities("IOS", 3), candidates, Map.of())
                .snapshot(Map.of(PACK_ID, PackProfile.FULL), null, 1234);

        assertEquals(2, snapshot.packAudits().size());
        PackSetAudit.PackAuditSnapshot target = snapshot.packAudits().stream()
                .filter(pack -> pack.id().equals(PACK_ID)).findFirst().orElseThrow();
        assertEquals(PackProfile.FULL, target.profile());
        assertEquals(1, target.report().getUnmeasuredTextureCount());
        assertEquals(17, target.report().getModelFileCount());
        assertEquals(1520, target.report().getMaxModelCubes());
        assertEquals("models/entity/heavy.geo.json", target.report().getLargestModelPath());
        PackSetAudit.PackAuditSnapshot fixedPack = snapshot.packAudits().stream()
                .filter(pack -> pack.id().equals(fixedId)).findFirst().orElseThrow();
        assertEquals(PackProfile.FULL, fixedPack.profile());
        assertEquals(fixed, fixedPack.report());
    }

    private static DeviceSelectionPlanner.Plan plan(
            BedrockDeviceCapabilities capabilities,
            PackSetAudit audit,
            Map<Integer, Long> overrides) {
        return PLANNER.plan(capabilities, PackProfile.AUTO, null, overrides, audit);
    }

    private static BedrockDeviceCapabilities capabilities(String platform, int memoryTier) {
        return new BedrockDeviceCapabilities(platform, null, "Example Device", memoryTier, 24, 2, true, "1.26.10");
    }

    private static PackSetAudit auditSet(long full, long lossless, long semi, long mini, long safe) {
        Map<PackProfile, PackAuditReport> variants = Map.of(
                PackProfile.FULL, audit(full),
                PackProfile.OPTIMIZE_FULL, audit(lossless),
                PackProfile.OPTIMIZE_SEMI, audit(semi),
                PackProfile.OPTIMIZE_MINI, audit(mini),
                PackProfile.SAFE, audit(safe));
        return new PackSetAudit(List.of(), List.of(), List.of(), Map.of(), Map.of(PACK_ID, variants), Map.of(), List.of(),
                0, 0, 0, 0, true, 0, true, true);
    }

    private static PackAuditReport audit(long textureBytes) {
        return audit(textureBytes, 0);
    }

    private static PackAuditReport audit(long textureBytes, long itemAtlasTextureBytes) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, true, 0, 0, List.of(), List.of(), 0, 0, 0, itemAtlasTextureBytes);
    }

    private static PackAuditReport auditMiB(double textureMemoryMiB, double itemAtlasTextureMemoryMiB) {
        return audit(Math.round(textureMemoryMiB * MIB), Math.round(itemAtlasTextureMemoryMiB * MIB));
    }
}
