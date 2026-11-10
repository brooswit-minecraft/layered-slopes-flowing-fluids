package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MINECRAFT-513 self-review 53033/53037/53041: a bounded, per-world record of a position whose
 * fluid amount/source-ness was actually VERIFIED to change -- real evidence of an active
 * transfer, independent of whatever {@code isSource()}/{@code getAmount()} the resulting
 * {@link FluidState} reports on its own. This exists specifically because the optional Flowing
 * Fluids mod represents EVERY full (amount 8) cell as a source state (confirmed by decompiling
 * its pinned jar), including one its own finite simulation is actively feeding or draining --
 * {@code isSource()} alone cannot tell a genuinely still-transferring full cell apart from a
 * permanently static one (an ocean, a lake) under that mod.
 *
 * <p>Self-review 53041 rejected the first version of this class: a plain
 * {@code BlockEvent.NeighborNotifyEvent} with a nonempty current fluid is NOT proof of a
 * transfer on its own (a manual placement or an unrelated generic notification also qualifies),
 * and it ran unconditionally, making even plain vanilla static-source placement erosive --
 * contradicting the required no-Flowing-Fluids fallback. Fixed here two ways: (1) this class is
 * entirely inert ({@link #isFlowingFluidsLoaded()} false) unless Flowing Fluids is actually
 * loaded, so the vanilla static-source rule is untouched when it is absent; (2) "changed" is
 * only ever recorded when a position's fluid amount or source-ness genuinely differs from the
 * last amount/source-ness this class itself observed there -- a first-ever observation at a
 * position only establishes that baseline, it is never itself a "change". Re-notifying the same
 * unchanged state (a generic notification with nothing actually different) therefore never
 * extends or creates a "recently changed" window.
 *
 * <p>Vanilla's {@code BlockEvent.NeighborNotifyEvent} already fires for every block state
 * change, fluid or not (the same event Dynamic Terrain's own {@code CaveIns} uses for a
 * different purpose) -- including every time Flowing Fluids calls {@code Level.setBlock} to
 * actually move fluid (confirmed by decompiling {@code FFFluidUtils.setFluidStateAtPosToNewAmount}/
 * {@code removeAllFluidAtPos}, both plain {@code setBlock} calls with neighbour-update flags).
 * Comparing the amount/source-ness this event reports against what was last observed at that
 * exact position -- while Flowing Fluids is loaded -- is therefore real, mod-agnostic evidence
 * of an actual transfer, usable for a source state too, without hooking any Flowing-Fluids-
 * specific API.
 *
 * <p>Bounded by design, per the ticket's "no persistent blocks, no forced chunk loads"
 * requirement: a hard-capped (4096 entries, oldest evicted first), time-windowed (40 ticks) map
 * populated purely by reacting to notifications the game already sends for other reasons.
 * {@link #onServerStarting} also clears it outright on every server start, and
 * {@link #recentlyChanged} additionally requires a nonnegative age -- self-review 53041 found
 * that the first version's bare {@code currentTick - changedTick <= window} check could read as
 * "recent" forever after a tick-count reset (a new server/world session starting its tick
 * counter back near zero) if a stale entry with a larger recorded tick survived; both fixes
 * apply independently so either alone would already close that gap.
 */
final class RecentFlowActivity {
    /** Hard cap on tracked positions, evicting the least-recently-touched entry past it. */
    private static final int MAX_ENTRIES = 4096;
    /** A position counts as "recently changed" for this many ticks after it last actually did. */
    private static final int RECENT_WINDOW_TICKS = 40;
    /** Flowing Fluids' own modid, confirmed from its pinned jar's neoforge.mods.toml. */
    private static final String FLOWING_FLUIDS_MODID = "flowing_fluids";
    /** Sentinel changedTick meaning "only ever observed as a baseline, never seen to change". */
    private static final long NEVER = Long.MIN_VALUE;

    private record Observation(int amount, boolean source, long changedTick) {}

    private static final Map<GlobalPos, Observation> lastObserved = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<GlobalPos, Observation> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private RecentFlowActivity() {}

    /** Whether the optional Flowing Fluids mod is actually loaded this session. */
    static boolean isFlowingFluidsLoaded() {
        return ModList.get().isLoaded(FLOWING_FLUIDS_MODID);
    }

    /** Discards all recorded observations: a fresh server/world session starts with none. */
    static void onServerStarting(ServerStartingEvent event) {
        lastObserved.clear();
    }

    static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!isFlowingFluidsLoaded() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FluidState fluid = event.getState().getFluidState();
        if (fluid.isEmpty()) {
            return;
        }
        GlobalPos key = GlobalPos.of(level.dimension(), event.getPos());
        long tick = level.getServer().getTickCount();
        int amount = fluid.getAmount();
        boolean source = fluid.isSource();
        Observation previous = lastObserved.get(key);
        boolean genuineChange = previous != null && (previous.amount() != amount || previous.source() != source);
        long changedTick = genuineChange ? tick : (previous != null ? previous.changedTick() : NEVER);
        lastObserved.put(key, new Observation(amount, source, changedTick));
    }

    /**
     * Whether {@code pos} was verified to actually change (not merely re-notified unchanged, and
     * not merely a first-ever observation) within the recent window -- always false unless
     * Flowing Fluids is loaded.
     */
    static boolean recentlyChanged(ServerLevel level, BlockPos pos) {
        if (!isFlowingFluidsLoaded()) {
            return false;
        }
        Observation observation = lastObserved.get(GlobalPos.of(level.dimension(), pos));
        if (observation == null || observation.changedTick() == NEVER) {
            return false;
        }
        long age = level.getServer().getTickCount() - observation.changedTick();
        return age >= 0 && age <= RECENT_WINDOW_TICKS;
    }

    /**
     * Test-only: discards any recorded observation at {@code pos}. A GameTest fixture's OWN
     * {@code helper.setBlock} placement is itself a genuine block change and fires the exact same
     * {@link net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent} a real in-game
     * fluid update would, so a test establishing a deliberately long-standing/static baseline
     * (rather than a just-placed one) must explicitly discard its fixture's own placement first.
     */
    static void forgetForTesting(ServerLevel level, BlockPos pos) {
        lastObserved.remove(GlobalPos.of(level.dimension(), pos));
    }
}
