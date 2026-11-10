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
 * explicitly out of this ticket's scope (Dynamic Terrain's own heat erosion owns that). Only
 * flowing (non-source) contact counts -- see {@link FluidMath#amount}; still source water next
 * to a block is a no-op, distinguishing actively flowing fluid from static contact per the
 * ticket.
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
            FluidState fluid = level.getFluidState(pos.relative(direction));
            if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                strongest = Math.max(strongest, FluidMath.amount(fluid.isSource(), fluid.getAmount()));
            }
        }
        return strongest;
    }
}
