package io.github.brooswitminecraft.layeredslopesflowingfluids;

import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import io.github.brooswitminecraft.layeredslopes.LayeredSlopesMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;
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
 * automated and unperformed manual tests"): the real per-second player-sampling cadence with
 * actual multiple real players, and actual multi-repo coexistence with Dynamic Terrain and/or
 * Layered Slope Grading installed together -- those need a real running server/pack, not a
 * GameTest. The duplicate/cascade dedup mechanism itself (self-review 52887) IS covered, directly
 * and deterministically, by {@link #aPositionAlreadyVisitedThisSecondIsNotAttemptedAgain}.
 */
@GameTestHolder(LayeredSlopesFlowingFluidsMod.MODID)
@PrefixGameTestTemplate(false)
public final class FlowingFluidErosionGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);
    /** Any amount large enough that successChance clamps to exactly 1.0 for any non-unbreakable block. */
    private static final double GUARANTEED_AMOUNT = 1_000_000.0;

    private FlowingFluidErosionGameTests() {}

    private static BlockState flowingWater() {
        return flowingWater(3);
    }

    private static BlockState flowingWater(int amount) {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, amount);
    }

    /**
     * erosionEnabled defaults to false (existing worlds are safe by default); LayerErosion.erode
     * reports DISABLED rather than making a decision while it's off. These acceptance tests
     * exercise the decision logic itself, so each one forces it on for its own duration and
     * restores the previous value afterward via try/finally -- never left changed for a later
     * test in the same batch.
     */
    private static void withErosionEnabled(Runnable body) {
        boolean previous = FlowingFluidsConfig.EROSION_ENABLED.get();
        FlowingFluidsConfig.EROSION_ENABLED.set(true);
        try {
            body.run();
        } finally {
            FlowingFluidsConfig.EROSION_ENABLED.set(previous);
        }
    }

    /** Temporarily overrides {@code chanceScale}, restoring the previous value via try/finally. */
    private static void withChanceScale(double scale, Runnable body) {
        double previous = FlowingFluidsConfig.CHANCE_SCALE.get();
        FlowingFluidsConfig.CHANCE_SCALE.set(scale);
        try {
            body.run();
        } finally {
            FlowingFluidsConfig.CHANCE_SCALE.set(previous);
        }
    }

    @GameTest(template = "flat_platform")
    public static void flowingWaterBesideAFullBlockConvertsItToFifteenLayers(GameTestHelper helper) {
        withErosionEnabled(() -> {
            helper.setBlock(POS, Blocks.STONE.defaultBlockState());
            BlockPos contact = POS.relative(Direction.EAST);
            helper.setBlock(contact, flowingWater());
            // An explicit empty neighbour makes this contact cell genuinely advancing (isAdvancing),
            // not just "non-source" -- deterministic regardless of the template's own default terrain.
            helper.setBlock(contact.relative(Direction.EAST), Blocks.AIR.defaultBlockState());

            double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
            helper.assertTrue(amount > 0, "flowing water beside the block must report a positive erosion amount");

            var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), GUARANTEED_AMOUNT, helper.getLevel().getRandom());
            helper.assertTrue(result == LayerErosion.Result.ERODED, "expected ERODED, got " + result);

            BlockState after = helper.getBlockState(POS);
            helper.assertTrue(after.getBlock() == LayeredSlopesMod.LAYERED_STONE.get(),
                    "a full stone block's first erosion hit must convert it to layered stone");
            helper.assertTrue(after.getValue(LayeredBlock.LAYERS) == 15,
                    "first conversion must leave 15 layers, got " + after.getValue(LayeredBlock.LAYERS));
        });
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void flowingWaterOverTheLastLayerErodesItToAir(GameTestHelper helper) {
        withErosionEnabled(() -> {
            helper.setBlock(POS, LayeredSlopesMod.LAYERED_STONE.get().defaultBlockState().setValue(LayeredBlock.LAYERS, 1));
            BlockPos contact = POS.above();
            helper.setBlock(contact, flowingWater());
            // An explicit empty neighbour makes this contact cell genuinely advancing (isAdvancing),
            // not just "non-source" -- deterministic regardless of the template's own default terrain.
            helper.setBlock(contact.relative(Direction.EAST), Blocks.AIR.defaultBlockState());

            double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
            helper.assertTrue(amount > 0, "flowing water over the block must report a positive erosion amount");

            var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), GUARANTEED_AMOUNT, helper.getLevel().getRandom());
            helper.assertTrue(result == LayerErosion.Result.ERODED, "expected ERODED, got " + result);
            helper.assertTrue(helper.getBlockState(POS).isAir(), "the final layer must erode away to air");
        });
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

    /**
     * MINECRAFT-513 self-review 53031/53033: a flowing-type (non-source) contact cell that has
     * already equalized -- every neighbour is either solid or same-fluid at an amount it cannot
     * flow into -- must report no erosion amount, exactly like a source does, distinguishing a
     * stationary pool from a genuinely advancing edge. This matters for both vanilla (a settled
     * lake-shore edge) and the optional Flowing Fluids mod (which can deliberately let a
     * partial-amount cell settle into exactly such a stationary, non-advancing pool).
     */
    @GameTest(template = "flat_platform")
    public static void stationarySettledPartialWaterReportsNoErosionAmount(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        BlockPos contact = POS.relative(Direction.EAST);
        helper.setBlock(contact, flowingWater(4));
        // Surround the contact cell on every other side (and below) with solid stone: no empty
        // neighbour, no lower-amount same-fluid neighbour -- isAdvancing must be false.
        helper.setBlock(contact.relative(Direction.EAST), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.relative(Direction.NORTH), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.relative(Direction.SOUTH), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.below(), Blocks.STONE.defaultBlockState());

        double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
        helper.assertTrue(amount == 0, "a fully equalized, non-advancing flowing-type pool must never report an erosion amount, got " + amount);
        helper.assertTrue(helper.getBlockState(POS).is(Blocks.STONE), "the block must stay unchanged next to a settled pool");
        helper.succeed();
    }

    /**
     * The same equalized surroundings as {@link #stationarySettledPartialWaterReportsNoErosionAmount},
     * except the contact cell's downward neighbour is open -- it is still genuinely advancing
     * (could still flow down into that gap) and must erode normally.
     */
    @GameTest(template = "flat_platform")
    public static void anAdvancingPartialWaterReportsAPositiveErosionAmount(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        BlockPos contact = POS.relative(Direction.EAST);
        helper.setBlock(contact, flowingWater(4));
        helper.setBlock(contact.relative(Direction.EAST), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.relative(Direction.NORTH), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.relative(Direction.SOUTH), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.below(), Blocks.AIR.defaultBlockState());

        double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
        helper.assertTrue(amount > 0, "a flowing-type cell with an open neighbour to fall into must report a positive erosion amount, got " + amount);
        helper.succeed();
    }

    /**
     * A full (amount 8), non-source cell is a falling waterfall column: it must always be treated
     * as advancing, with no neighbour check, matching the ticket's explicit "moving full cells"
     * erosion requirement.
     */
    @GameTest(template = "flat_platform")
    public static void aFullNonSourceFallingColumnReportsTheMaximumErosionAmount(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        BlockPos contact = POS.relative(Direction.EAST);
        // LiquidBlock.LEVEL=8 is specifically the falling-column cache entry (fluid.getFlowing(8, true):
        // amount=8, falling=true, isSource()=false) -- confirmed by decompiling vanilla's own
        // LiquidBlock constructor, which builds its 9-entry stateCache exactly this way.
        helper.setBlock(contact, flowingWater(8));
        // Fully enclosed: a settled/equalized cell would report 0, but a full non-source cell must
        // report the maximum amount regardless, since it is always treated as advancing.
        helper.setBlock(contact.relative(Direction.EAST), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.relative(Direction.NORTH), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.relative(Direction.SOUTH), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.below(), Blocks.STONE.defaultBlockState());

        double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
        helper.assertTrue(amount == FluidMath.FLOWING,
                "a full non-source falling column must report the maximum erosion amount regardless of neighbours, got " + amount);
        helper.succeed();
    }

    /**
     * MINECRAFT-513 self-review 53036: a synthetic, hand-constructed state does not prove the
     * decision logic reacts correctly to a REAL fluid-tick transfer. This places a genuine water
     * source on open ground and lets the server's own scheduled fluid ticks run (the real vanilla
     * engine, or the real Flowing Fluids engine's {@code ff$tickMixin} replacement when that mod
     * is coinstalled -- this test makes no assumption about which) for 30 real ticks, then queries
     * the actual production decision against whatever state that real simulation produced next to
     * the target. {@code fluid_flow_platform} (9x9 open floor) gives the spread room to still be
     * genuinely advancing past the target at that point rather than having already filled and
     * equalized across the whole bounded area (which {@code flat_platform}'s 3x3 footprint would
     * risk doing almost immediately).
     */
    @GameTest(template = "fluid_flow_platform", timeoutTicks = 200)
    public static void realFlowingWaterAdvancesAndErodesWhatItTouches(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 1, 1);
        BlockPos target = new BlockPos(4, 1, 1);
        helper.setBlock(source, Blocks.WATER.defaultBlockState());
        helper.setBlock(target, Blocks.STONE.defaultBlockState());

        helper.runAfterDelay(30, () -> {
            double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(target));
            helper.assertTrue(amount > 0,
                    "real fluid-tick physics spreading from a live source across open ground must report a positive erosion amount next to the target, got " + amount);
            helper.succeed();
        });
    }

    /**
     * MINECRAFT-513 self-review 52887: two players with overlapping sample radii must not
     * double-attempt the exact same dimension+position in the same second. This exercises the
     * real production dedup primitive ({@code markVisited}) directly and deterministically --
     * going through full {@code sampleAround} with real randomness would make "did the second
     * player's roll land on the same cell" itself nondeterministic, which is exactly what this
     * dedup exists to make irrelevant: whichever position is picked, a repeat is always skipped.
     */
    @GameTest(template = "flat_platform")
    public static void aPositionAlreadyVisitedThisSecondIsNotAttemptedAgain(GameTestHelper helper) {
        Set<GlobalPos> visited = new HashSet<>();
        BlockPos absolute = helper.absolutePos(POS);

        helper.assertTrue(FlowingFluidErosion.markVisited(visited, helper.getLevel(), absolute),
                "the first player to sample this position this second must record it as visited");
        helper.assertTrue(!FlowingFluidErosion.markVisited(visited, helper.getLevel(), absolute),
                "a second player sampling the SAME position in the SAME second must be skipped, not re-rolled");
        helper.assertTrue(FlowingFluidErosion.markVisited(visited, helper.getLevel(), absolute.relative(Direction.EAST)),
                "a genuinely different position must still be allowed");
        helper.succeed();
    }

    /**
     * MINECRAFT-513 self-review 52905: the ticket requires "configurable bounded chance/cadence"
     * -- samplesPerSecond alone only configures cadence. chanceScale=0.0 must disable erosion
     * even for an attempt that would otherwise be guaranteed to succeed (GUARANTEED_AMOUNT makes
     * the underlying curve's own chance exactly 1.0), proving the scale is actually applied, not
     * just present as an unused config key.
     */
    @GameTest(template = "flat_platform")
    public static void chanceScaleZeroDisablesAnOtherwiseGuaranteedAttempt(GameTestHelper helper) {
        withErosionEnabled(() -> withChanceScale(0.0, () -> {
            helper.setBlock(POS, Blocks.STONE.defaultBlockState());

            var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), GUARANTEED_AMOUNT, helper.getLevel().getRandom());
            helper.assertTrue(result == LayerErosion.Result.RESISTED,
                    "chanceScale=0.0 must force RESISTED even at an amount the curve alone would make certain, got " + result);
            helper.assertTrue(helper.getBlockState(POS).is(Blocks.STONE), "a chanceScale-disabled attempt must not change the block");
        }));
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void aResistedRollLeavesTheBlockUnchanged(GameTestHelper helper) {
        withErosionEnabled(() -> {
            helper.setBlock(POS, Blocks.STONE.defaultBlockState());

            // amount=0 forces successChance to exactly 0.0, and nextDouble() is always >= 0.0.
            var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), 0.0, helper.getLevel().getRandom());
            helper.assertTrue(result == LayerErosion.Result.RESISTED, "expected RESISTED, got " + result);
            helper.assertTrue(helper.getBlockState(POS).is(Blocks.STONE), "a resisted roll must not change the block");
        });
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void aCancelledProtectionHookLeavesTheBlockUnchanged(GameTestHelper helper) {
        withErosionEnabled(() -> {
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
        });
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void erodingAFullGrassBlockSettlesItAsLayeredDirtNotLayeredGrass(GameTestHelper helper) {
        withErosionEnabled(() -> {
            helper.setBlock(POS, Blocks.GRASS_BLOCK.defaultBlockState());

            var result = LayerErosion.erode(helper.getLevel(), helper.absolutePos(POS), GUARANTEED_AMOUNT, helper.getLevel().getRandom());
            helper.assertTrue(result == LayerErosion.Result.ERODED, "expected ERODED, got " + result);

            BlockState after = helper.getBlockState(POS);
            helper.assertTrue(after.getBlock() == LayeredSlopesMod.LAYERED_DIRT.get(),
                    "erosion must disturb grass into dirt, matching Dynamic Terrain's established semantic, got " + after.getBlock());
            helper.assertTrue(after.getValue(LayeredBlock.LAYERS) == 15, "first conversion must leave 15 layers");
        });
        helper.succeed();
    }
}
