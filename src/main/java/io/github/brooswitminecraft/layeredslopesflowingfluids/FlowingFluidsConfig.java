package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config. Erosion defaults to OFF so existing worlds are safe. */
public final class FlowingFluidsConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue EROSION_ENABLED;
    public static final ModConfigSpec.IntValue SAMPLES_PER_SECOND;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        EROSION_ENABLED = builder
                .comment("Let flowing fluid erode layered/layerable blocks it passes over or beside. When false, this mod does nothing.")
                .define("erosionEnabled", false);
        SAMPLES_PER_SECOND = builder
                .comment("Random positions sampled around each player per second for flowing-fluid erosion. 0 turns it off.")
                .defineInRange("samplesPerSecond", 128, 0, 4096);
        SPEC = builder.build();
    }

    private FlowingFluidsConfig() {}
}
