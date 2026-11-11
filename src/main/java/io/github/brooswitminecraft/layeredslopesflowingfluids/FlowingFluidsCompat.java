package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The only door to the optional Flowing Fluids mod: nothing else in this project may mention its
 * types. MINECRAFT-513 reviews (53041/53049/53050/53051) rejected gating {@link RecentFlowActivity}
 * on "Flowing Fluids is loaded" alone -- that cannot tell a manual edit (this mod's own test
 * fixtures included) apart from Flowing Fluids' own real fluid-moving simulation, and it cannot
 * tell "loaded but the mod or this fluid is disabled in its own config" apart from "actually
 * governing this fluid right now" (in which case vanilla's own tick runs regardless, per
 * {@code MixinFlowingFluid.ff$tickMixin}'s {@code ci.cancel()} being conditional on exactly that).
 *
 * <p>{@link #isCurrentlyTicking()} answers the real question directly. Flowing Fluids' own public
 * {@code FlowingFluids.isManeuveringFluids} flag (confirmed by decompiling the pinned jar) is
 * {@code true} for the duration of its {@code ff$tickMixin} fluid-tick method and its
 * displacement path, and {@code false} the rest of the time, including during any manual {@code
 * Level.setBlock} call (a GameTest fixture, a player, another mod) that happens to touch a
 * fluid-bearing position. Self-review 53094 found, with bytecode-level evidence, that this flag
 * is NOT set during its periodic random-leveling smoothing tick ({@code
 * MixinFlowingFluid.randomTick}, active by default): that path calls the SAME low-level setter
 * ({@code FFFluidUtils.setFluidStateAtPosToNewAmount}) every other fluid-moving path ultimately
 * goes through, so {@link LevelingMixin} brackets exactly that one method with its own
 * {@link #levelingDepth} reentrancy counter instead of relying on Flowing Fluids' own flag --
 * covering every path, including this one, at its single lowest common point, in a dedicated
 * narrowly-targeted (one method), optional (`@Pseudo`, skipped entirely if Flowing Fluids is
 * absent, exactly the pattern Flowing Fluids itself uses for its own optional Create compat)
 * mixin rather than the usual plain-event approach this project otherwise uses throughout,
 * because no vanilla or NeoForge event fires around a fluid's random tick to hook instead. A
 * counter, not a boolean, because nested calls (a leveling transfer calling the setter twice,
 * inside a scheduled tick that may already be inside it once) must not have an inner call's
 * completion incorrectly clear the signal while an outer call is still in progress.
 *
 * <p>Self-review 53101/53103: an earlier version of {@code LevelingMixin} used a plain {@code
 * @Inject} {@code HEAD}/{@code RETURN} pair, which is NOT exception-safe -- a thrown setter
 * leaves {@code RETURN} never firing, permanently stuck positive, after which every later manual
 * edit at any position (same tick or any later one) would incorrectly qualify as real movement.
 * {@code LevelingMixin} now wraps the original setter call in a genuine {@code try/finally} (via
 * MixinExtras' {@code @WrapMethod}), exception-safe by construction -- a {@code finally} block
 * is a JLS guarantee to run on exceptional exit too, closing that window outright rather than
 * merely bounding it. {@link #onServerTickStart} forcing {@link #levelingDepth} back to {@code
 * 0} at the start of every single server tick, and {@link #onServerStarting} resetting it for a
 * fresh session, are kept purely as defense in depth under that real fix, not load-bearing
 * correctness -- bounding anything genuinely unanticipated to, at most, the remainder of one
 * tick, never the correctness mechanism itself. Not {@code ThreadLocal}-scoped: Minecraft's own
 * world mutation (and therefore this exact setter) only ever runs on the single server thread,
 * the same assumption every other static field in this class and in {@link RecentFlowActivity}
 * already relies on without extra synchronization.
 */
public final class FlowingFluidsCompat {
    private static Boolean present;
    /**
     * Reentrancy counter set by {@code io.github.brooswitminecraft.layeredslopesflowingfluids.mixin.LevelingMixin}
     * (a different package than this class, so this must be public -- see that class's own doc
     * comment for why it cannot live alongside this class); see the class doc above, including
     * why this is forcibly reset every tick regardless of the mixin's own HEAD/RETURN pairing.
     */
    public static int levelingDepth = 0;

    private FlowingFluidsCompat() {}

    /** Bounds any exception-leaked {@link #levelingDepth} to, at most, the tick it leaked on. */
    static void onServerTickStart(ServerTickEvent.Pre event) {
        levelingDepth = 0;
    }

    /** A fresh server/world session starts clean, not carrying over a previous one's state. */
    static void onServerStarting(ServerStartingEvent event) {
        levelingDepth = 0;
    }

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
        return isLoaded() && (Bridge.isCurrentlyTicking() || levelingDepth > 0);
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
