package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config. {@code erosionEnabled}/{@code samplesPerSecond} mirror the keys Dynamic
 * Terrain used for water erosion before MINECRAFT-513 extracted it into this mod; their defaults
 * on first write migrate Dynamic Terrain's own pre-split choice (see {@link MigrationPolicy}/
 * {@link LegacyConfigMigration}) so a server that had water erosion genuinely running (or
 * genuinely off) is not silently flipped by the upgrade.
 */
public final class FlowingFluidsConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue EROSION_ENABLED;
    public static final ModConfigSpec.IntValue SAMPLES_PER_SECOND;
    public static final ModConfigSpec.DoubleValue CHANCE_SCALE;

    static {
        Boolean legacyErosionEnabled = LegacyConfigMigration.readLegacyErosionEnabled(
                FMLPaths.CONFIGDIR.get().resolve("dynamicterrain-server.toml"));
        Integer legacyWaterSamplesPerSecond = LegacyConfigMigration.readLegacyWaterSamplesPerSecond(
                FMLPaths.CONFIGDIR.get().resolve("dynamicterrain-server.toml"));
        boolean migratedErosionEnabled = MigrationPolicy.defaultErosionEnabled(legacyErosionEnabled, legacyWaterSamplesPerSecond);
        int migratedSamplesPerSecond = MigrationPolicy.defaultSamplesPerSecond(legacyWaterSamplesPerSecond);

        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        EROSION_ENABLED = builder
                .comment("Let flowing fluid erode layered/layerable blocks it passes over or beside. When false, this mod does nothing. "
                        + "Extracted from Dynamic Terrain (MINECRAFT-513): if this server previously had Dynamic Terrain's water erosion "
                        + "genuinely running (erosionEnabled=true AND waterSamplesPerSecond>0), that is carried over as this key's default "
                        + "on first write; otherwise this defaults to off, same as a fresh install.")
                .define("erosionEnabled", migratedErosionEnabled);
        SAMPLES_PER_SECOND = builder
                .comment("Random positions sampled around each player per second for flowing-fluid erosion. 0 turns it off. "
                        + "Defaults to Dynamic Terrain's old waterSamplesPerSecond value on first write if that was explicitly set.")
                .defineInRange("samplesPerSecond", migratedSamplesPerSecond, 0, 4096);
        CHANCE_SCALE = builder
                .comment("Bounded multiplier (0.0-1.0) applied on top of the hardness/blast-resistance-based erosion chance curve "
                        + "for every eligible attempt. 1.0 (default) is the curve as designed; 0.0 makes every attempt RESIST "
                        + "regardless of amount, letting an operator dial overall erosion probability down (or off) without "
                        + "changing samplesPerSecond's unrelated sampling cadence.")
                .defineInRange("chanceScale", 1.0, 0.0, 1.0);
        SPEC = builder.build();
    }

    private FlowingFluidsConfig() {}
}
