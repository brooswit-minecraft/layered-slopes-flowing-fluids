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

    /** Holds the Flowing Fluids references so they load only after {@link #isLoaded()}. */
    private static final class Bridge {
        static boolean isGoverning(FluidState fluid) {
            var config = traben.flowing_fluids.FlowingFluids.config;
            return config.enableMod && config.isFluidAllowed(fluid.getType());
        }

        static boolean isCurrentlyTicking() {
            return traben.flowing_fluids.FlowingFluids.isManeuveringFluids;
        }
    }
}
