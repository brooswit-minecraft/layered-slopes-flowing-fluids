package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.ModList;

/**
 * The only door to the optional Flowing Fluids mod: nothing else in this project may mention its
 * types. MINECRAFT-513 reviews (53041/53049/53050/53051) rejected gating {@link RecentFlowActivity}
 * on "Flowing Fluids is loaded" alone -- that cannot tell a manual edit (this mod's own test
 * fixtures included) apart from Flowing Fluids' own real fluid-moving simulation, and it cannot
 * tell "loaded but the mod or this fluid is disabled in its own config" apart from "actually
 * governing this fluid right now" (in which case vanilla's own tick runs regardless, per
 * {@code MixinFlowingFluid.ff$tickMixin}'s {@code ci.cancel()} being conditional on exactly that).
 *
 * <p>{@link #isCurrentlyTicking()} answers the real question directly: Flowing Fluids' own public
 * {@code FlowingFluids.isManeuveringFluids} flag (confirmed by decompiling the pinned jar) is
 * {@code true} for the entire duration of its {@code ff$tickMixin} fluid-tick method -- the one
 * and only place its mixin actually moves fluid -- and {@code false} the rest of the time,
 * including during any manual {@code Level.setBlock} call (a GameTest fixture, a player, another
 * mod) that happens to touch a fluid-bearing position. A notification observed while this is
 * {@code true} is therefore real, verified evidence of Flowing Fluids' own engine moving fluid,
 * not a guess from comparing amounts.
 */
final class FlowingFluidsCompat {
    private static Boolean present;

    private FlowingFluidsCompat() {}

    static boolean isLoaded() {
        if (present == null) {
            present = ModList.get().isLoaded("flowing_fluids");
        }
        return present;
    }

    /** Whether Flowing Fluids' own tick is actually governing this fluid right now (not merely installed). */
    static boolean isGoverning(FluidState fluid) {
        return isLoaded() && Bridge.isGoverning(fluid);
    }

    /** Whether a currently-running call stack is inside Flowing Fluids' own fluid-moving tick. */
    static boolean isCurrentlyTicking() {
        return isLoaded() && Bridge.isCurrentlyTicking();
    }

    /**
     * Test-only: runs {@code body} with Flowing Fluids' own {@code enableMod} toggled off, so
     * {@link #isGoverning} reads false and the vanilla fallback rule must apply -- a no-op (just
     * runs {@code body}) when Flowing Fluids is not loaded at all. Always restores the previous
     * value afterward via try/finally.
     */
    static void withModDisabledForTesting(Runnable body) {
        if (!isLoaded()) {
            body.run();
            return;
        }
        Bridge.withModDisabledForTesting(body);
    }

    /**
     * Test-only: runs {@code body} with water added to Flowing Fluids' own fluid blacklist, so
     * {@link #isGoverning} reads false for water specifically -- a no-op when Flowing Fluids is
     * not loaded. Always restores the previous blacklist afterward via try/finally.
     */
    static void withWaterIgnoredForTesting(Runnable body) {
        if (!isLoaded()) {
            body.run();
            return;
        }
        Bridge.withWaterIgnoredForTesting(body);
    }

    /** Holds the Flowing Fluids references so they load only after {@link #isLoaded()}. */
    private static final class Bridge {
        static boolean isGoverning(FluidState fluid) {
            var config = traben.flowing_fluids.FlowingFluids.config;
            return config.enableMod && config.isFluidAllowed(fluid.getType());
        }

        static boolean isCurrentlyTicking() {
            return traben.flowing_fluids.FlowingFluids.isManeuveringFluids;
        }

        static void withModDisabledForTesting(Runnable body) {
            var config = traben.flowing_fluids.FlowingFluids.config;
            boolean previous = config.enableMod;
            config.enableMod = false;
            try {
                body.run();
            } finally {
                config.enableMod = previous;
            }
        }

        static void withWaterIgnoredForTesting(Runnable body) {
            var config = traben.flowing_fluids.FlowingFluids.config;
            String waterId = net.minecraft.core.registries.BuiltInRegistries.FLUID
                    .getKey(net.minecraft.world.level.material.Fluids.WATER).toString();
            boolean added = config.fluidBlacklist.add(waterId);
            try {
                body.run();
            } finally {
                if (added) {
                    config.fluidBlacklist.remove(waterId);
                }
            }
        }
    }
}
