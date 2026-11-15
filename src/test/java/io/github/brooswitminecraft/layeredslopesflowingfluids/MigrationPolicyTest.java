package io.github.brooswitminecraft.layeredslopesflowingfluids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MigrationPolicyTest {
    @Test
    void noLegacyFileDefaultsToDisabled() {
        assertFalse(MigrationPolicy.defaultErosionEnabled(null, null));
    }

    @Test
    void legacyErosionGenuinelyRunningCarriesForwardAsEnabled() {
        assertTrue(MigrationPolicy.defaultErosionEnabled(true, 128));
        assertTrue(MigrationPolicy.defaultErosionEnabled(true, null));
    }

    @Test
    void legacyMasterSwitchOffCarriesForwardAsDisabledRegardlessOfSamples() {
        // MINECRAFT-513: water erosion was never actually running if the master switch was off,
        // even if waterSamplesPerSecond was left at its nonzero default.
        assertFalse(MigrationPolicy.defaultErosionEnabled(false, 128));
        assertFalse(MigrationPolicy.defaultErosionEnabled(null, 128));
    }

    @Test
    void legacyZeroSamplesCarriesForwardAsDisabledEvenIfMasterSwitchWasOn() {
        // Water erosion was never actually running with waterSamplesPerSecond=0, regardless of
        // the master switch -- must not silently turn new fluid erosion on for this server.
        assertFalse(MigrationPolicy.defaultErosionEnabled(true, 0));
    }

    @Test
    void samplesPerSecondDefaultsToDynamicTerrainsOldValueWhenSet() {
        assertEquals(256, MigrationPolicy.defaultSamplesPerSecond(256));
        assertEquals(0, MigrationPolicy.defaultSamplesPerSecond(0));
    }

    @Test
    void samplesPerSecondFallsBackToTheOrdinaryDefaultWhenNeverSet() {
        assertEquals(128, MigrationPolicy.defaultSamplesPerSecond(null));
    }

    @Test
    void samplesPerSecondAtTheUpperBoundIsHonoredExactly() {
        // 4096 is FlowingFluidsConfig.SAMPLES_PER_SECOND's own defineInRange upper bound --
        // in-spec, must be honored exactly, not normalized away.
        assertEquals(4096, MigrationPolicy.defaultSamplesPerSecond(4096));
    }

    @Test
    void samplesPerSecondOneOverTheUpperBoundFallsBackToTheOrdinaryDefault() {
        // MINECRAFT-514 coordinator review (comment 53153): 4097 fits in an int (so
        // LegacyConfigMigration's own overflow guard never sees it) but is still out of
        // FlowingFluidsConfig's own 0..4096 spec -- passing it straight through to defineInRange's
        // default, or into a per-world .set()/persisted file, would be a real bug, not cosmetic.
        assertEquals(128, MigrationPolicy.defaultSamplesPerSecond(4097));
        assertEquals(128, MigrationPolicy.defaultSamplesPerSecond(5000));
        assertEquals(128, MigrationPolicy.defaultSamplesPerSecond(Integer.MAX_VALUE));
    }

    @Test
    void outOfSpecSamplesPerSecondDoesNotAffectTheErosionOptInPolicy() {
        // An out-of-spec sample count is normalized exactly like "never set" for BOTH policy
        // functions: with the master switch on, "never set" already carries forward as enabled
        // (legacyErosionEnabled true, legacyWaterSamplesPerSecond null -- see
        // legacyErosionGenuinelyRunningCarriesForwardAsEnabled), so an out-of-spec value must
        // produce that exact same opt-in outcome, explicitly, not by accident.
        assertTrue(MigrationPolicy.defaultErosionEnabled(true, 4097));
        assertTrue(MigrationPolicy.defaultErosionEnabled(true, Integer.MAX_VALUE));
        // The master switch still governs regardless of the (now-normalized) sample count.
        assertFalse(MigrationPolicy.defaultErosionEnabled(false, 4097));
        assertFalse(MigrationPolicy.defaultErosionEnabled(null, Integer.MAX_VALUE));
    }
}
