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
}
