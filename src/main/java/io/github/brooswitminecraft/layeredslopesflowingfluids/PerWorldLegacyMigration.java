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
 * NeoForge itself writes; confirmed NeoForge does NOT auto-create that override file, it stays
 * absent until something writes it). A world that had its OWN per-world Dynamic Terrain legacy
 * override (explicitly opting out of water erosion, say) with a global legacy config saying
 * otherwise would have that world-specific choice silently lost to the global-derived default.
 *
 * <p>This resolves that per world, on every {@code ServerAboutToStartEvent}: the single, sole
 * gate is whether THIS world's own override file -- {@code serverconfig/
 * layeredslopesflowingfluids-server.toml} -- already exists. If it does, for ANY reason (an
 * admin's own explicit edit, NeoForge's own default-write, or a previous run of this exact
 * migration), this never reads the legacy file, never calls {@code .set()}, and never writes
 * anything -- preserving that file (and whatever currently-live config it produced) completely,
 * byte for byte (coordinator review, MINECRAFT-514 comment 53149: an earlier marker-file design
 * checked only the marker, so an already-present override file with no marker yet got its
 * explicit content silently truncated to just the two migrated keys -- this has no separate
 * marker to go stale or disagree with the file it is supposed to be guarding). Only when the
 * override file is genuinely absent does this look at THIS world's own per-world legacy file: if
 * that is also absent, there is no world-specific signal to act on and nothing happens (the
 * already-loaded, global-default-derived config is left exactly as is). If it IS present, the
 * migrated choice is computed from THAT file alone (reusing {@link LegacyConfigMigration}/
 * {@link MigrationPolicy} exactly as the global path does, just pointed at a different file),
 * applied live for this run, and persisted as this world's own override file so a later restart
 * loads it natively through NeoForge's own per-world mechanism without this code's help.
 *
 * <p><b>Lifecycle evidence (coordinator review, MINECRAFT-514 comment 53153), confirmed live
 * against two real, successive {@code runGameTestServer} starts of the same world save, not just
 * read from NeoForge's docs:</b> {@link FlowingFluidsConfig#EROSION_ENABLED}/{@link
 * FlowingFluidsConfig#SAMPLES_PER_SECOND} are process-wide static {@code ModConfigSpec} value
 * singletons -- one shared in-memory backing store, matching one dedicated server process
 * serving exactly one selected world for its lifetime (confirmed by the {@code ConfigTracker}
 * "Loading configs type SERVER"/"Unloading configs type SERVER" pair bracketing each server
 * start/stop). On the FIRST start for a world whose own per-world override is still absent,
 * {@code ConfigTracker} loads the GLOBAL file (confirmed: its own debug log names the global
 * path, e.g. {@code .../config/layeredslopesflowingfluids-server.toml}) BEFORE {@code
 * ServerAboutToStartEvent} fires -- this method's {@code .set()} calls are what make that run's
 * LIVE values correct; the override file {@link #writeOverrideFile} persists is not re-read
 * during this same run. On a LATER, separate start of that same world, {@code ConfigTracker}
 * itself finds and loads the per-world override NATIVELY -- confirmed by its own log line,
 * {@code "Found config file override in path ./world/serverconfig/layeredslopesflowingfluids-
 * server.toml"} -- with no help from this class at all (by then {@link #alreadyConfigured} is
 * true, so {@link #apply} is a pure no-op). NeoForge also "corrects" this file's formatting on
 * load (restoring the full commented template around the values this class wrote) without
 * altering any of the three values themselves -- confirmed by inspecting the file before and
 * after that second start. {@code chanceScale} is explicitly written too (coordinator review,
 * MINECRAFT-514 comment 53155), captured as whatever is already effective at the moment of the
 * write (not a bare pass-through default), specifically so an admin's own customized global
 * {@code chanceScale} survives into this per-world file rather than getting silently reset to
 * NeoForge's own spec default by that "correcting" rewrite.
 */
final class PerWorldLegacyMigration {
    static final String LEGACY_FILE_NAME = "dynamicterrain-server.toml";
    static final String OVERRIDE_FILE_NAME = LayeredSlopesFlowingFluidsMod.MODID + "-server.toml";

    private PerWorldLegacyMigration() {}

    static void onServerAboutToStart(ServerAboutToStartEvent event) {
        apply(event.getServer().getWorldPath(LevelResource.ROOT).resolve("serverconfig"));
    }

    /**
     * Runs the migration for one world's {@code serverconfig} directory. Safe to call on every
     * server start for the same world: once this world's own override file exists (whether
     * written by this method or anyone/anything else), every later call is a pure no-op --
     * {@link #alreadyConfigured} is checked FIRST, before any legacy read or live {@code .set()}.
     * A fresh world with no per-world legacy file either makes NO live {@code .set()} call and NO
     * filesystem write at all (self-review, MINECRAFT-514 comment 53158: an earlier design wrote
     * a diagnostic-only file on every call, including this no-op branch and the already-configured
     * branch -- a production side effect on exactly the paths required to leave a world's own
     * config completely untouched).
     */
    static void apply(Path worldServerConfigDir) {
        if (alreadyConfigured(worldServerConfigDir)) {
            return;
        }
        Override override = decide(worldServerConfigDir);
        if (override == null) {
            return;
        }
        FlowingFluidsConfig.EROSION_ENABLED.set(override.erosionEnabled());
        FlowingFluidsConfig.SAMPLES_PER_SECOND.set(override.samplesPerSecond());
        try {
            // chanceScale is not itself part of Dynamic Terrain's legacy choice (it did not
            // exist there) -- captured here as whatever is ALREADY effective right now (loaded
            // moments ago from the global config, since this is this world's first start with
            // no per-world override yet) and written explicitly, so NeoForge's own "correcting"
            // rewrite of this minimal file on a later start fills it from THIS preserved value,
            // not silently from its own spec default (coordinator review, MINECRAFT-514 comment
            // 53155: an admin's customized global chanceScale must survive into a per-world
            // override this class creates, not get silently reset to 1.0).
            writeOverrideFile(worldServerConfigDir, override, FlowingFluidsConfig.CHANCE_SCALE.get());
        } catch (IOException e) {
            // The live .set() above already took effect for this run regardless; a write failure
            // here just risks a redundant re-attempt on the next server start (still gated by
            // alreadyConfigured, which would then see the file is still missing), not a silently
            // wrong decision or a lost admin edit.
            LayeredSlopesFlowingFluidsMod.LOGGER.warn(
                    "MINECRAFT-514 per-world legacy config migration could not write to {}", worldServerConfigDir, e);
        }
    }

    /**
     * @return true if this world already has its own override file -- for any reason. This is
     *     the ONLY gate {@link #apply} uses before touching live config or legacy files; there is
     *     deliberately no separate marker that could go stale relative to this file's own actual
     *     presence/content.
     */
    static boolean alreadyConfigured(Path worldServerConfigDir) {
        return Files.isRegularFile(worldServerConfigDir.resolve(OVERRIDE_FILE_NAME));
    }

    /**
     * Pure decision, free of any live config access: {@code null} when this world's own
     * per-world legacy file does not exist (nothing world-specific to act on), otherwise the
     * migrated choice computed from THAT file alone, via the same policy the global path uses.
     * Does not itself check {@link #alreadyConfigured} -- callers (namely {@link #apply}) must.
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
     * Persists {@code override} (plus the currently-effective {@code chanceScale}, passed in by
     * the caller rather than read here -- keeps this method free of any live {@code
     * FlowingFluidsConfig} access, so it stays testable without the NeoForge/Minecraft classpath
     * a plain unit test does not have) as this world's own per-world config override file, so a
     * future restart of this exact world loads the correct values natively through NeoForge's
     * own per-world serverconfig mechanism, independent of the global config's own default. Only
     * ever called by {@link #apply} when {@link #alreadyConfigured} has just confirmed no such
     * file exists yet, so this never overwrites or truncates existing content.
     */
    static void writeOverrideFile(Path worldServerConfigDir, Override override, double chanceScale) throws IOException {
        Files.createDirectories(worldServerConfigDir);
        Files.writeString(worldServerConfigDir.resolve(OVERRIDE_FILE_NAME),
                "erosionEnabled = " + override.erosionEnabled() + "\n"
                        + "samplesPerSecond = " + override.samplesPerSecond() + "\n"
                        + "chanceScale = " + chanceScale + "\n");
    }

    record Override(boolean erosionEnabled, int samplesPerSecond) {}
}
