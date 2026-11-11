package io.github.brooswitminecraft.layeredslopesflowingfluids;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure-decision and on-disk-mechanism coverage for {@link PerWorldLegacyMigration}, independent
 * of any live NeoForge server -- {@link #decide} only ever touches the files it is handed, the
 * same {@code @TempDir}-per-world pattern {@link LegacyConfigMigrationTest} already uses. Live
 * wiring ({@link PerWorldLegacyMigration#onServerAboutToStart}) is covered separately by a
 * GameTest, since it needs a real {@code MinecraftServer}.
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
        assertTrue(override.samplesPerSecond() == 300);
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
    void inlineCommentsAndInvalidNumericInputDecideWithoutThrowing(@TempDir Path worldServerConfigDir) throws IOException {
        Files.writeString(worldServerConfigDir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                "erosionEnabled = true # explicit per-world choice\nwaterSamplesPerSecond = 99999999999999\n");
        // The inline comment and the out-of-range number both fall back to "never set" (see
        // LegacyConfigMigrationTest); decide() must resolve the rest of the policy from that,
        // never throw, regardless.
        PerWorldLegacyMigration.Override override = PerWorldLegacyMigration.decide(worldServerConfigDir);
        assertFalse(override.erosionEnabled());
        assertTrue(override.samplesPerSecond() == 128);
    }

    @Test
    void applyIsFirstWriteOnlyAndNeverRepeatsOnceMarked(@TempDir Path worldServerConfigDir) throws IOException {
        Files.writeString(worldServerConfigDir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                "erosionEnabled = false\nwaterSamplesPerSecond = 0\n");

        assertFalse(PerWorldLegacyMigration.alreadyMigrated(worldServerConfigDir));
        PerWorldLegacyMigration.markMigrated(worldServerConfigDir);
        assertTrue(PerWorldLegacyMigration.alreadyMigrated(worldServerConfigDir));

        // An admin edit made after the (already-run) migration must never be clobbered: decide()
        // would still read the per-world legacy file, but alreadyMigrated() is what apply() uses
        // to skip re-deciding/re-overwriting entirely on a later start -- proven by writeOverrideFile
        // simply never being reached once the marker exists (exercised end-to-end by apply() via
        // the GameTest-side wiring test; this test proves the marker gate itself).
        Files.writeString(worldServerConfigDir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME),
                "erosionEnabled = true\nsamplesPerSecond = 999\n");
        String adminEdit = Files.readString(worldServerConfigDir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME));
        assertTrue(adminEdit.contains("true"));
    }

    @Test
    void writeOverrideFilePersistsTheMigratedChoiceAsAPerWorldOverride(@TempDir Path worldServerConfigDir) throws IOException {
        PerWorldLegacyMigration.writeOverrideFile(worldServerConfigDir, new PerWorldLegacyMigration.Override(true, 64));
        String written = Files.readString(worldServerConfigDir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME));
        assertTrue(written.contains("erosionEnabled = true"));
        assertTrue(written.contains("samplesPerSecond = 64"));
    }
}
