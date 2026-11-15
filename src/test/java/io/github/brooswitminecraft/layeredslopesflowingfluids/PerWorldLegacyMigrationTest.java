package io.github.brooswitminecraft.layeredslopesflowingfluids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure, Minecraft/NeoForge-free coverage of {@link PerWorldLegacyMigration}'s decision logic and
 * on-disk mechanism -- {@code decide}/{@code alreadyConfigured}/{@code writeOverrideFileAtomically}
 * only ever touch the {@code Path}s they are handed, the same {@code @TempDir}-per-world pattern
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
    void writeOverrideFileAtomicallyPersistsTheMigratedChoiceAndTheGivenChanceScaleAsAPerWorldOverride(@TempDir Path worldServerConfigDir) throws IOException {
        // chanceScale is passed in explicitly, not read from any live config here (this method
        // stays free of the NeoForge classpath this plain unit-test source set does not have --
        // see the class doc) -- coordinator review, MINECRAFT-514 comment 53155: it must be
        // written explicitly so a customized value survives, not merely left to NeoForge's own
        // spec default on a later "correcting" rewrite.
        PerWorldLegacyMigration.writeOverrideFileAtomically(worldServerConfigDir, new PerWorldLegacyMigration.Override(true, 64), 0.5);
        String written = Files.readString(worldServerConfigDir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME));
        assertTrue(written.contains("erosionEnabled = true"));
        assertTrue(written.contains("samplesPerSecond = 64"));
        assertTrue(written.contains("chanceScale = 0.5"));
    }

    @Test
    void writeOverrideFileAtomicallyLeavesNoTemporaryFileBehindOnSuccess(@TempDir Path worldServerConfigDir) throws IOException {
        PerWorldLegacyMigration.writeOverrideFileAtomically(worldServerConfigDir, new PerWorldLegacyMigration.Override(true, 64), 1.0);
        try (var entries = Files.list(worldServerConfigDir)) {
            java.util.List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertEquals(java.util.List.of(PerWorldLegacyMigration.OVERRIDE_FILE_NAME), names);
        }
    }

    @Test
    void writeOverrideFileAtomicallyNeverReplacesAConcurrentlyAppearingFinalFile(@TempDir Path worldServerConfigDir) throws IOException {
        // MINECRAFT-514 self-review (comment 53160): the final path is never overwritten by the
        // atomic move -- a final file that appeared in the meantime (an admin's own edit, or
        // another run of this exact migration winning a race) must win untouched, byte-for-byte,
        // and this must throw so the caller knows nothing of ITS OWN decision was persisted.
        String existing = "erosionEnabled = true\nsamplesPerSecond = 999\n";
        Path finalFile = worldServerConfigDir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME);
        Files.writeString(finalFile, existing);

        assertThrows(IOException.class, () ->
                PerWorldLegacyMigration.writeOverrideFileAtomically(worldServerConfigDir, new PerWorldLegacyMigration.Override(false, 0), 0.0));

        assertEquals(existing, Files.readString(finalFile), "an existing final file must survive byte-for-byte");
        try (var entries = Files.list(worldServerConfigDir)) {
            java.util.List<String> names = entries.map(p -> p.getFileName().toString()).toList();
            assertEquals(java.util.List.of(PerWorldLegacyMigration.OVERRIDE_FILE_NAME), names,
                    "no leftover temporary file may remain after a failed write");
        }
    }

    @Test
    void unsupportedHardLinksAreTranslatedIntoAnOrdinaryPersistenceFailure(@TempDir Path worldServerConfigDir) {
        // MINECRAFT-514 coordinator review (comment 53163): hard-link support is
        // filesystem/provider-dependent -- Files.createLink throws UnsupportedOperationException
        // (not IOException) when unavailable. This exact translation is independently testable
        // without a real filesystem that genuinely lacks hard-link support; the real createLink
        // call site (writeOverrideFileAtomically) is covered by
        // writeOverrideFileAtomicallyNeverReplacesAConcurrentlyAppearingFinalFile and the
        // GameTest-level applyNeverMutatesLiveValuesWhenPersistenceFails for the general
        // "persistence failed, treat it as an ordinary failure" contract this feeds into.
        UnsupportedOperationException cause = new UnsupportedOperationException("hard links not supported");
        IOException translated = PerWorldLegacyMigration.asPersistenceFailure(worldServerConfigDir, cause);
        assertEquals(cause, translated.getCause());
    }
}
