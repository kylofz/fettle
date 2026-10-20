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
    void missingMemoryTierKeepsQualityInsteadOfSelectingSafeByPlatformGuess() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB),
                PackProfile.OPTIMIZE_FULL, audit(600 * MIB),
                PackProfile.SAFE, audit(100 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(null), variants, Map.of(),
                0, 0, true);

        assertEquals(PackProfile.FULL, selection.profiles().get(id));
        assertFalse(selection.budgetKnown());
        assertFalse(selection.withinBudget());
    }

    @Test
    void whenNoVariantFitsItSelectsTheSmallestMeasuredSetAndReportsTheOverage() {
        UUID id = UUID.randomUUID();
        Map<UUID, Map<PackProfile, PackAuditReport>> variants = Map.of(id, Map.of(
                PackProfile.FULL, audit(800 * MIB),
                PackProfile.OPTIMIZE_MINI, audit(200 * MIB),
                PackProfile.SAFE, audit(100 * MIB)));

        DeviceProfilePolicy.Selection selection = DeviceProfilePolicy.selectAll(
                PackProfile.AUTO, null, capabilities(0), variants, Map.of(0, 50 * MIB),
                0, 0, true);

        assertEquals(PackProfile.SAFE, selection.profiles().get(id));
        assertEquals(100 * MIB, selection.estimatedTextureBytes());
        assertFalse(selection.withinBudget());
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

        assertEquals(PackProfile.FULL, selection.profiles().get(id));
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

        assertEquals(PackProfile.FULL, selection.profiles().get(id));
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

    private static PackAuditReport audit(long textureBytes, boolean complete) {
        return new PackAuditReport(
                0, 0, 1, 0, 1, textureBytes,
                null, 0, 0, 0,
                null, 0, 0, 0,
                0, 0, null,
                complete, complete, true, complete ? 0 : 1, 0, List.of());
    }
}
