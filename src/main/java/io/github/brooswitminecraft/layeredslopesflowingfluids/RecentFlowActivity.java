package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MINECRAFT-513 self-review 53033/53037/53041/53049/53050/53051: a bounded, per-world record of
 * a position Flowing Fluids' own tick was VERIFIED to actually touch -- real evidence of an
 * active transfer, independent of whatever {@code isSource()}/{@code getAmount()} the resulting
 * {@link FluidState} reports on its own. This exists specifically because Flowing Fluids
 * represents EVERY full (amount 8) cell as a source state (confirmed by decompiling its pinned
 * jar), including one its own finite simulation is actively feeding or draining -- {@code
 * isSource()} alone cannot tell a genuinely still-transferring full cell apart from a
 * permanently static one (an ocean, a lake) under that mod.
 *
 * <p>Earlier versions were rejected twice over. First (53041): gating on a bare
 * {@code BlockEvent.NeighborNotifyEvent} plus a nonempty current fluid is not proof of a
 * transfer (a manual placement or an unrelated generic notification also qualifies), and running
 * unconditionally made even plain vanilla static-source placement erosive. Second
 * (53049/53050/53051): gating on "Flowing Fluids is loaded" plus "the amount/source-ness
 * genuinely changed from what was last observed" still cannot tell a manual edit (a GameTest
 * fixture, a player, another mod) apart from Flowing Fluids' own engine -- an amount changing is
 * not evidence of WHO changed it. Both gaps are closed the same way: {@link
 * FlowingFluidsCompat#isCurrentlyTicking()} reports Flowing Fluids' own public
 * {@code isManeuveringFluids} flag, which is {@code true} for the entire duration of its real
 * fluid-tick method and {@code false} everywhere else, including during any manual {@code
 * Level.setBlock} call. Only a notification observed while that flag is {@code true} -- Flowing
 * Fluids actually governing this exact fluid right now, per {@link
 * FlowingFluidsCompat#isGoverning} querying its own config, not merely installed -- is recorded
 * here at all, so a manual edit (even one that produces an amount/source-ness change) is never
 * mistaken for a real transfer, and the plain vanilla static-source rule is untouched whenever
 * Flowing Fluids is absent, disabled, or not governing this fluid.
 *
 * <p>Bounded by design, per the ticket's "no persistent blocks, no forced chunk loads"
 * requirement: a hard-capped (4096 entries, oldest evicted first), time-windowed (40 ticks) map
 * populated purely by reacting to notifications the game already sends for other reasons.
 * {@link #onServerStarting} also clears it outright on every server start, and {@link
 * #recentlyChanged} additionally requires a nonnegative age -- self-review 53041 found that a
 * bare {@code currentTick - changedTick <= window} check could read as "recent" forever after a
 * tick-count reset (a new server/world session starting its tick counter back near zero) if a
 * stale entry with a larger recorded tick survived; both fixes apply independently so either
 * alone would already close that gap.
 */
final class RecentFlowActivity {
    /** Hard cap on tracked positions, evicting the least-recently-touched entry past it. */
    private static final int MAX_ENTRIES = 4096;
    /** A position counts as "recently changed" for this many ticks after it last actually did. */
    private static final int RECENT_WINDOW_TICKS = 40;

    private static final Map<GlobalPos, Long> lastVerifiedTick = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<GlobalPos, Long> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private RecentFlowActivity() {}

    /** Discards all recorded observations: a fresh server/world session starts with none. */
    static void onServerStarting(ServerStartingEvent event) {
        lastVerifiedTick.clear();
    }

    static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FluidState fluid = event.getState().getFluidState();
        GlobalPos key = GlobalPos.of(level.dimension(), event.getPos());
        // Any notification that does NOT qualify (empty, not Flowing-Fluids-governed, or not
        // actually happening during its own tick -- a manual edit) discards any existing record
        // for this exact position, not merely skips adding a new one: self-review 53074 found
        // that leaving a stale record in place let an empty/manual-replaced/non-governed cell
        // keep reading as "recently changed" for up to the rest of the window after the real
        // transfer evidence it was based on no longer applies.
        if (fluid.isEmpty() || !FlowingFluidsCompat.isGoverning(fluid) || !FlowingFluidsCompat.isCurrentlyTicking()) {
            lastVerifiedTick.remove(key);
            return;
        }
        lastVerifiedTick.put(key, (long) level.getServer().getTickCount());
    }

    /**
     * Whether {@code pos} was verified to actually be touched by Flowing Fluids' own tick
     * within the recent window -- always false whenever Flowing Fluids is absent, disabled, or
     * not governing the fluid CURRENTLY there (self-review 53074: the fluid actually present at
     * read time, not merely whatever it was when the record was made, since a position's fluid
     * can change without ever firing a disqualifying notification this class happens to see).
     */
    static boolean recentlyChanged(ServerLevel level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        if (fluid.isEmpty() || !FlowingFluidsCompat.isGoverning(fluid)) {
            return false;
        }
        Long changedAt = lastVerifiedTick.get(GlobalPos.of(level.dimension(), pos));
        if (changedAt == null) {
            return false;
        }
        long age = level.getServer().getTickCount() - changedAt;
        return age >= 0 && age <= RECENT_WINDOW_TICKS;
    }

    /**
     * Test-only: discards any recorded observation at {@code pos}. A GameTest fixture's OWN
     * {@code helper.setBlock} placement never records anything here in the first place (it never
     * runs while {@link FlowingFluidsCompat#isCurrentlyTicking()} is true), but a test may still
     * want to force a clean baseline before asserting.
     */
    static void forgetForTesting(ServerLevel level, BlockPos pos) {
        lastVerifiedTick.remove(GlobalPos.of(level.dimension(), pos));
    }
}
