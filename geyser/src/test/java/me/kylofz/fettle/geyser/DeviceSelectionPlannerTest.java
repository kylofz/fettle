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
    void selectionUsesMemoryTierInsteadOfDeviceOs() {
        PackSetAudit candidates = auditSet(1100 * MIB, 900 * MIB, 420 * MIB, 210 * MIB, 100 * MIB);
        DeviceSelectionPlanner.Plan android = plan(capabilities("ANDROID", 3), candidates, Map.of());
        DeviceSelectionPlanner.Plan windows = plan(capabilities("WINDOWS", 3), candidates, Map.of());

        assertEquals(PackProfile.OPTIMIZE_SEMI, android.selection().profiles().get(PACK_ID));
        assertEquals(android.selection().profiles(), windows.selection().profiles());
        assertEquals(android.selection().textureBudgetBytes(), windows.selection().textureBudgetBytes());
        assertSame(android.selection(), windows.selection());
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
        PackSetAudit nonSplitSet = measuredSet(List.of(nonSplit), nonSplit, Set.of(),
                1176.8, 1090.8, 318.0, 149.0, 87.9);
        PackSetAudit splitFullSet = measuredSet(
                List.of(base, animated, blocks, glyphs, interfacePack), base, linkedAnimations,
                3813.2, 2391.8, 592.8, 318.6, 242.6);
        PackSetAudit splitSimpleSet = measuredSet(
                List.of(base, animatedSimple, blocks, glyphs, interfacePack), base, linkedAnimations,
                6255.1, 2118.1, 951.7, 406.0, 248.4);

        assertAutomaticProfiles(nonSplitSet, List.of(nonSplit), new PackProfile[]{
                PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_MINI,
                PackProfile.OPTIMIZE_SEMI, PackProfile.OPTIMIZE_FULL});
        assertAutomaticProfiles(splitFullSet, List.of(base, animated), new PackProfile[]{
                PackProfile.SAFE, PackProfile.SAFE, PackProfile.SAFE,
                PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_SEMI});
        assertAutomaticProfiles(splitSimpleSet, List.of(base, animatedSimple), new PackProfile[]{
                PackProfile.SAFE, PackProfile.SAFE, PackProfile.SAFE,
                PackProfile.OPTIMIZE_MINI, PackProfile.OPTIMIZE_SEMI});
    }

    private static PackSetAudit measuredSet(
            List<UUID> packIds,
            UUID aggregatePack,
            Set<UUID> linkedGroup,
            double full,
            double lossless,
            double semi,
            double mini,
            double safe) {
        // These are whole-set totals; the linked base carries the aggregate cost in this planner-only fixture.
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = new HashMap<>();
        for (UUID id : packIds) {
            variants.put(id, id.equals(aggregatePack)
                    ? measured(full, lossless, semi, mini, safe)
                    : measured(0, 0, 0, 0, 0));
        }
        return new PackSetAudit(List.of(), List.of(), List.of(), Map.of(), variants,
                linkedGroup.isEmpty() ? List.of() : List.of(linkedGroup), 0, 0, 0, true, true);
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
            assertTrue(selection.withinBudget(), "MemoryTier " + tier);
        }
    }

    @Test
    void snapshotMatchesThePackSetActuallySelectedForTheSession() {
        DeviceSelectionPlanner.Plan plan = plan(
                capabilities("NINTENDO", 2),
                auditSet(1100 * MIB, 900 * MIB, 420 * MIB, 210 * MIB, 100 * MIB),
                Map.of());
        SessionDeviceSnapshot snapshot = plan.snapshot(Map.of(PACK_ID, PackProfile.OPTIMIZE_MINI), "SIMPLE");

        assertEquals(2, snapshot.selectionMemoryTier());
        assertEquals(PackProfile.OPTIMIZE_MINI, snapshot.selectedProfiles().get(PACK_ID));
        assertEquals(210 * MIB, snapshot.estimatedTextureBytes());
        assertEquals("SIMPLE", snapshot.scaffoldingAnimationVariant());
        assertTrue(snapshot.defaultTierBudget());
        assertTrue(snapshot.withinBudget());
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
        return new PackSetAudit(List.of(), List.of(), List.of(), Map.of(), Map.of(PACK_ID, variants), List.of(),
                0, 0, 0, true, true);
    }

    private static PackAuditReport audit(long textureBytes) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, true, 0, 0, List.of());
    }

    private static Map<PackProfile, PackAuditReport> measured(
            double full, double lossless, double semi, double mini, double safe) {
        return Map.of(
                PackProfile.FULL, auditMiB(full),
                PackProfile.OPTIMIZE_FULL, auditMiB(lossless),
                PackProfile.OPTIMIZE_SEMI, auditMiB(semi),
                PackProfile.OPTIMIZE_MINI, auditMiB(mini),
                PackProfile.SAFE, auditMiB(safe));
    }

    private static PackAuditReport auditMiB(double textureMemoryMiB) {
        return audit(Math.round(textureMemoryMiB * MIB));
    }
}
