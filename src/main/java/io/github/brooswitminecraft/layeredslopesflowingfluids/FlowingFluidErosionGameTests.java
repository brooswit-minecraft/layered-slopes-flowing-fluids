package io.github.brooswitminecraft.layeredslopesflowingfluids;

import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import io.github.brooswitminecraft.layeredslopes.LayeredSlopesMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.function.Consumer;

/**
 * MINECRAFT-513 acceptance evidence: real runtime proof, through the actual production
 * decision methods (not direct state mutation), that flowing-fluid layer erosion behaves as the
 * ticket specifies. {@code successChance} is driven to exactly 0 or 1 by the {@code amount}
 * argument (0 forces RESISTED, an overwhelming amount forces ERODED) so these tests are
 * deterministic without needing a seeded/stubbed random -- {@link ErosionMath#successChance}
 * clamps to that range by construction.
 *
 * <p>Not covered here (left to manual/fleet verification per the ticket's "clearly distinguish
 * automated and unperformed manual tests"): the real per-second player-sampling cadence, and
 * actual multi-repo coexistence with Dynamic Terrain and/or Layered Slope Grading installed
 * together -- those need a real running server/pack, not a GameTest.
 */
@GameTestHolder(LayeredSlopesFlowingFluidsMod.MODID)
@PrefixGameTestTemplate(false)
public final class FlowingFluidErosionGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);
    /** Any amount large enough that successChance clamps to exactly 1.0 for any non-unbreakable block. */
    private static final double GUARANTEED_AMOUNT = 1_000_000.0;

    private FlowingFluidErosionGameTests() {}

    private static BlockState flowingWater() {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
    }

    @GameTest(template = "flat_platform")
    public static void flowingWaterBesideAFullBlockConvertsItToFifteenLayers(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        helper.setBlock(POS.relative(Direction.EAST), flowingWater());

        double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
        helper.assertTrue(amount > 0, "flowing water beside the block must report a positive erosion amount");

        var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), GUARANTEED_AMOUNT, helper.getLevel().getRandom());
        helper.assertTrue(result == LayerErosion.Result.ERODED, "expected ERODED, got " + result);

        BlockState after = helper.getBlockState(POS);
        helper.assertTrue(after.getBlock() == LayeredSlopesMod.LAYERED_STONE.get(),
                "a full stone block's first erosion hit must convert it to layered stone");
        helper.assertTrue(after.getValue(LayeredBlock.LAYERS) == 15,
                "first conversion must leave 15 layers, got " + after.getValue(LayeredBlock.LAYERS));
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void flowingWaterOverTheLastLayerErodesItToAir(GameTestHelper helper) {
        helper.setBlock(POS, LayeredSlopesMod.LAYERED_STONE.get().defaultBlockState().setValue(LayeredBlock.LAYERS, 1));
        helper.setBlock(POS.above(), flowingWater());

        double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
        helper.assertTrue(amount > 0, "flowing water over the block must report a positive erosion amount");

        var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), GUARANTEED_AMOUNT, helper.getLevel().getRandom());
        helper.assertTrue(result == LayerErosion.Result.ERODED, "expected ERODED, got " + result);
        helper.assertTrue(helper.getBlockState(POS).isAir(), "the final layer must erode away to air");
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void stationarySourceWaterReportsNoErosionAmount(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        helper.setBlock(POS.relative(Direction.EAST), Blocks.WATER.defaultBlockState()); // source: LEVEL defaults to 0

        double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
        helper.assertTrue(amount == 0, "still source water must never report an erosion amount, got " + amount);
        helper.assertTrue(helper.getBlockState(POS).is(Blocks.STONE), "the block must stay unchanged next to still source water");
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void aResistedRollLeavesTheBlockUnchanged(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());

        // amount=0 forces successChance to exactly 0.0, and nextDouble() is always >= 0.0.
        var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), 0.0, helper.getLevel().getRandom());
        helper.assertTrue(result == LayerErosion.Result.RESISTED, "expected RESISTED, got " + result);
        helper.assertTrue(helper.getBlockState(POS).is(Blocks.STONE), "a resisted roll must not change the block");
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void aCancelledProtectionHookLeavesTheBlockUnchanged(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        Consumer<BlockEvent.BreakEvent> listener = event -> event.setCanceled(true);
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), GUARANTEED_AMOUNT, helper.getLevel().getRandom());
            helper.assertTrue(result == LayerErosion.Result.PROTECTED, "expected PROTECTED, got " + result);
            helper.assertTrue(helper.getBlockState(POS).is(Blocks.STONE), "a cancelled protection hook must not change the block");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void erodingAFullGrassBlockSettlesItAsLayeredDirtNotLayeredGrass(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.GRASS_BLOCK.defaultBlockState());

        var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), GUARANTEED_AMOUNT, helper.getLevel().getRandom());
        helper.assertTrue(result == LayerErosion.Result.ERODED, "expected ERODED, got " + result);

        BlockState after = helper.getBlockState(POS);
        helper.assertTrue(after.getBlock() == LayeredSlopesMod.LAYERED_DIRT.get(),
                "erosion must disturb grass into dirt, matching Dynamic Terrain's established semantic, got " + after.getBlock());
        helper.assertTrue(after.getValue(LayeredBlock.LAYERS) == 15, "first conversion must leave 15 layers");
        helper.succeed();
    }
}
