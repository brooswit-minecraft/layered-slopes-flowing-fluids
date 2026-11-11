package io.github.brooswitminecraft.layeredslopesflowingfluids;

import io.github.brooswitminecraft.layeredslopes.LayeredBlock;
import io.github.brooswitminecraft.layeredslopes.LayeredSlopesMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
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
        BlockPos contact = POS.relative(Direction.EAST);
        helper.setBlock(contact, Blocks.WATER.defaultBlockState()); // source: LEVEL defaults to 0
        // This test's OWN placement above is itself a genuine change RecentFlowActivity would
        // otherwise (correctly) treat as "just happened" -- age it out so this test establishes a
        // deliberately long-standing/static baseline rather than a just-placed one.
        RecentFlowActivity.forgetForTesting(helper.getLevel(), helper.absolutePos(contact));

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
        // Age out this fixture's own placement (see stationarySourceWaterReportsNoErosionAmount).
        RecentFlowActivity.forgetForTesting(helper.getLevel(), helper.absolutePos(contact));

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
     * MINECRAFT-513 self-review 53049/53050/53051: an amount/source-ness change alone is not
     * evidence of WHO changed it -- a manual {@code helper.setBlock} (a GameTest fixture, a
     * player, another mod) must never be mistaken for Flowing Fluids' own real transfer, even
     * when Flowing Fluids is loaded and governing this fluid. {@link RecentFlowActivity} only
     * ever records a position while {@link FlowingFluidsCompat#isCurrentlyTicking()} is true,
     * and a manual placement never runs during that window, so this must read as the ordinary
     * static-source no-op in every configuration this suite runs under.
     */
    @GameTest(template = "flat_platform", timeoutTicks = 100)
    public static void aManualSourceCellChangeNeverErodesRegardlessOfFlowingFluids(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        BlockPos contact = POS.relative(Direction.EAST);
        helper.setBlock(contact, flowingWater(4));
        // A manual amount/source-ness change at the SAME position (4 -> source) -- exactly the
        // kind of edit a careless "did it change" heuristic could mistake for a real transfer.
        helper.setBlock(contact, Blocks.WATER.defaultBlockState());

        double justChanged = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
        helper.assertTrue(justChanged == 0,
                "a manual amount change must never erode a source cell, with or without Flowing Fluids loaded, got " + justChanged);
        helper.succeed();
    }

    /**
     * MINECRAFT-513 self-review 53041: a {@code BlockEvent.NeighborNotifyEvent} at one position
     * must never make an unrelated position read as "recently changed" -- RecentFlowActivity is
     * keyed per-position, not a single global flag.
     */
    @GameTest(template = "flat_platform")
    public static void anUnrelatedNeighborNotificationDoesNotMarkAnotherPositionAsRecentlyChanged(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        BlockPos contact = POS.relative(Direction.EAST);
        helper.setBlock(contact, flowingWater(4));
        // Surround it as a fully settled, non-advancing pool -- only RecentFlowActivity could
        // make this erosive, so an unrelated notification elsewhere must never do so.
        helper.setBlock(contact.relative(Direction.EAST), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.relative(Direction.NORTH), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.relative(Direction.SOUTH), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact.below(), Blocks.STONE.defaultBlockState());
        RecentFlowActivity.forgetForTesting(helper.getLevel(), helper.absolutePos(contact));

        // An unrelated position (POS.below(): never read by strongestFlowAround, which skips
        // DOWN) gets its own unrelated edit.
        BlockPos unrelated = POS.below();
        helper.setBlock(unrelated, flowingWater(4));
        helper.setBlock(unrelated, Blocks.WATER.defaultBlockState());

        double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(POS));
        helper.assertTrue(amount == 0,
                "activity at an unrelated position must not make this settled pool erosive, got " + amount);
        helper.succeed();
    }

    /**
     * MINECRAFT-513 self-review 53036: a synthetic, hand-constructed state does not prove the
     * decision logic reacts correctly to a REAL fluid-tick transfer. This places a genuine water
     * source on open ground and lets the server's own scheduled fluid ticks run (the real
     * vanilla engine, or the real Flowing Fluids engine's mixin replacement when that mod is
     * coinstalled -- this test makes no assumption about which) before querying the actual
     * production decision against whatever state that real simulation produced next to the
     * target. {@code strongestFlowAround} never reports anything for a static source's OWN
     * position (a never-fed, never-draining source is correctly a no-op, like any other still
     * source), so the target must be the cell the water actually SPREADS INTO, one past the
     * source -- confirmed locally (run repeatedly) that leaving every other direction open lets
     * Flowing Fluids' finite simulation sometimes pick a different spread direction first, or
     * spread too thinly to ever reach that one cell within the timeout, in an essentially random
     * order unrelated to whether the production decision reacting to real physics works.
     * Confirmed locally, in the other direction, that fully dead-ending the channel (no open or
     * lower-amount neighbour left at all once it fills) makes vanilla correctly settle it as a
     * non-advancing pool -- exactly {@code isAdvancing}'s intended "settled, like a still source"
     * case, not a bug, but then this test would need Flowing Fluids' {@code RecentFlowActivity}
     * override to pass under vanilla too, which defeats the point of testing real vanilla
     * physics. The channel keeps one side permanently open (never dead-ends) so it stays
     * genuinely advancing under either engine the instant real physics gets any water into it at
     * all -- removing both sources of non-determinism without relying on which engine is active.
     */
    @GameTest(template = "fluid_flow_platform", timeoutTicks = 200)
    public static void realFlowingWaterAdvancesAndErodesWhatItTouches(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos channel = new BlockPos(2, 2, 1);
        BlockPos source = new BlockPos(3, 2, 1);
        helper.setBlock(pos, Blocks.STONE.defaultBlockState());
        // Solid ground support under both cells (not the template's own default terrain --
        // confirmed locally that water without it simply falls straight through and drains
        // away), and wall off the source's other directions so it can only reach the channel.
        // The channel's own north side stays open (air): the one deliberate escape that keeps it
        // genuinely advancing, by definition, as soon as any real water reaches it at all.
        helper.setBlock(channel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(channel.south(), Blocks.STONE.defaultBlockState());
        helper.setBlock(channel.above(), Blocks.STONE.defaultBlockState());
        helper.setBlock(source.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(source.north(), Blocks.STONE.defaultBlockState());
        helper.setBlock(source.south(), Blocks.STONE.defaultBlockState());
        helper.setBlock(source.above(), Blocks.STONE.defaultBlockState());
        helper.setBlock(source.east(), Blocks.STONE.defaultBlockState());
        helper.setBlock(source, Blocks.WATER.defaultBlockState());

        helper.succeedWhen(() -> {
            double amount = FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(pos));
            helper.assertTrue(amount > 0,
                    "real fluid-tick physics from a live source, with only one possible direction to spread, "
                            + "must at some point report a positive erosion amount next to the target within the "
                            + "timeout, got " + amount
                            + " (channel fluid state: " + helper.getLevel().getFluidState(helper.absolutePos(channel)) + ")");
        });
    }

    /**
     * MINECRAFT-513 self-review 53049/53050/53051/53074/53081: proves a real, physics-driven
     * fill to a FULL cell at the exact fed position, attributed specifically to {@link
     * RecentFlowActivity} (not merely a nonzero aggregate reading a partial cell could also
     * satisfy), erodes -- with NO manual edit of the fed position itself after the feed starts,
     * the gap {@link #aManualSourceCellChangeNeverErodesRegardlessOfFlowingFluids} deliberately
     * does not close. A single source feeds straight down into a cell enclosed on every other
     * side. Review 53081 found phase 1 accepted either outcome without asserting which engine
     * SHOULD be active, so a real Flowing Fluids integration failure under the coinstalled job
     * could silently pass via the vanilla fallback alternative; the expected mode is now decided
     * upfront from {@link FlowingFluidsCompat#isGoverning} and phase 1 REQUIRES that exact
     * outcome (the real full source verified by RecentFlowActivity when Flowing Fluids is
     * governing water; vanilla's always-advancing full non-source falling column only as a
     * fallback) rather than accepting whichever happens to show up. Removing the feed and
     * waiting past the recent window proves the fluid is RETAINED, stationary, with erosion
     * genuinely stopped -- not merely drained away, which {@code amount == 0} alone could not
     * tell apart -- confirmed locally under both engines.
     */
    @GameTest(template = "fluid_flow_platform", timeoutTicks = 200)
    public static void aRealFlowingFluidsDrivenFullCellErodesThenSettlesToNoOp(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 3, 1);
        BlockPos fed = new BlockPos(1, 2, 1);
        BlockPos contact = new BlockPos(2, 2, 1);
        // Enclose "fed" on every side except above (where the source feeds it) and the one
        // contact face -- confining it into a bucket that must fill toward full rather than
        // draining laterally, without ever touching "fed" itself with a manual edit.
        helper.setBlock(fed.north(), Blocks.STONE.defaultBlockState());
        helper.setBlock(fed.south(), Blocks.STONE.defaultBlockState());
        helper.setBlock(fed.west(), Blocks.STONE.defaultBlockState());
        helper.setBlock(contact, Blocks.STONE.defaultBlockState());
        helper.setBlock(source, Blocks.WATER.defaultBlockState());
        boolean flowingFluidsShouldGovern = FlowingFluidsCompat.isGoverning(Fluids.WATER.defaultFluidState());

        // Two phases, so this cannot use succeedWhen (which succeeds the instant its criterion
        // stops throwing): poll every tick for the real fill to erode, then switch to proving it
        // settles once the feed is gone. A guard flag keeps the phase-1 side effect (removing the
        // source) from repeating on every subsequent tick once it has already fired.
        var feedRemoved = new boolean[1];
        helper.onEachTick(() -> {
            if (feedRemoved[0]) {
                return;
            }
            var level = (ServerLevel) helper.getLevel();
            var absoluteFed = helper.absolutePos(fed);
            var fedFluid = level.getFluidState(absoluteFed);
            boolean required = flowingFluidsShouldGovern
                    ? fedFluid.getAmount() >= FluidMath.FULL_FLUID && fedFluid.isSource()
                            && RecentFlowActivity.recentlyChanged(level, absoluteFed)
                    : fedFluid.getAmount() >= FluidMath.FULL_FLUID && !fedFluid.isSource();
            double amount = FlowingFluidErosion.strongestFlowAround(level, helper.absolutePos(contact));
            if (amount <= 0 || !required) {
                return;
            }
            feedRemoved[0] = true;
            helper.setBlock(source, Blocks.AIR.defaultBlockState());
            helper.runAfterDelay(60, () -> {
                var afterFluid = level.getFluidState(absoluteFed);
                if (flowingFluidsShouldGovern) {
                    // A genuine Flowing Fluids source is self-sustaining once created: it must
                    // stay right there, stationary, with erosion genuinely stopped -- not merely
                    // drained away, which amount == 0 alone could not tell apart.
                    helper.assertTrue(!afterFluid.isEmpty(),
                            "60 ticks after the feed is removed, a real Flowing Fluids source must retain its "
                                    + "stationary fluid rather than drain away, got " + afterFluid);
                } else {
                    // Vanilla's own non-source falling column is NOT self-sustaining: with
                    // nothing feeding it any more, draining away to empty is its own correct,
                    // unforced "settled" outcome here, not a sign the check below is vacuous --
                    // confirmed locally that it reliably does drain within this window.
                    helper.assertTrue(afterFluid.isEmpty(),
                            "60 ticks after the feed is removed, vanilla's unfed falling column must have drained "
                                    + "away, got " + afterFluid);
                }
                helper.assertTrue(!RecentFlowActivity.recentlyChanged(level, absoluteFed),
                        "60 ticks after the feed is removed, with no further real physics touching it, this "
                                + "position must no longer verify as recently changed");
                double afterSettling = FlowingFluidErosion.strongestFlowAround(level, helper.absolutePos(contact));
                helper.assertTrue(afterSettling == 0,
                        "60 ticks after the feed is removed, this must settle to the ordinary no-op, got " + afterSettling
                                + " (fed cell fluid state: " + afterFluid + ")");
                helper.succeed();
            });
        });
        helper.runAfterDelay(100, () -> {
            if (!feedRemoved[0]) {
                helper.fail("a real physics-driven fill of an enclosed cell to full must verify and erode "
                        + "within 100 ticks, got amount=" + FlowingFluidErosion.strongestFlowAround(helper.getLevel(), helper.absolutePos(contact))
                        + " (fed cell fluid state: " + helper.getLevel().getFluidState(helper.absolutePos(fed)) + ")");
            }
        });
    }

    /**
     * MINECRAFT-513 self-review 53081: a disabled Flowing Fluids mod ({@code config.enableMod}
     * false) means its own {@code ff$tickMixin} never cancels vanilla's tick (the {@code
     * ci.cancel()} is conditional on exactly that flag, confirmed by decompiling the pinned jar),
     * so vanilla's own fluid logic must govern instead -- {@link FlowingFluidsCompat#isGoverning}
     * must read false, the one signal {@link RecentFlowActivity} itself gates on, so a real
     * multi-tick physics fixture is not needed here (and toggling this GLOBAL config for several
     * real ticks would risk interfering with any OTHER test's own Flowing Fluids physics running
     * concurrently in the same batch -- this check is synchronous and toggles back before any
     * other tick runs). A no-op pass-through whenever Flowing Fluids is not loaded at all, where
     * {@code isGoverning} already reads false unconditionally.
     */
    @GameTest(template = "flat_platform")
    public static void aDisabledFlowingFluidsNeverGovernsWater(GameTestHelper helper) {
        FlowingFluidsCompat.withModDisabledForTesting(() ->
                helper.assertTrue(!FlowingFluidsCompat.isGoverning(Fluids.WATER.defaultFluidState()),
                        "a disabled Flowing Fluids must never report governing water"));
        helper.succeed();
    }

    /**
     * MINECRAFT-513 self-review 53081: the same fallback signal, but via Flowing Fluids' OTHER
     * governance knob -- water added to its own fluid blacklist ({@code isFluidAllowed} false
     * for water specifically) rather than the mod disabled outright.
     */
    @GameTest(template = "flat_platform")
    public static void anIgnoredFluidIsNeverGoverned(GameTestHelper helper) {
        FlowingFluidsCompat.withWaterIgnoredForTesting(() ->
                helper.assertTrue(!FlowingFluidsCompat.isGoverning(Fluids.WATER.defaultFluidState()),
                        "an ignored fluid must never report as governed by Flowing Fluids"));
        helper.succeed();
    }

    /**
     * MINECRAFT-513 self-review 53074: a manual replacement of a position's fluid must discard
     * any existing RecentFlowActivity record for it immediately, not merely let it age out over
     * the rest of the window. Lets a real Flowing Fluids transfer verify the confined fed
     * position first, then manually replaces it and asserts recentlyChanged reads false on the
     * very next check -- a no-op (trivially succeeds) whenever Flowing Fluids is not governing
     * water, since nothing is ever recorded there to regress against in the first place.
     */
    @GameTest(template = "fluid_flow_platform", timeoutTicks = 200)
    public static void aManualReplaceAfterARealTransferImmediatelyClearsTheStaleRecord(GameTestHelper helper) {
        if (!FlowingFluidsCompat.isGoverning(Fluids.WATER.defaultFluidState())) {
            helper.succeed();
            return;
        }
        BlockPos source = new BlockPos(1, 3, 1);
        BlockPos fed = new BlockPos(1, 2, 1);
        helper.setBlock(fed.north(), Blocks.STONE.defaultBlockState());
        helper.setBlock(fed.south(), Blocks.STONE.defaultBlockState());
        helper.setBlock(fed.west(), Blocks.STONE.defaultBlockState());
        helper.setBlock(fed.east(), Blocks.STONE.defaultBlockState());
        helper.setBlock(source, Blocks.WATER.defaultBlockState());
        var level = (ServerLevel) helper.getLevel();
        var absoluteFed = helper.absolutePos(fed);

        helper.succeedWhen(() -> {
            helper.assertTrue(RecentFlowActivity.recentlyChanged(level, absoluteFed),
                    "a real Flowing Fluids transfer must verify this position before this regression can run");
            helper.setBlock(fed, Blocks.STONE.defaultBlockState());
            helper.assertTrue(!RecentFlowActivity.recentlyChanged(level, absoluteFed),
                    "a manual replacement must immediately discard the stale record, not merely let it age out");
        });
    }

    /**
     * MINECRAFT-513 self-review 53094: Flowing Fluids' own {@code isManeuveringFluids} flag is
     * NOT set during its periodic random-leveling smoothing tick (confirmed by decompiling the
     * pinned jar's bytecode -- two real {@code setFluidStateAtPosToNewAmount} calls, no flag
     * access anywhere in that method), active by default. {@code LevelingMixin} closes that gap
     * at the one low-level setter every Flowing Fluids transfer path ultimately calls, instead of
     * that flag. {@code FluidState.randomTick} is called directly (a genuine public vanilla
     * method, not a synthetic shortcut) rather than waiting on vanilla's own probabilistic
     * per-chunk random-tick scheduler, which would make this nondeterministic; the direction
     * Flowing Fluids' own leveling scan picks is still genuinely random, so this retries up to a
     * generous bound -- with every other direction walled off, only the one real transfer this
     * test cares about can ever succeed, so a retry is exactly as deterministic as the single-shot
     * fixtures elsewhere in this class. A no-op pass-through whenever Flowing Fluids is not
     * governing water, where vanilla's own {@code randomTick} has no leveling behavior to trigger
     * at all.
     */
    @GameTest(template = "fluid_flow_platform")
    public static void aRandomLevelingTransferIsVerifiedByRecentFlowActivity(GameTestHelper helper) {
        if (!FlowingFluidsCompat.isGoverning(Fluids.WATER.defaultFluidState())) {
            helper.succeed();
            return;
        }
        BlockPos high = new BlockPos(1, 2, 1);
        BlockPos low = new BlockPos(2, 2, 1);
        // Wall off every direction except the one straight line from "high" to "low": the only
        // path Flowing Fluids' own random-direction leveling scan can ever actually find water
        // through, regardless of which direction it happens to pick this attempt.
        helper.setBlock(high.north(), Blocks.STONE.defaultBlockState());
        helper.setBlock(high.south(), Blocks.STONE.defaultBlockState());
        helper.setBlock(high.above(), Blocks.STONE.defaultBlockState());
        helper.setBlock(low.north(), Blocks.STONE.defaultBlockState());
        helper.setBlock(low.south(), Blocks.STONE.defaultBlockState());
        helper.setBlock(low.above(), Blocks.STONE.defaultBlockState());
        helper.setBlock(low.east(), Blocks.STONE.defaultBlockState());
        helper.setBlock(high, flowingWater(2)); // amount 6
        helper.setBlock(low, flowingWater(6)); // amount 2
        var level = (ServerLevel) helper.getLevel();
        var absoluteHigh = helper.absolutePos(high);
        var absoluteLow = helper.absolutePos(low);
        int lowAmountBefore = level.getFluidState(absoluteLow).getAmount();

        for (int attempt = 0; attempt < 500; attempt++) {
            level.getFluidState(absoluteHigh).randomTick(level, absoluteHigh, level.getRandom());
            if (level.getFluidState(absoluteLow).getAmount() != lowAmountBefore) {
                helper.assertTrue(RecentFlowActivity.recentlyChanged(level, absoluteLow),
                        "a real Flowing Fluids random-leveling transfer must be verified by RecentFlowActivity, "
                                + "got low=" + level.getFluidState(absoluteLow));
                // A genuine leveling transfer calls the wrapped setter twice (once for each
                // cell) within this single randomTick invocation -- proving LevelingMixin's
                // counter actually settles back to its resting value across that real,
                // sequential multi-call pattern, not just a single isolated call.
                helper.assertTrue(FlowingFluidsCompat.levelingDepth == 0,
                        "levelingDepth must return to 0 after a real leveling transfer's two sequential setter "
                                + "calls complete, got " + FlowingFluidsCompat.levelingDepth);
                helper.succeed();
                return;
            }
        }
        helper.fail("a random-leveling transfer between these two cells never happened in 500 attempts -- "
                + "either Flowing Fluids' own leveling precondition is not met here, or something about this "
                + "fixture needs revisiting");
    }

    /**
     * MINECRAFT-513 self-review 53101/53103: {@code LevelingMixin} now wraps the original setter
     * call in a genuine {@code try/finally} ({@code @WrapMethod}), exception-safe by construction
     * -- a {@code finally} block is a JLS guarantee to run on exceptional exit too, not something
     * that needs its own empirical regression the way the REJECTED {@code HEAD}/{@code RETURN}
     * pair's gap did (confirmed broken by the exact test this one replaces: that version proved
     * the misread, this one no longer can, because the underlying bug it targeted is gone).
     * {@link #aRandomLevelingTransferIsVerifiedByRecentFlowActivity} is the regression that
     * matters now -- it already proves {@code levelingDepth} returns to its resting value after
     * a REAL leveling transfer's two sequential setter calls, the actual call pattern Flowing
     * Fluids produces. This test keeps only the DEFENSE-IN-DEPTH layer under that primary fix:
     * {@code FlowingFluidsCompat.onServerTickStart} forcibly resetting the counter every tick
     * regardless, confirmed to still actually clear a separately-induced stuck value by the very
     * next tick, with a manual edit made after that reset correctly reading as negative again.
     */
    @GameTest(template = "flat_platform")
    public static void aLeakedLevelingDepthIsBoundedByThePerTickSafetyNet(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.STONE.defaultBlockState());
        BlockPos contact = POS.relative(Direction.EAST);
        var level = (ServerLevel) helper.getLevel();
        var absoluteContact = helper.absolutePos(contact);

        // Phase 1: confirms the counter genuinely gates verification at all (a positive value --
        // however it got there -- really does make a manual edit misread as verified), so the
        // per-tick reset below is proven to be clearing a signal that actually matters, not a
        // field nothing reads.
        helper.setBlock(contact, flowingWater(4));
        FlowingFluidsCompat.levelingDepth = 1;
        helper.setBlock(contact, Blocks.WATER.defaultBlockState());
        boolean misreadWhileStuck = RecentFlowActivity.recentlyChanged(level, absoluteContact);
        FlowingFluidsCompat.levelingDepth = 0;
        if (FlowingFluidsCompat.isGoverning(Fluids.WATER.defaultFluidState())) {
            helper.assertTrue(misreadWhileStuck,
                    "a positive levelingDepth must make a manual edit misread as verified, confirming the "
                            + "signal the per-tick reset below is checked against actually matters");
        }
        RecentFlowActivity.forgetForTesting(level, absoluteContact);

        // Phase 2: a separately-induced stuck value, left for the per-tick safety net to find
        // rather than cleared immediately, must actually be cleared by the very next tick.
        FlowingFluidsCompat.levelingDepth = 1;
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(FlowingFluidsCompat.levelingDepth == 0,
                    "a leaked positive levelingDepth must reset to 0 at the start of the next tick, got "
                            + FlowingFluidsCompat.levelingDepth);
            helper.setBlock(contact, Blocks.STONE.defaultBlockState());
            helper.setBlock(contact, Blocks.WATER.defaultBlockState());
            double amount = FlowingFluidErosion.strongestFlowAround(level, helper.absolutePos(POS));
            helper.assertTrue(amount == 0,
                    "once the leaked counter is reset, a manual edit must read as negative again, got " + amount);
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

    /**
     * MINECRAFT-514: {@code LayerErosion.erode} is a public entrypoint any caller can reach
     * directly, not only through {@link FlowingFluidErosion#sampleAround} (which already checks
     * {@code isLoaded} before ever calling in). {@code level.getBlockState(pos)} on an unloaded
     * position forces a synchronous chunk load/generation -- this proves the guard added to
     * {@code erode} itself actually fires FIRST, before any block/fluid state read: a position
     * far outside this GameTest's own loaded structure must read UNLOADED and must still read
     * unloaded afterward, proving nothing forced it to load.
     */
    @GameTest(template = "flat_platform")
    public static void erodeOnAnUnloadedPositionReportsUnloadedWithoutForcingAChunkLoad(GameTestHelper helper) {
        withErosionEnabled(() -> {
            BlockPos farAway = helper.absolutePos(POS).offset(10_000, 0, 10_000);
            helper.assertTrue(!helper.getLevel().isLoaded(farAway),
                    "this test requires a position genuinely outside this GameTest's own loaded structure");

            var result = LayerErosion.erode(helper.getLevel(), farAway, GUARANTEED_AMOUNT, helper.getLevel().getRandom());
            helper.assertTrue(result == LayerErosion.Result.UNLOADED, "expected UNLOADED, got " + result);

            helper.assertTrue(!helper.getLevel().isLoaded(farAway),
                    "erode() must never force the unloaded position it just refused to act on to load/generate");
        });
        helper.succeed();
    }

    /**
     * MINECRAFT-514: the same "no forced chunk creation" safety, but through the OTHER public
     * entrypoint -- {@link FlowingFluidErosion#sampleAround} -- called with a center far outside
     * this GameTest's own loaded structure. Every sampled position within {@code RADIUS}/{@code
     * HEIGHT} of that center must be skipped via the existing {@code isLoaded} guard rather than
     * read or force-loaded; zero erosions and the area still unloaded afterward is the proof.
     */
    @GameTest(template = "flat_platform")
    public static void sampleAroundACenterFarOutsideLoadedTerrainNeverForcesAChunkLoad(GameTestHelper helper) {
        withErosionEnabled(() -> {
            BlockPos farCenter = helper.absolutePos(POS).offset(10_000, 0, 10_000);
            helper.assertTrue(!helper.getLevel().isLoaded(farCenter),
                    "this test requires a center genuinely outside this GameTest's own loaded structure");

            int changed = FlowingFluidErosion.sampleAround((ServerLevel) helper.getLevel(), farCenter, 64, helper.getLevel().getRandom());
            helper.assertTrue(changed == 0, "sampling around unloaded terrain must never report an erosion, got " + changed);

            helper.assertTrue(!helper.getLevel().isLoaded(farCenter),
                    "sampleAround must never force the unloaded area it just skipped to load/generate");
        });
        helper.succeed();
    }

    /**
     * MINECRAFT-514: proves the real wiring, not just the pure logic {@link
     * PerWorldLegacyMigrationTest} already covers end-to-end against plain temp directories --
     * {@code ServerAboutToStartEvent} fires before any GameTest method runs (confirmed by the
     * config-load log lines preceding "Preparing start region" in a real run), so by the time
     * this test executes, this exact GameTestServer's own world save must already have been
     * examined by the real listener reaching a real {@code MinecraftServer}'s own world save
     * path, which a Path-only unit test cannot provide. This GameTest fixture's own world has no
     * per-world {@code dynamicterrain-server.toml} (confirmed: only {@code readme.txt} exists
     * under {@code run/world/serverconfig} before any server start), so the one observable,
     * non-vacuous proof available in a single server lifecycle is the diagnostic line {@link
     * PerWorldLegacyMigration#apply} leaves on EVERY run (never read back to decide anything,
     * only written -- see its own doc) recording that exact "nothing to migrate" conclusion, plus
     * the two live config values it left genuinely alone, matching the real NeoForge-loaded
     * value for a world with no override file at all. A legacy-file-present path (the actually
     * interesting "did it apply the right values" case) is exercised directly below by {@link
     * #applyOnAFreshWorldWithAnExplicitOptOutSetsLiveConfigAndPersistsIt} and its siblings --
     * same production {@code apply} method, same {@code FlowingFluidsConfig} fields, just a
     * hand-created temp directory rather than this test's own real world save (so each variant
     * can set up its own fixture independently without racing the real, already-fired hook).
     */
    @GameTest(template = "flat_platform")
    public static void theServerAboutToStartMigrationHookActuallyRanForThisRealServer(GameTestHelper helper) {
        var level = (ServerLevel) helper.getLevel();
        Path worldServerConfigDir = level.getServer().getWorldPath(LevelResource.ROOT).resolve("serverconfig");
        String diagnostic;
        try {
            diagnostic = Files.readString(worldServerConfigDir.resolve(PerWorldLegacyMigration.DIAGNOSTIC_FILE_NAME));
        } catch (java.io.IOException e) {
            helper.fail("PerWorldLegacyMigration.onServerAboutToStart must have already run and left a diagnostic for this real server's own world save: " + e);
            return;
        }
        helper.assertTrue(diagnostic.contains("no-op: no per-world legacy file"),
                "this GameTest world has no per-world legacy file, so the real hook must have concluded there is nothing to migrate, got: " + diagnostic);
        helper.assertTrue(!PerWorldLegacyMigration.alreadyConfigured(worldServerConfigDir),
                "with nothing to migrate, the real hook must never have written a per-world override file");
        helper.succeed();
    }

    /**
     * Runs {@code body} against a freshly created, empty temp directory standing in for one
     * world's {@code serverconfig} directory, deleting it afterward regardless of outcome --
     * {@link PerWorldLegacyMigration#apply} needs a real {@code Path} to read/write, and a GameTest
     * has no {@code @TempDir} support of its own.
     */
    private static void withTempWorldServerConfigDir(java.util.function.Consumer<Path> body) {
        Path dir;
        try {
            dir = Files.createTempDirectory("mc514-serverconfig-");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        try {
            body.accept(dir);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // Best-effort cleanup of a throwaway temp directory.
                }
            });
        } catch (java.io.IOException ignored) {
            // Best-effort cleanup of a throwaway temp directory.
        }
    }

    /**
     * MINECRAFT-514 coordinator review (comment 53149): the actually interesting "did it apply
     * the right values" case, exercised through the real production {@code apply} method against
     * the real, live {@link FlowingFluidsConfig} fields -- not just {@code decide}'s pure return
     * value.
     */
    @GameTest(template = "flat_platform")
    public static void applyOnAFreshWorldWithAnExplicitOptOutSetsLiveConfigAndPersistsIt(GameTestHelper helper) {
        withTempWorldServerConfigDir(dir -> {
            boolean previousEnabled = FlowingFluidsConfig.EROSION_ENABLED.get();
            int previousSamples = FlowingFluidsConfig.SAMPLES_PER_SECOND.get();
            try {
                FlowingFluidsConfig.EROSION_ENABLED.set(true); // the opposite of what migration should produce
                FlowingFluidsConfig.SAMPLES_PER_SECOND.set(999);
                Files.writeString(dir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                        "erosionEnabled = false\nwaterSamplesPerSecond = 0\n");

                PerWorldLegacyMigration.apply(dir);

                helper.assertTrue(!FlowingFluidsConfig.EROSION_ENABLED.get(), "apply() must set the live value from this world's own legacy file");
                helper.assertTrue(FlowingFluidsConfig.SAMPLES_PER_SECOND.get() == 0,
                        "expected samplesPerSecond=0, got " + FlowingFluidsConfig.SAMPLES_PER_SECOND.get());
                helper.assertTrue(PerWorldLegacyMigration.alreadyConfigured(dir), "apply() must persist a per-world override file");
            } catch (java.io.IOException e) {
                helper.fail("fixture setup failed: " + e);
            } finally {
                FlowingFluidsConfig.EROSION_ENABLED.set(previousEnabled);
                FlowingFluidsConfig.SAMPLES_PER_SECOND.set(previousSamples);
            }
        });
        helper.succeed();
    }

    /**
     * MINECRAFT-514 coordinator review (comment 53149): the exact regression for the clobbering
     * bug the earlier marker-file design had -- an override file already exists (explicit admin
     * content, including a key this migration doesn't even know about) AND a conflicting legacy
     * file is present. {@code apply()} must leave the file byte-for-byte untouched and never call
     * {@code .set()} at all.
     */
    @GameTest(template = "flat_platform")
    public static void applyNeverTouchesAnAlreadyPresentOverrideFileOrItsLiveValuesEvenWithAConflictingLegacyFile(GameTestHelper helper) {
        withTempWorldServerConfigDir(dir -> {
            boolean previousEnabled = FlowingFluidsConfig.EROSION_ENABLED.get();
            int previousSamples = FlowingFluidsConfig.SAMPLES_PER_SECOND.get();
            String existingOverride = "erosionEnabled = true\nsamplesPerSecond = 300\nchanceScale = 0.5\n";
            try {
                Files.writeString(dir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME), existingOverride);
                Files.writeString(dir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                        "erosionEnabled = false\nwaterSamplesPerSecond = 0\n");
                FlowingFluidsConfig.EROSION_ENABLED.set(true);
                FlowingFluidsConfig.SAMPLES_PER_SECOND.set(300);

                PerWorldLegacyMigration.apply(dir);

                helper.assertTrue(FlowingFluidsConfig.EROSION_ENABLED.get(), "apply() must never overwrite live values once an override file already exists");
                helper.assertTrue(FlowingFluidsConfig.SAMPLES_PER_SECOND.get() == 300,
                        "expected samplesPerSecond=300, got " + FlowingFluidsConfig.SAMPLES_PER_SECOND.get());
                String afterApply = Files.readString(dir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME));
                helper.assertTrue(existingOverride.equals(afterApply),
                        "an existing override file must be preserved byte-for-byte, including unrelated keys, got: " + afterApply);
            } catch (java.io.IOException e) {
                helper.fail("fixture setup failed: " + e);
            } finally {
                FlowingFluidsConfig.EROSION_ENABLED.set(previousEnabled);
                FlowingFluidsConfig.SAMPLES_PER_SECOND.set(previousSamples);
            }
        });
        helper.succeed();
    }

    /** MINECRAFT-514: a repeat server start for the same world must never re-decide or re-write once configured. */
    @GameTest(template = "flat_platform")
    public static void applyIsIdempotentOnARepeatRunForTheSameWorld(GameTestHelper helper) {
        withTempWorldServerConfigDir(dir -> {
            boolean previousEnabled = FlowingFluidsConfig.EROSION_ENABLED.get();
            int previousSamples = FlowingFluidsConfig.SAMPLES_PER_SECOND.get();
            try {
                Files.writeString(dir.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                        "erosionEnabled = false\nwaterSamplesPerSecond = 0\n");

                PerWorldLegacyMigration.apply(dir);
                String afterFirstRun = Files.readString(dir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME));

                // A later server start could now find an admin-edited override; apply() must
                // never run its decision again, regardless -- this is the repeat-run guarantee.
                FlowingFluidsConfig.EROSION_ENABLED.set(true);
                FlowingFluidsConfig.SAMPLES_PER_SECOND.set(777);
                PerWorldLegacyMigration.apply(dir);

                helper.assertTrue(FlowingFluidsConfig.EROSION_ENABLED.get(), "a repeat run must not re-decide or touch live values a second time");
                helper.assertTrue(FlowingFluidsConfig.SAMPLES_PER_SECOND.get() == 777,
                        "expected samplesPerSecond=777 (untouched by the repeat run), got " + FlowingFluidsConfig.SAMPLES_PER_SECOND.get());
                helper.assertTrue(afterFirstRun.equals(Files.readString(dir.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME))),
                        "a repeat run must not rewrite the override file");
            } catch (java.io.IOException e) {
                helper.fail("fixture setup failed: " + e);
            } finally {
                FlowingFluidsConfig.EROSION_ENABLED.set(previousEnabled);
                FlowingFluidsConfig.SAMPLES_PER_SECOND.set(previousSamples);
            }
        });
        helper.succeed();
    }

    /** MINECRAFT-514: two distinct worlds' own override files must reflect only their own per-world legacy choice. */
    @GameTest(template = "flat_platform")
    public static void twoDistinctWorldsStayIsolatedThroughApply(GameTestHelper helper) {
        withTempWorldServerConfigDir(worldA -> withTempWorldServerConfigDir(worldB -> {
            boolean previousEnabled = FlowingFluidsConfig.EROSION_ENABLED.get();
            int previousSamples = FlowingFluidsConfig.SAMPLES_PER_SECOND.get();
            try {
                Files.writeString(worldA.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                        "erosionEnabled = true\nwaterSamplesPerSecond = 128\n");
                Files.writeString(worldB.resolve(PerWorldLegacyMigration.LEGACY_FILE_NAME),
                        "erosionEnabled = false\nwaterSamplesPerSecond = 0\n");

                PerWorldLegacyMigration.apply(worldA);
                PerWorldLegacyMigration.apply(worldB);

                String worldAOverride = Files.readString(worldA.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME));
                String worldBOverride = Files.readString(worldB.resolve(PerWorldLegacyMigration.OVERRIDE_FILE_NAME));
                helper.assertTrue(worldAOverride.contains("erosionEnabled = true"), worldAOverride);
                helper.assertTrue(worldBOverride.contains("erosionEnabled = false"), worldBOverride);
            } catch (java.io.IOException e) {
                helper.fail("fixture setup failed: " + e);
            } finally {
                FlowingFluidsConfig.EROSION_ENABLED.set(previousEnabled);
                FlowingFluidsConfig.SAMPLES_PER_SECOND.set(previousSamples);
            }
        }));
        helper.succeed();
    }
}
