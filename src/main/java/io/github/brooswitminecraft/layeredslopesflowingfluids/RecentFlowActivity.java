package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MINECRAFT-513 self-review 53033/53037: a bounded, per-world record of "a fluid-bearing
 * position actually changed recently" -- real evidence of an active transfer, independent of
 * whatever {@code isSource()}/{@code getAmount()} the resulting {@link net.minecraft.world.level.material.FluidState}
 * reports. This exists specifically because the optional Flowing Fluids mod represents EVERY
 * full (amount 8) cell as a source state (confirmed by decompiling its pinned jar), including
 * one its own finite simulation is actively feeding or draining -- {@code isSource()} alone
 * cannot tell a genuinely still-transferring full cell apart from a permanently static one
 * (an ocean, a lake) under that mod. A bare "has room to spread" geometric check
 * ({@link FlowingFluidErosion#isAdvancing}) cannot help here either: a source state is, by
 * definition, never evaluated for where it could spread to -- only non-source states are.
 *
 * <p>Vanilla's {@code BlockEvent.NeighborNotifyEvent} already fires for every block state
 * change, fluid or not (the same event Dynamic Terrain's own {@code CaveIns} uses for a
 * different purpose) -- including every time Flowing Fluids calls {@code Level.setBlock} to
 * actually move fluid (confirmed by decompiling {@code FFFluidUtils.setFluidStateAtPosToNewAmount}/
 * {@code removeAllFluidAtPos}, both plain {@code setBlock} calls with neighbour-update flags).
 * Recording "this exact position's fluid just changed, at this tick" from that event -- not
 * from any Flowing-Fluids-specific API -- is therefore real, mod-agnostic evidence of an actual
 * transfer, usable for a source state too. No persistent blocks, no forced chunk loads, no
 * synthetic tracking entities: a bounded (hard-capped, time-windowed) map populated purely by
 * reacting to notifications the game already sends for other reasons.
 */
final class RecentFlowActivity {
    /** Hard cap on tracked positions, evicting the least-recently-touched entry past it. */
    private static final int MAX_ENTRIES = 4096;
    /** A position counts as "recently changed" for this many ticks after it last actually did. */
    private static final int RECENT_WINDOW_TICKS = 40;

    private static final Map<GlobalPos, Long> lastChangedTick = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<GlobalPos, Long> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private RecentFlowActivity() {}

    static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getState().getFluidState().isEmpty()) {
            return;
        }
        lastChangedTick.put(GlobalPos.of(level.dimension(), event.getPos()), (long) level.getServer().getTickCount());
    }

    /** Whether {@code pos} was recorded as an actual fluid change within the recent window. */
    static boolean recentlyChanged(ServerLevel level, BlockPos pos) {
        Long changedAt = lastChangedTick.get(GlobalPos.of(level.dimension(), pos));
        return changedAt != null && level.getServer().getTickCount() - changedAt <= RECENT_WINDOW_TICKS;
    }

    /**
     * Test-only: discards any recorded change at {@code pos}. A GameTest fixture's OWN
     * {@code helper.setBlock} placement is itself a genuine block change and fires the exact same
     * {@link net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent} a real in-game
     * fluid update would, so a test establishing a deliberately long-standing/static baseline
     * (rather than a just-placed one) must explicitly age its fixture's own placement out first.
     */
    static void forgetForTesting(ServerLevel level, BlockPos pos) {
        lastChangedTick.remove(GlobalPos.of(level.dimension(), pos));
    }
}
