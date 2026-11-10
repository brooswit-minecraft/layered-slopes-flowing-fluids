package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Flowing fluid as an erosion source. Each second, a bounded number of random positions around
 * each player are sampled; an eligible block with flowing fluid over it or beside it reports the
 * contact to {@link LayerErosion#erode}, which alone decides whether a layer is actually
 * removed. Gated by {@code erosionEnabled}.
 *
 * <p>Eligibility is by the vanilla {@link FluidTags#WATER} tag, not a hardcoded fluid list, so
 * compatible modded water-like fluids erode too without code changes here; lava/heat erosion is
 * explicitly out of this ticket's scope (Dynamic Terrain's own heat erosion owns that). A
 * contact's amount comes from {@link #contactAmount}: ordinarily, flowing (non-source) contact
 * only counts if it is genuinely {@link #isAdvancing} -- has an actual neighbour it could still
 * spread into right now -- so a stationary, locally-equalized flowing-type pool no-ops just like
 * a source does, distinct from an edge still advancing downhill (mirrors Flowing Fluids' own
 * decompiled {@code canFitIntoFluid} precondition, also vanilla's own, not Flowing-Fluids-
 * specific). But a source contact also counts, independent of that geometric check, if
 * {@link RecentFlowActivity} recorded it as having actually just changed: the optional Flowing
 * Fluids mod represents EVERY full cell as a source state, including one its own finite
 * simulation is actively feeding or draining (self-review 53037), so {@code isSource()} alone
 * cannot tell a genuinely still-transferring full cell apart from a permanently static ocean or
 * lake under that mod -- real, mod-agnostic change evidence can.
 *
 * <p>Sampling random positions once per second, rather than hooking fluid-flow update events, is
 * most of what keeps one flow from multiplying erosion through repeated hooks -- but that alone
 * is not sufficient: two players with overlapping sample radii can independently pick the exact
 * same block in the exact same second, which would attempt erosion on it twice from one flow
 * (self-review 52887's finding). {@code onServerTick} therefore shares one {@code visited} set
 * across every player sampled this second, keyed by dimension+position (not bare {@link BlockPos},
 * which collides across dimensions): the second player to land on an already-attempted position
 * skips it outright rather than rolling erosion again. Combined with the hard, configurable
 * per-player sample-count bound, this is the global one-attempt-per-position-per-second policy --
 * no world scans, forced chunk loads, or event-driven update storms.
 */
public final class FlowingFluidErosion {
    private static final int RADIUS = 16;
    private static final int HEIGHT = 6;

    private FlowingFluidErosion() {}

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0 || !FlowingFluidsConfig.EROSION_ENABLED.get()) {
            return;
        }
        int samples = FlowingFluidsConfig.SAMPLES_PER_SECOND.get();
        Set<GlobalPos> visited = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sampleAround(player.serverLevel(), player.blockPosition(), samples, player.getRandom(), visited);
        }
    }

    /** Sample random positions near {@code center}, deduplicating against no prior attempts. Returns how many blocks eroded. */
    public static int sampleAround(ServerLevel level, BlockPos center, int samples, RandomSource random) {
        return sampleAround(level, center, samples, random, new HashSet<>());
    }

    /**
     * Sample random positions near {@code center}. {@code visited} is shared across every caller
     * in the same tick (see {@link #onServerTick}): a position already attempted this second,
     * by this call or an earlier one sharing the same set, is skipped rather than re-rolled.
     * Returns how many blocks eroded.
     */
    public static int sampleAround(ServerLevel level, BlockPos center, int samples, RandomSource random, Set<GlobalPos> visited) {
        int changed = 0;
        for (int i = 0; i < samples; i++) {
            BlockPos pos = center.offset(random.nextInt(2 * RADIUS + 1) - RADIUS,
                    random.nextInt(2 * HEIGHT + 1) - HEIGHT, random.nextInt(2 * RADIUS + 1) - RADIUS);
            if (!markVisited(visited, level, pos)) {
                continue;
            }
            if (!level.isLoaded(pos) || !LayerErosion.takesPart(level.getBlockState(pos))) {
                continue;
            }
            double amount = strongestFlowAround(level, pos);
            if (amount > 0 && LayerErosion.erode(level, pos, amount, random) == LayerErosion.Result.ERODED) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * Records {@code pos} (keyed by dimension+position, since a bare {@link BlockPos} collides
     * across dimensions) as attempted this second. Returns true the first time a given
     * dimension+position is recorded against {@code visited}, false every time after -- the
     * exact mechanism that stops two players' overlapping sample radii from double-attempting
     * the same block (self-review 52887).
     */
    static boolean markVisited(Set<GlobalPos> visited, ServerLevel level, BlockPos pos) {
        return visited.add(GlobalPos.of(level.dimension(), pos));
    }

    /** The strongest flowing-fluid contact among the four sides and the cell above ("over or beside"), or 0. */
    static double strongestFlowAround(ServerLevel level, BlockPos pos) {
        double strongest = 0;
        for (Direction direction : Direction.values()) {
            if (direction == Direction.DOWN) {
                continue;
            }
            BlockPos contactPos = pos.relative(direction);
            if (!level.isLoaded(contactPos)) {
                continue;
            }
            FluidState fluid = level.getFluidState(contactPos);
            if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                strongest = Math.max(strongest, contactAmount(level, contactPos, fluid));
            }
        }
        return strongest;
    }

    /**
     * The erosion amount a single fluid contact contributes. A source contact is ordinarily
     * non-erosive, but counts at the maximum amount if {@link RecentFlowActivity} recorded it as
     * having genuinely just changed -- real, mod-agnostic evidence of an active transfer that a
     * bare {@code isSource()} check cannot provide once the optional Flowing Fluids mod is
     * involved (see the class doc). A non-source contact counts only if it is {@link #isAdvancing}.
     */
    static double contactAmount(ServerLevel level, BlockPos contactPos, FluidState fluid) {
        if (RecentFlowActivity.recentlyChanged(level, contactPos)) {
            return FluidMath.FLOWING;
        }
        if (fluid.isSource()) {
            return 0;
        }
        return isAdvancing(level, contactPos, fluid) ? FluidMath.amount(false, fluid.getAmount()) : 0;
    }

    /**
     * Whether the flowing (non-source) fluid at {@code fluidPos} has an actual neighbour it could
     * still spread into right now, as opposed to a stationary pool that has already equalized.
     * Mirrors Flowing Fluids' own decompiled {@code canFitIntoFluid} precondition (also vanilla's
     * own spread precondition, not something Flowing Fluids introduces): a horizontal neighbour
     * must be air (checked on the BLOCK, not the fluid state -- a solid block's {@code FluidState}
     * is ALSO {@code isEmpty()}, so a fluid-state-only check would misread a solid wall as open,
     * self-review 53036's finding) or the same fluid at a strictly lower amount; the downward
     * neighbour must be air or the same fluid below full (8). A full (amount 8), non-source cell
     * -- a falling waterfall column, a vanilla-only representation (see README) -- is always
     * advancing without needing a neighbour check. Only called for non-source contact; a source
     * is handled separately by the caller (sources never "advance" themselves).
     */
    static boolean isAdvancing(ServerLevel level, BlockPos fluidPos, FluidState fluid) {
        if (fluid.getAmount() >= FluidMath.FULL_FLUID) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (direction == Direction.UP) {
                continue;
            }
            BlockPos neighborPos = fluidPos.relative(direction);
            if (!level.isLoaded(neighborPos)) {
                continue;
            }
            // A solid block's FluidState is ALSO isEmpty() (no fluid there is not the same as "open
            // to flow into"): an ordinary air check on the BLOCK, not the fluid, is what actually
            // distinguishes "open" from "solid and blocking" (self-review 53036's finding).
            if (level.getBlockState(neighborPos).isAir()) {
                return true;
            }
            FluidState neighbor = level.getFluidState(neighborPos);
            if (neighbor.isEmpty() || !neighbor.getType().isSame(fluid.getType())) {
                continue;
            }
            if (direction == Direction.DOWN ? neighbor.getAmount() < FluidMath.FULL_FLUID : neighbor.getAmount() < fluid.getAmount()) {
                return true;
            }
        }
        return false;
    }
}
