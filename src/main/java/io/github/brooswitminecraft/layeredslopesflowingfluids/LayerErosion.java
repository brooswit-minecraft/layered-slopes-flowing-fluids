package io.github.brooswitminecraft.layeredslopesflowingfluids;

import io.github.brooswitminecraft.layeredslopes.Anchor;
import io.github.brooswitminecraft.layeredslopes.LayerMath;
import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import io.github.brooswitminecraft.layeredslopes.LayeredGrassBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * erode(amount): a probabilistic policy over direct layer removal. Callers only report how much
 * fluid abuse a block took; this class alone decides whether a layer is actually removed.
 *
 * <p>Unlike Dynamic Terrain's generic {@code Erosion} (which moves material downhill and only
 * degrades in place when it cannot move), this mod's explicit ticket scope (MINECRAFT-513) is
 * narrower: flowing fluid "has a chance to lower that block by removing a layer", full stop --
 * no redistribution to a neighbour. A full block's first hit converts it into a 15-layer layered
 * block (first conversion); a layered block at the minimum layer that loses its last layer
 * becomes air (final-layer behaviour). Erosion always disturbs grass-group material into dirt,
 * matching Dynamic Terrain's established disturb semantic for erosion (as opposed to manual
 * grading, which does not disturb).
 *
 * <p>The success chance is {@link ErosionMath#successChance}'s hardness/blast-resistance-based
 * curve scaled by the bounded, operator-facing {@link FlowingFluidsConfig#CHANCE_SCALE} (default
 * 1.0 leaves the curve as designed; 0.0 disables erosion entirely regardless of amount) --
 * {@code samplesPerSecond} alone only configures cadence, not probability, so both halves of the
 * ticket's "configurable bounded chance/cadence" requirement are covered independently.
 */
public final class LayerErosion {
    private LayerErosion() {}

    public enum Result { DISABLED, SKIPPED, RESISTED, PROTECTED, ERODED }

    public static Result erode(Level level, BlockPos pos, double amount, RandomSource random) {
        if (level.isClientSide() || !FlowingFluidsConfig.EROSION_ENABLED.get()) {
            return Result.DISABLED;
        }
        BlockState state = level.getBlockState(pos);
        if (!takesPart(state)) {
            return Result.SKIPPED;
        }
        double resistance = ErosionMath.resistance(state.getDestroySpeed(level, pos), state.getBlock().getExplosionResistance());
        double chance = ErosionMath.successChance(amount, resistance) * FlowingFluidsConfig.CHANCE_SCALE.get();
        if (random.nextDouble() >= chance) {
            return Result.RESISTED;
        }
        if (!Protection.mayErode(level, pos)) {
            return Result.PROTECTED;
        }
        applyErosion(level, pos, state);
        return Result.ERODED;
    }

    /** Layered block, or a full block with a registered layered variant. */
    public static boolean takesPart(BlockState state) {
        return state.getBlock() instanceof LayeredBlock || LayeredMaterials.layeredFor(state.getBlock()) != null;
    }

    private static void applyErosion(Level level, BlockPos pos, BlockState state) {
        LayeredBlock layered;
        int layers;
        Anchor anchor;
        if (state.getBlock() instanceof LayeredBlock lb) {
            layered = lb;
            layers = state.getValue(LayeredBlock.LAYERS);
            anchor = state.getValue(LayeredBlock.ANCHOR);
        } else {
            layered = LayeredMaterials.layeredFor(state.getBlock());
            layers = LayerMath.MAX_LAYERS;
            anchor = Anchor.FLOOR;
        }
        int remaining = layers - 1;
        if (remaining < LayerMath.MIN_LAYERS) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return;
        }
        LayeredBlock settled = LayeredMaterials.disturbed(layered);
        BlockState next = settled.defaultBlockState().setValue(LayeredBlock.LAYERS, remaining).setValue(LayeredBlock.ANCHOR, anchor);
        if (settled instanceof LayeredGrassBlock grass) {
            next = grass.withSnowy(level, pos, next);
        }
        level.setBlock(pos, next, Block.UPDATE_ALL);
    }
}
