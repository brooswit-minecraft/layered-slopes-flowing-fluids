package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MINECRAFT-514: {@link FlowingFluidsConfig}'s migrated default is computed once, at class
 * static-init, from the single GLOBAL {@code <instance>/config/dynamicterrain-server.toml} --
 * NeoForge SERVER configs are global unless a per-world {@code serverconfig/<modid>-server.toml}
 * override is explicitly present for that world (see the stock {@code serverconfig/readme.txt}
 * NeoForge itself writes). A world that had its OWN per-world Dynamic Terrain legacy override
 * (explicitly opting out of water erosion, say) with a global legacy config saying otherwise
 * would have that world-specific choice silently lost to the global-derived default.
 *
 * <p>This resolves that per world, the first time (and only the first time -- see {@link
 * #alreadyMigrated}) a server starts for a given world's save directory: if, and only if, THIS
 * world's own per-world legacy file exists, recompute the migrated choice from it (reusing
 * {@link LegacyConfigMigration}/{@link MigrationPolicy} exactly as the global path does, just
 * pointed at a different file) and apply it both live (for this run) and persisted (so this
 * world's own serverconfig override file carries it forward on every later restart, independent
 * of whatever the global default is). A fresh world with no per-world legacy file at all has no
 * world-specific signal to act on and is left completely alone -- its already-loaded
 * (global-default-derived) config, including any admin pre-write, is never touched.
 */
final class PerWorldLegacyMigration {
    static final String LEGACY_FILE_NAME = "dynamicterrain-server.toml";
    static final String OVERRIDE_FILE_NAME = LayeredSlopesFlowingFluidsMod.MODID + "-server.toml";
    static final String MARKER_FILE_NAME = LayeredSlopesFlowingFluidsMod.MODID + "-legacy-migrated.marker";

    private PerWorldLegacyMigration() {}

    static void onServerAboutToStart(ServerAboutToStartEvent event) {
        apply(event.getServer().getWorldPath(LevelResource.ROOT).resolve("serverconfig"));
    }

    /**
     * Runs the full first-write-only migration for one world's {@code serverconfig} directory.
     * Safe to call on every server start for the same world: a prior run's marker makes every
     * later call a no-op, preserving any explicit edit an admin made afterward.
     */
    static void apply(Path worldServerConfigDir) {
        if (alreadyMigrated(worldServerConfigDir)) {
            return;
        }
        Override override = decide(worldServerConfigDir);
        try {
            if (override != null) {
                FlowingFluidsConfig.EROSION_ENABLED.set(override.erosionEnabled());
                FlowingFluidsConfig.SAMPLES_PER_SECOND.set(override.samplesPerSecond());
                writeOverrideFile(worldServerConfigDir, override);
            }
            markMigrated(worldServerConfigDir);
        } catch (IOException e) {
            // Best-effort: the in-memory .set() above (if any) already took effect for this run
            // regardless, and a write failure here just risks a redundant re-attempt on the next
            // server start, not a silently wrong decision.
            LayeredSlopesFlowingFluidsMod.LOGGER.warn(
                    "MINECRAFT-514 per-world legacy config migration could not write to {}", worldServerConfigDir, e);
        }
    }

    /** @return true if this world's own legacy migration has already run (its marker is on disk). */
    static boolean alreadyMigrated(Path worldServerConfigDir) {
        return Files.isRegularFile(worldServerConfigDir.resolve(MARKER_FILE_NAME));
    }

    /**
     * Pure decision, free of any live config access: {@code null} when this world's own
     * per-world legacy file does not exist (nothing world-specific to act on), otherwise the
     * migrated choice computed from THAT file alone, via the same policy the global path uses.
     */
    static Override decide(Path worldServerConfigDir) {
        Path legacyFile = worldServerConfigDir.resolve(LEGACY_FILE_NAME);
        if (!Files.isRegularFile(legacyFile)) {
            return null;
        }
        Boolean legacyErosionEnabled = LegacyConfigMigration.readLegacyErosionEnabled(legacyFile);
        Integer legacyWaterSamplesPerSecond = LegacyConfigMigration.readLegacyWaterSamplesPerSecond(legacyFile);
        return new Override(
                MigrationPolicy.defaultErosionEnabled(legacyErosionEnabled, legacyWaterSamplesPerSecond),
                MigrationPolicy.defaultSamplesPerSecond(legacyWaterSamplesPerSecond));
    }

    /**
     * Persists {@code override} as this world's own per-world config override file so a future
     * restart of this exact world loads the correct values natively through NeoForge's own
     * per-world serverconfig mechanism, independent of the global config's own default. A plain,
     * minimal TOML file with just the two migrated keys -- an operator remains free to replace or
     * extend it afterward, since migration never runs again once {@link #markMigrated} fires.
     */
    static void writeOverrideFile(Path worldServerConfigDir, Override override) throws IOException {
        Files.createDirectories(worldServerConfigDir);
        Files.writeString(worldServerConfigDir.resolve(OVERRIDE_FILE_NAME),
                "erosionEnabled = " + override.erosionEnabled() + "\n"
                        + "samplesPerSecond = " + override.samplesPerSecond() + "\n");
    }

    /**
     * Marks this world's legacy migration as done so a later restart never re-applies or
     * re-overwrites an admin's own explicit edit of this world's config -- the same
     * first-write-only guarantee the global migration already gives via {@link
     * FlowingFluidsConfig}'s own static initializer running only once per process.
     */
    static void markMigrated(Path worldServerConfigDir) throws IOException {
        Files.createDirectories(worldServerConfigDir);
        Files.writeString(worldServerConfigDir.resolve(MARKER_FILE_NAME),
                "Marks that this world's own Dynamic Terrain legacy water-erosion config (if any) has "
                        + "already been migrated into " + OVERRIDE_FILE_NAME + ". Do not delete: removing it "
                        + "would let this mod silently recompute and overwrite this world's config again on "
                        + "the next server start.\n");
    }

    record Override(boolean erosionEnabled, int samplesPerSecond) {}
}
