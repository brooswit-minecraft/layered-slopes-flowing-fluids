package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
 * what keeps one flow from multiplying erosion through repeated hooks: each sampled position is
 * considered at most once per second regardless of how many times the fluid above/beside it
 * re-flowed in that window, and the per-tick/per-second sample count is a hard, configurable
 * bound -- no world scans, forced chunk loads, or event-driven update storms.
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
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sampleAround(player.serverLevel(), player.blockPosition(), samples, player.getRandom());
        }
    }

    /** Sample random positions near {@code center}. Returns how many blocks eroded. */
    public static int sampleAround(ServerLevel level, BlockPos center, int samples, RandomSource random) {
        int changed = 0;
        for (int i = 0; i < samples; i++) {
            BlockPos pos = center.offset(random.nextInt(2 * RADIUS + 1) - RADIUS,
                    random.nextInt(2 * HEIGHT + 1) - HEIGHT, random.nextInt(2 * RADIUS + 1) - RADIUS);
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
