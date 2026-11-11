package io.github.brooswitminecraft.layeredslopesflowingfluids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure, Minecraft/NeoForge-free coverage of {@link PerWorldLegacyMigration}'s decision logic and
 * on-disk mechanism -- {@code decide}/{@code alreadyConfigured}/{@code writeOverrideFile} only
 * ever touch the {@code Path}s they are handed, the same {@code @TempDir}-per-world pattern
 * {@link LegacyConfigMigrationTest} already uses. {@link PerWorldLegacyMigration#apply} itself
 * reads/writes {@link FlowingFluidsConfig}'s live {@code ModConfigSpec} fields, which require the
 * NeoForge/Minecraft classpath this plain unit-test source set does not have (same reason
 * {@code FlowingFluidErosionGameTests} lives under {@code src/main}, not {@code src/test}) --
 * {@code apply}'s full end-to-end behavior (live values, byte-for-byte preservation of an
 * existing override, idempotent repeat runs, two-world isolation) is covered there instead.
 */
class PerWorldLegacyMigrationTest {
    @Test
    void aWorldWithNoPerWorldLegacyFileHasNothingToDecide(@TempDir Path worldServerConfigDir) {
        // MINECRAFT-514: a genuinely fresh world (or one whose Dynamic Terrain install never had
        // a per-world override, only the global one) has no world-specific signal at all -- must
        // not fabricate an override from nothing.
        assertNull(PerWorldLegacyMigration.decide(worldServerConfigDir));
    }

    @Test
    void aWorldSpecificExplicitOptOutWinsOverWhatAGlobalLegacyTrueWouldHaveGiven(@TempDir Path worldServerConfigDir) throws IOException {
        // The exact bug this ticket targets: a world whose OWN per-world Dynamic Terrain config
        // explicitly turned water erosion off must have that win, independent of whatever the
        // global dynamicterrain-server.toml says (a global "true" is not even written here --
        // decide() must never consult it).
        Files.writeString(worldServerConfigDir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                "erosionEnabled = false\nwaterSamplesPerSecond = 0\n");
        PerWorldLegacyMigration.Override override = PerWorldLegacyMigration.decide(worldServerConfigDir);
        assertFalse(override.erosionEnabled());
    }

    @Test
    void aWorldSpecificExplicitOptInIsHonoredToo(@TempDir Path worldServerConfigDir) throws IOException {
        Files.writeString(worldServerConfigDir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                "erosionEnabled = true\nwaterSamplesPerSecond = 300\n");
        PerWorldLegacyMigration.Override override = PerWorldLegacyMigration.decide(worldServerConfigDir);
        assertTrue(override.erosionEnabled());
        assertEquals(300, override.samplesPerSecond());
    }

    @Test
    void anInlineCommentOnTheLegacyValueIsHonoredRatherThanTreatedAsAbsent(@TempDir Path worldServerConfigDir) throws IOException {
        // MINECRAFT-514 coordinator review (comment 53149): a trailing TOML comment is a valid
        // value, not an invalid one -- decide() must resolve the REAL legacy choice from it.
        Files.writeString(worldServerConfigDir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                "erosionEnabled = true # explicit per-world choice\nwaterSamplesPerSecond = 64 # tuned\n");
        PerWorldLegacyMigration.Override override = PerWorldLegacyMigration.decide(worldServerConfigDir);
        assertTrue(override.erosionEnabled());
        assertEquals(64, override.samplesPerSecond());
    }

    @Test
    void outOfRangeNumericLegacyInputDecidesWithoutThrowing(@TempDir Path worldServerConfigDir) throws IOException {
        Files.writeString(worldServerConfigDir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                "erosionEnabled = true\nwaterSamplesPerSecond = 99999999999999\n");
        // The out-of-range number falls back to "never set" (see LegacyConfigMigrationTest);
        // decide() must resolve the rest of the policy from that, never throw, regardless.
        PerWorldLegacyMigration.Override override = PerWorldLegacyMigration.decide(worldServerConfigDir);
        assertTrue(override.erosionEnabled());
        assertEquals(128, override.samplesPerSecond());
    }

    @Test
    void twoDistinctWorldsWithDifferentLegacyFilesDecideIndependently(
            @TempDir Path worldADir, @TempDir Path worldBDir) throws IOException {
        Files.writeString(worldADir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                "erosionEnabled = true\nwaterSamplesPerSecond = 128\n");
        Files.writeString(worldBDir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                "erosionEnabled = false\nwaterSamplesPerSecond = 0\n");

        assertTrue(PerWorldLegacyMigration.decide(worldADir).erosionEnabled());
        assertFalse(PerWorldLegacyMigration.decide(worldBDir).erosionEnabled());
    }

    @Test
    void alreadyConfiguredIsTrueOnlyWhenTheOverrideFileItselfExists(@TempDir Path worldServerConfigDir) throws IOException {
        // MINECRAFT-514 coordinator review (comment 53149): this is the ONLY gate apply() uses
        // before touching live config or legacy files -- deliberately no separate marker that
        // could disagree with whether the file it is supposed to be guarding actually exists.
        assertFalse(PerWorldLegacyMigration.alreadyConfigured(worldServerConfigDir));
        Files.writeString(worldServerConfigDir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME), "erosionEnabled = true\n");
        assertTrue(PerWorldLegacyMigration.alreadyConfigured(worldServerConfigDir));
    }

    @Test
    void writeOverrideFilePersistsTheMigratedChoiceAndTheGivenChanceScaleAsAPerWorldOverride(@TempDir Path worldServerConfigDir) throws IOException {
        // chanceScale is passed in explicitly, not read from any live config here (this method
        // stays free of the NeoForge classpath this plain unit-test source set does not have --
        // see the class doc) -- coordinator review, MINECRAFT-514 comment 53155: it must be
        // written explicitly so a customized value survives, not merely left to NeoForge's own
        // spec default on a later "correcting" rewrite.
        PerWorldLegacyMigration.writeOverrideFile(worldServerConfigDir, new PerWorldLegacyMigration.Override(true, 64), 0.5);
        String written = Files.readString(worldServerConfigDir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME));
        assertTrue(written.contains("erosionEnabled = true"));
        assertTrue(written.contains("samplesPerSecond = 64"));
        assertTrue(written.contains("chanceScale = 0.5"));
    }
}
