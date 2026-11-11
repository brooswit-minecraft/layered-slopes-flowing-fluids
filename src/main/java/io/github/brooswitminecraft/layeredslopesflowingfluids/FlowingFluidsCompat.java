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
 * <p>Self-review 53101: the mixin's {@code HEAD}/{@code RETURN} pair alone is not exception-safe
 * -- a thrown setter leaves {@code RETURN} never firing, permanently stuck positive, after which
 * every later manual edit at any position would incorrectly qualify as real movement. {@link
 * #onServerTickStart} forces this back to {@code 0} at the start of every single server tick,
 * bounding any such leak to, at most, the remainder of the one tick it happened on -- the
 * {@code HEAD}/{@code RETURN} pair still handles the overwhelming non-exceptional case exactly
 * (including correctly nested calls), this is strictly a bounded safety net under it, not a
 * replacement for it. {@link #isLoaded} is cached process-wide the same as everywhere else in
 * this class, with no per-world-session reset needed: {@link #levelingDepth} is reset by {@link
 * #onServerStarting} for the same reason (a fresh session starts clean, not carrying over
 * whatever a previous one happened to leave behind) and by every tick regardless, so a stale
 * value can survive, at most, until the very next tick starts, session boundary or not. Not
 * {@code ThreadLocal}-scoped: Minecraft's own world mutation (and therefore this exact setter)
 * only ever runs on the single server thread, the same assumption every other static field in
 * this class and in {@link RecentFlowActivity} already relies on without extra synchronization.
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
