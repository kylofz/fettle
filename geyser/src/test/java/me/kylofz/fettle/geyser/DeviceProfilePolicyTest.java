package me.kylofz.fettle.geyser;

import me.kylofz.fettle.device.BedrockDeviceCapabilities;
import me.kylofz.fettle.optimizer.PackAuditReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceProfilePolicyTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    void automaticChoiceUsesTheHighestFidelityVariantWithinMemoryTierBudget() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(600 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(420 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(200 * MIB),
                PackProfile.SAFE, audit(100 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 450 * MIB),
                0, 0, true);

        assertEquals(PackProfile.OPTIMIZE_SEMI, selection.profiles().get(id));
        assertTrue(selection.budgetKnown());
        assertTrue(selection.withinBudget());
    }

    @Test
    void equalCostProfileCombinationsPreferEarlierPackDeterministically() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = new LinkedHashMap<>();
        variants.put(second, Map.of(PackProfile.FULL, audit(80), PackProfile.SAFE, audit(60)));
        variants.put(first, Map.of(PackProfile.FULL, audit(60), PackProfile.SAFE, audit(40)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 120L),
                0, 0, true);

        assertEquals(PackProfile.FULL, selection.profiles().get(first));
        assertEquals(PackProfile.SAFE, selection.profiles().get(second));
    }

    @Test
    void automaticSelectionUsesAValidatedLowerFileCountVariant() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, auditWithFileCount(50, DeviceProfilePolicy.AUTO_PACK_FILE_LIMIT + 1),
                PackProfile.SAFE, auditWithFileCount(50, DeviceProfilePolicy.AUTO_PACK_FILE_LIMIT)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 100L), 0, 0, true);

        assertEquals(PackProfile.SAFE, selection.profiles().get(id));
        assertEquals(DeviceProfilePolicy.AUTO_PACK_FILE_LIMIT, selection.requiredPackFileCount());
        assertTrue(selection.withinPackFileLimit());
        assertTrue(selection.satisfiesPolicy());
    }

    @Test
    void autoRejectsEveryCandidateWhenRequiredStackExceedsFileCountPolicy() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, auditWithFileCount(50, DeviceProfilePolicy.AUTO_PACK_FILE_LIMIT + 1),
                PackProfile.SAFE, auditWithFileCount(10, DeviceProfilePolicy.AUTO_PACK_FILE_LIMIT + 1)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 100L), 0, 0, true);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.OVER_BUDGET, selection.status());
        assertFalse(selection.withinPackFileLimit());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void autoAllowsAnAddonAboveRecommendationButRejectsTheEngineLimitBoundary() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, auditWithHandleCount(20, DeviceProfilePolicy.AUTO_TEXTURE_HANDLE_LIMIT + 1),
                PackProfile.SAFE, auditWithHandleCount(10, DeviceProfilePolicy.AUTO_TEXTURE_HANDLE_LIMIT)));

        DeviceProfilePolicy.Selection boundary = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 100L), 0, 0, true);

        assertEquals(PackProfile.SAFE, boundary.profiles().get(id));
        assertEquals(DeviceProfilePolicy.AUTO_TEXTURE_HANDLE_LIMIT, boundary.estimatedTextureHandleUpperBound());
        assertTrue(boundary.withinTextureHandleLimit());
        assertTrue(boundary.satisfiesPolicy());
    }

    @Test
    void autoAcceptsAnEstimateAbovePerAddonGuidanceButBelowEngineLimit() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.SAFE, auditWithHandleCount(10, 1_152)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(0), variants, Map.of(0, 100L), 0, 0, true);

        assertEquals(PackProfile.SAFE, selection.profiles().get(id));
        assertTrue(selection.withinTextureHandleLimit());
        assertTrue(selection.satisfiesPolicy());
    }

    @Test
    void autoDoesNotSelectAnyVariantWhenEveryCandidateExceedsTextureHandleLimit() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, auditWithHandleCount(20, DeviceProfilePolicy.AUTO_TEXTURE_HANDLE_LIMIT + 1),
                PackProfile.SAFE, auditWithHandleCount(10, DeviceProfilePolicy.AUTO_TEXTURE_HANDLE_LIMIT + 2)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 100L), 0, 0, true);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.OVER_BUDGET, selection.status());
        assertFalse(selection.withinTextureHandleLimit());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void fixedPackHandleEstimateCountsTowardTheWholeStackLimit() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.SAFE, auditWithHandleCount(10, 1_001)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 100L),
                0, 0, true, List.of(), 0, 0, true,
                0, DeviceProfilePolicy.AUTO_PACK_FILE_LIMIT, 3_000, true,
                DeviceProfilePolicy.AUTO_TEXTURE_HANDLE_LIMIT);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(3_000, selection.estimatedTextureHandleUpperBound());
        assertFalse(selection.withinTextureHandleLimit());
        assertEquals(DeviceProfilePolicy.Status.OVER_BUDGET, selection.status());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void incompleteFixedPackHandleEstimateCannotQualifyAnAutomaticStack() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.SAFE, auditWithHandleCount(10, 100)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 100L),
                0, 0, true, List.of(), 0, 0, true,
                0, DeviceProfilePolicy.AUTO_PACK_FILE_LIMIT, 0, false,
                DeviceProfilePolicy.AUTO_TEXTURE_HANDLE_LIMIT);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.UNMEASURED, selection.status());
        assertFalse(selection.textureHandleMetricsComplete());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void actualNonSplitScaffoldingProfileCostsKeepTextureAndAtlasEstimatesSeparate() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(1176 * MIB, 1083 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(1116 * MIB, 1025 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(275 * MIB, 269 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(117 * MIB, 114 * MIB),
                PackProfile.SAFE, audit(58 * MIB, 57 * MIB)));

        DeviceProfilePolicy.Selection tier3 = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 450 * MIB),
                0, 0, true);
        DeviceProfilePolicy.Selection iosTier4 = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null,
                new BedrockDeviceCapabilities("IOS", 2, "iPhone", 4, 24, 2, true, "1.26.10"),
                variants, Map.of(4, 1200 * MIB), 0, 0, true);

        assertEquals(PackProfile.OPTIMIZE_MINI, tier3.profiles().get(id));
        assertEquals(117 * MIB, tier3.estimatedTextureBytes());
        assertTrue(tier3.withinBudget());
        assertEquals(PackProfile.OPTIMIZE_MINI, iosTier4.profiles().get(id));
        assertEquals(117 * MIB, iosTier4.estimatedTextureBytes());
        assertEquals(114 * MIB, iosTier4.estimatedItemAtlasTextureBytes());
        assertTrue(iosTier4.atlasMetricsComplete());
        assertTrue(iosTier4.withinStackAtlasPolicy());
        assertTrue(iosTier4.withinBudget());
    }

    @Test
    void missingIosMemoryTierUsesConservativeUnknownTierCapWithoutOsInference() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(600 * MIB),
                PackProfile.SAFE, audit(100 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null,
                new BedrockDeviceCapabilities("IOS", 2, "iPhone", null, null, null, null, "1.26.10"),
                variants, Map.of(),
                0, 0, true);
        DeviceProfilePolicy.Selection nonIos = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null,
                new BedrockDeviceCapabilities("WINDOWS", 8, "PC", null, null, null, null, "1.26.10"),
                variants, Map.of(), 0, 0, true);

        assertEquals(PackProfile.SAFE, selection.profiles().get(id));
        assertEquals(selection.profiles(), nonIos.profiles());
        assertEquals(300 * MIB, selection.textureBudgetBytes());
        assertEquals(100 * MIB, selection.estimatedTextureBytes());
        assertFalse(selection.budgetKnown());
        assertFalse(selection.withinBudget());
    }

    @Test
    void fixedOptimizedProfileUsesConservativeBudgetWhenMemoryTierIsUnknown() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(
                id, Map.of(PackProfile.SAFE, audit(350 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.SAFE, null, capabilities(null), variants, Map.of(), 0, 0, true);

        assertEquals(PackProfile.SAFE, selection.profiles().get(id));
        assertEquals(300 * MIB, selection.textureBudgetBytes());
        assertFalse(selection.budgetKnown());
        assertFalse(selection.satisfiesPolicy());
        assertTrue(FettleExtension.mustRejectSelection(false, selection));
    }

    @Test
    void onlyAnExplicitOriginalProfileBypassesFailedSafetyPolicy() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(
                id, Map.of(PackProfile.FULL, audit(350 * MIB)));
        Map<Integer, Long> budgets = Map.of(3, 100 * MIB);

        DeviceProfilePolicy.Selection original = DeviceProfilePolicy.selectAll(
                PackProfile.FULL, null, capabilities(3), variants, budgets, 0, 0, true);
        DeviceProfilePolicy.Selection automatic = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, budgets, 0, 0, true);

        assertFalse(original.satisfiesPolicy());
        assertFalse(FettleExtension.mustRejectSelection(false, original));
        assertTrue(FettleExtension.mustRejectSelection(true, automatic));
    }

    @Test
    void textureBudgetAndAtlasPolicyAreAppliedAsSeparateMeasurements() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(600 * MIB, 200 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(200 * MIB, 100 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(150 * MIB, 64 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(2), variants, Map.of(2, 300 * MIB),
                0, 0, true);

        assertEquals(PackProfile.OPTIMIZE_SEMI, selection.profiles().get(id));
        assertEquals(200 * MIB, selection.estimatedTextureBytes());
        assertEquals(100 * MIB, selection.estimatedItemAtlasTextureBytes());
        assertTrue(selection.withinBudget());
    }

    @Test
    void automaticSelectionRespectsCreatorToolsAtlasHardLimitInAdditionToTextureBudget() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB, 1100 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(600 * MIB, 1000 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(276 * MIB, 276 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(118 * MIB, 118 * MIB),
                PackProfile.SAFE, audit(59 * MIB, 59 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(2), variants, Map.of(2, 300 * MIB),
                0, 0, true);

        assertEquals(PackProfile.OPTIMIZE_MINI, selection.profiles().get(id));
        assertTrue(selection.withinBudget());
        assertTrue(selection.atlasMetricsComplete());
        assertTrue(selection.withinStackAtlasPolicy());
        assertEquals(118 * MIB, selection.estimatedItemAtlasTextureBytes());
    }

    @Test
    void automaticSelectionWithoutMemoryTierStillHonorsMeasuredAtlasHardLimit() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB, 1100 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(600 * MIB, 1000 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(276 * MIB, 276 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(118 * MIB, 118 * MIB),
                PackProfile.SAFE, audit(59 * MIB, 59 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null,
                new BedrockDeviceCapabilities("IOS", 2, "iPhone", null, null, null, null, "1.26.10"),
                variants, Map.of(), 0, 0, true);

        assertEquals(PackProfile.OPTIMIZE_MINI, selection.profiles().get(id));
        assertFalse(selection.budgetKnown());
        assertTrue(selection.atlasMetricsComplete());
        assertTrue(selection.withinStackAtlasPolicy());
    }

    @Test
    void stackAtlasPolicyConservativelyCapsIndependentRequiredPacks() {
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        // Each SEMI pack is below the 256 MiB per-pack validator threshold; together they exceed Fettle's stack policy.
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(
                left, Map.of(
                        PackProfile.FULL, audit(100 * MIB, 180 * MIB),
                        PackProfile.OPTIMIZE_SEMI, audit(60 * MIB, 140 * MIB),
                        PackProfile.OPTIMIZE_MINI, audit(30 * MIB, 100 * MIB),
                        PackProfile.SAFE, audit(10 * MIB, 60 * MIB)),
                right, Map.of(
                        PackProfile.FULL, audit(100 * MIB, 180 * MIB),
                        PackProfile.OPTIMIZE_SEMI, audit(60 * MIB, 140 * MIB),
                        PackProfile.OPTIMIZE_MINI, audit(30 * MIB, 100 * MIB),
                        PackProfile.SAFE, audit(10 * MIB, 60 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(4), variants, Map.of(4, 1200 * MIB),
                0, 0, true);

        assertTrue(selection.profiles().values().contains(PackProfile.OPTIMIZE_SEMI));
        assertTrue(selection.profiles().values().contains(PackProfile.OPTIMIZE_MINI));
        assertEquals(240 * MIB, selection.estimatedItemAtlasTextureBytes());
        assertTrue(selection.withinStackAtlasPolicy());
    }

    @Test
    void fixedRequiredPackAtlasUseIsIncludedInAutomaticSelection() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(300 * MIB, 100 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(250 * MIB, 80 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(180 * MIB, 50 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(4), variants, Map.of(4, 1200 * MIB),
                0, 0, true, List.of(), 200 * MIB, 0, true);

        assertEquals(PackProfile.OPTIMIZE_MINI, selection.profiles().get(id));
        assertEquals(250 * MIB, selection.estimatedItemAtlasTextureBytes());
        assertTrue(selection.withinStackAtlasPolicy());
    }

    @Test
    void incompleteFixedAtlasCannotProduceAnAutomaticSelection() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(100 * MIB, 300 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(80 * MIB, 280 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(60 * MIB, 270 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(40 * MIB, 200 * MIB),
                PackProfile.SAFE, audit(20 * MIB, 100 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(4), variants, Map.of(4, 1200 * MIB),
                0, 0, true, List.of(), 0, 0, false);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.UNMEASURED, selection.status());
        assertFalse(selection.satisfiesPolicy());
        assertFalse(selection.atlasMetricsComplete());
        assertFalse(selection.withinStackAtlasPolicy());
        assertFalse(selection.withinBudget());
    }

    @Test
    void incompleteAtlasScanIsReportedAsUnknownRatherThanAFalseFit() {
        UUID id = UUID.randomUUID();
        PackAuditReport incompleteAtlas = new PackAuditReport(
                0, 0, 1, 0, 1, 100 * MIB,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, false, true, 0, 0, List.of(),
                List.of(), 0, 0, 0, 100 * MIB, 0);
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, incompleteAtlas));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(null), variants, Map.of(), 0, 0, true);

        assertFalse(selection.atlasMetricsComplete());
        assertFalse(selection.withinStackAtlasPolicy());
    }

    @Test
    void incompleteTextureMeasurementAlsoMakesAtlasFitUnknown() {
        UUID id = UUID.randomUUID();
        PackAuditReport incompleteTexture = new PackAuditReport(
                0, 0, 1, 0, 1, 100 * MIB,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                false, true, true, 1, 0, List.of(),
                List.of(), 0, 0, 0, 0, 0);
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(PackProfile.FULL, incompleteTexture));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(null), variants, Map.of(), 0, 0, true);

        assertFalse(selection.metricsComplete());
        assertFalse(selection.atlasMetricsComplete());
        assertFalse(selection.withinStackAtlasPolicy());
    }

    @Test
    void whenNoVariantFitsItReturnsOverBudgetWithoutSelectingAnyPack() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(200 * MIB),
                PackProfile.SAFE, audit(100 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(0), variants, Map.of(0, 50 * MIB),
                0, 0, true);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.OVER_BUDGET, selection.status());
        assertFalse(selection.satisfiesPolicy());
        assertFalse(selection.profiles().containsValue(PackProfile.FULL));
        assertFalse(selection.withinBudget());
    }

    @Test
    void unknownTierWithIncompleteFixedPackCostsCannotSelectFull() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(120 * MIB),
                PackProfile.SAFE, audit(40 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(null), variants, Map.of(),
                10 * MIB, 0, false);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.UNMEASURED, selection.status());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void unknownTierUsesItsOwnConfiguredBudget() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(70 * MIB),
                PackProfile.SAFE, audit(40 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(null), variants,
                Map.of(DeviceProfilePolicy.UNKNOWN_TIER_CONFIG_KEY, 50 * MIB), 0, 0, true);

        assertEquals(50 * MIB, selection.textureBudgetBytes());
        assertEquals(PackProfile.SAFE, selection.profiles().get(id));
        assertTrue(selection.satisfiesPolicy());
        assertFalse(selection.profiles().containsValue(PackProfile.FULL));
    }

    @Test
    void unknownTierDoesNotInheritAHighTierBudget() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(1000 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(700 * MIB),
                PackProfile.OPTIMIZE_SEMI, audit(500 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(350 * MIB),
                PackProfile.SAFE, audit(200 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(null), variants, Map.of(4, 1200 * MIB),
                0, 0, true);

        assertEquals(300 * MIB, selection.textureBudgetBytes());
        assertEquals(PackProfile.SAFE, selection.profiles().get(id));
        assertFalse(selection.budgetKnown());
        assertTrue(selection.satisfiesPolicy());
    }

    @Test
    void unknownTierHonorsALowerConfiguredTierBudget() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(1000 * MIB),
                PackProfile.SAFE, audit(200 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(null), variants,
                Map.of(0, 100 * MIB, 4, 1200 * MIB), 0, 0, true);

        assertEquals(100 * MIB, selection.textureBudgetBytes());
        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.OVER_BUDGET, selection.status());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void invalidSafeVariantDoesNotTurnAnOverBudgetFullPackIntoSuccess() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB),
                PackProfile.SAFE, unsupportedButMeasuredAudit(10 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(0), variants, Map.of(0, 50 * MIB),
                0, 0, true);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.OVER_BUDGET, selection.status());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void incompleteTextureHandleScanCannotQualifyAnAutomaticVariant() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.SAFE, auditWithIncompleteHandleScan(10 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(0), variants, Map.of(0, 50 * MIB),
                0, 0, true);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.UNMEASURED, selection.status());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void modelComplexitySkippedByTheBoundedScannerCannotQualifyAnAutomaticVariant() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.SAFE, auditWithOversizedModel(10 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(0), variants, Map.of(0, 50 * MIB),
                0, 0, true);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.UNMEASURED, selection.status());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void unsupportedOnlyCandidateHasAnUnsupportedResult() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.SAFE, PackAuditReport.unsupported("encrypted pack")));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(0), variants, Map.of(0, 50 * MIB),
                0, 0, true);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.UNSUPPORTED, selection.status());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void unknownTextureCostIsNotTreatedAsTheSmallestVariant() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB, false),
                PackProfile.OPTIMIZE_MINI, audit(200 * MIB, false),
                PackProfile.SAFE, audit(0, false)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(0), variants, Map.of(0, 50 * MIB),
                0, 0, true);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.UNMEASURED, selection.status());
        assertFalse(selection.metricsComplete());
        assertFalse(selection.withinBudget());
    }

    @Test
    void incompleteFixedPackAuditDoesNotForceTheSmallestFettleProfile() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(250 * MIB),
                PackProfile.SAFE, audit(100 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 450 * MIB),
                50 * MIB, 0, false);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.UNMEASURED, selection.status());
        assertFalse(selection.metricsComplete());
        assertFalse(selection.withinBudget());
    }

    @Test
    void allocatesOneTierBudgetAcrossTheWholeRequiredPackSet() {
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(
                first, Map.of(PackProfile.OPTIMIZE_FULL, audit(300 * MIB), PackProfile.SAFE, audit(120 * MIB)),
                second, Map.of(PackProfile.OPTIMIZE_FULL, audit(300 * MIB), PackProfile.SAFE, audit(120 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(2), variants, Map.of(2, 450 * MIB),
                0, 0, true);

        assertEquals(450 * MIB, selection.textureBudgetBytes());
        assertEquals(420 * MIB, selection.estimatedTextureBytes());
        assertEquals(1, selection.profiles().values().stream()
                .filter(profile -> profile == PackProfile.OPTIMIZE_FULL).count());
        assertEquals(1, selection.profiles().values().stream()
                .filter(profile -> profile == PackProfile.SAFE).count());
        assertTrue(selection.withinBudget());
    }

    @Test
    void sumsFractionalMebibytePackCostsBeforeApplyingTheBudget() {
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = new LinkedHashMap<>();
        for (int index = 1; index <= 3; index++) {
            UUID id = UUID.fromString(String.format("00000000-0000-4000-8000-%012d", index));
            variants.put(id, Map.of(
                    PackProfile.FULL, audit(400L * 1024),
                    PackProfile.OPTIMIZE_SEMI, audit(300L * 1024)));
        }

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 2 * MIB),
                0, 0, true);

        assertEquals(3, selection.profiles().values().stream()
                .filter(profile -> profile == PackProfile.FULL).count());
        assertEquals(1200L * 1024, selection.estimatedTextureBytes());
        assertTrue(selection.withinBudget());
    }

    @Test
    void reconstructsACompleteSelectionForLargePackSets() {
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = new LinkedHashMap<>();
        for (int index = 1; index <= 40; index++) {
            UUID id = UUID.fromString(String.format("00000000-0000-4000-8000-%012d", index));
            variants.put(id, Map.of(
                    PackProfile.FULL, audit(50 * MIB),
                    PackProfile.OPTIMIZE_FULL, audit(45 * MIB),
                    PackProfile.OPTIMIZE_SEMI, audit(30 * MIB),
                    PackProfile.OPTIMIZE_MINI, audit(15 * MIB),
                    PackProfile.SAFE, audit(5 * MIB)));
        }

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(4), variants, Map.of(4, 1200 * MIB),
                0, 0, true);

        assertEquals(40, selection.profiles().size());
        assertTrue(selection.withinBudget());
    }

    @Test
    void automaticSearchStopsAtTheBoundedParetoFrontierWithoutPickingAPartialResult() {
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = new LinkedHashMap<>();
        long weight = 1;
        for (int index = 0; index < 6; index++, weight *= 5) {
            Map<PackProfile, PackAuditReport> profiles = new java.util.EnumMap<>(PackProfile.class);
            profiles.put(PackProfile.SAFE, auditWithHandleCount(0, (int) (4 * weight)));
            profiles.put(PackProfile.OPTIMIZE_MINI, auditWithHandleCount(weight, (int) (3 * weight)));
            profiles.put(PackProfile.OPTIMIZE_SEMI, auditWithHandleCount(2 * weight, (int) (2 * weight)));
            profiles.put(PackProfile.OPTIMIZE_FULL, auditWithHandleCount(3 * weight, (int) weight));
            profiles.put(PackProfile.FULL, auditWithHandleCount(4 * weight, 0));
            variants.put(UUID.randomUUID(), profiles);
        }

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(3), variants, Map.of(3, 1_000_000L),
                0, 0, true, List.of(), 0, 0, true,
                0, 100_000, 0, true, 100_000);

        assertTrue(selection.profiles().isEmpty());
        assertEquals(DeviceProfilePolicy.Status.SEARCH_LIMIT, selection.status());
        assertFalse(selection.satisfiesPolicy());
    }

    @Test
    void explicitAdminProfileOverridesAutomaticSelection() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB),
                PackProfile.SAFE, audit(100 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, PackProfile.FULL, capabilities(0), variants, Map.of(),
                0, 0, true);

        assertEquals(PackProfile.FULL, selection.profiles().get(id));
    }

    @Test
    void validatesConfigBudgetAndRejectsUnsupportedTierValues() {
        assertEquals(0L, DeviceProfilePolicy.parseTextureBudgetMib("0"));
        assertEquals(8192L * MIB, DeviceProfilePolicy.parseTextureBudgetMib("8192"));
        assertNull(DeviceProfilePolicy.parseTextureBudgetMib("8193"));
        assertNull(DeviceProfilePolicy.parseTextureBudgetMib("-1"));
    }

    private static BedrockDeviceCapabilities capabilities(Integer memoryTier) {
        return new BedrockDeviceCapabilities("WINDOWS", 8, "Example Device", memoryTier, 24, 2, true, "1.26.10");
    }

    private static PackAuditReport audit(long textureBytes) {
        return audit(textureBytes, true);
    }

    private static PackAuditReport auditWithIncompleteHandleScan(long textureBytes) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, false, 0, 0, List.of());
    }

    private static PackAuditReport auditWithOversizedModel(long textureBytes) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                1, 0, null,
                true, true, true, 0, 0, List.of(),
                List.of(), 1, 0, 0, 0, 0);
    }

    private static PackAuditReport unsupportedButMeasuredAudit(long textureBytes) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, true, 0, 0, List.of(),
                List.of(), 0, 0, 0, 0, 0, false, true);
    }

    private static PackAuditReport audit(long textureBytes, boolean complete) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                complete, complete, true, complete ? 0 : 1, 0, List.of());
    }

    private static PackAuditReport audit(long textureBytes, long itemAtlasBytes) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, true, 0, 0, List.of(),
                List.of(), 0, 0, 0, itemAtlasBytes, 0);
    }

    private static PackAuditReport auditWithHandleCount(long textureBytes, int handleCount) {
        return new PackAuditReport(
                0, 0, 1, handleCount, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, true, 0, 0, List.of());
    }

    private static PackAuditReport auditWithFileCount(long textureBytes, int fileCount) {
        return new PackAuditReport(
                0, 0, fileCount, 0, 0, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                true, true, true, 0, 0, List.of());
    }
}
