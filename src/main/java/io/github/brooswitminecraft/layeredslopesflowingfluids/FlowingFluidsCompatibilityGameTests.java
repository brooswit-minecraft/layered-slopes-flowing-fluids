package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * MINECRAFT-513 (comment 53021): the Traben-0/flowing_fluids mod (pinned v1.0.8-1.21-neoforge,
 * sha512 verified against the real Modrinth-hosted jar in gradle.properties/build.gradle's
 * {@code downloadFlowingFluids}) mixes directly into vanilla {@code Fluids.WATER}/
 * {@code Fluids.LAVA} (both {@link FlowingFluid} instances) rather than registering new fluid
 * types -- confirmed by decompiling the pinned jar's {@code FFFluidUtils.getStateForFluidByAmount}:
 * <pre>
 *   return amount >= 8 ? flowing.getSource(false) : flowing.getFlowing(amount, false);
 * </pre>
 * the exact same vanilla {@link FlowingFluid#getSource} / {@link FlowingFluid#getFlowing}
 * public methods this test calls directly below. A "full" (amount 8) contact is therefore
 * always a source {@link FluidState} ({@code isSource()==true}) and a "partial" (1-7) contact
 * is always a flowing state ({@code isSource()==false}), with Flowing Fluids installed or not --
 * there is no FF-specific state representation this mod would need to special-case, because
 * Flowing Fluids itself builds its states through the same vanilla factory methods.
 *
 * <p>This is standalone (vanilla-only) regression evidence for that finding: it proves
 * {@link FluidMath#amount} treats a vanilla-factory-constructed full/partial state identically
 * to the {@code LiquidBlock}-constructed states the other GameTests already use, independently
 * of whether Flowing Fluids is actually on the classpath. The separate "Run GameTests (Flowing
 * Fluids coinstalled)" CI job re-runs this entire test class with the real, pinned Flowing
 * Fluids jar also loaded (via {@code -PcoinstallFlowingFluids=true}) as the coinstalled runtime
 * acceptance evidence the ticket asks for -- proving the real mod's presence changes nothing
 * about how these states are read, not just that this reasoning holds in theory.
 *
 * <p>Remaining limit, documented rather than silently assumed away: Flowing Fluids' {@code tick()}
 * replacement changes WHETHER and HOW FAST a fluid continues to spread (its finite-water
 * simulation can let a partial state settle into a stable, non-advancing pool rather than
 * vanilla's always-re-evaluate-every-tick behaviour) -- this mod reads a {@link FluidState}'s
 * {@code isSource()}/{@code getAmount()} at the instant it samples a position either way, and
 * Flowing Fluids changes neither of those two query methods' meaning, so this mod's erosion
 * decision is unaffected by that simulation difference.
 */
@GameTestHolder(LayeredSlopesFlowingFluidsMod.MODID)
@PrefixGameTestTemplate(false)
public final class FlowingFluidsCompatibilityGameTests {
    private FlowingFluidsCompatibilityGameTests() {}

    @GameTest(template = "flat_platform")
    public static void aVanillaFactoryConstructedFullStateIsSourceAndErodesNothing(GameTestHelper helper) {
        FlowingFluid water = (FlowingFluid) Fluids.WATER;
        FluidState full = water.getSource(false);
        helper.assertTrue(full.isSource(), "a full (amount>=8) Flowing-Fluids-style state must be a source, exactly like vanilla's own source state");
        helper.assertTrue(FluidMath.amount(full.isSource(), full.getAmount()) == 0,
                "a source state must never report an erosion amount, got " + FluidMath.amount(full.isSource(), full.getAmount()));
        helper.succeed();
    }

    @GameTest(template = "flat_platform")
    public static void aVanillaFactoryConstructedPartialStateIsFlowingAndErodes(GameTestHelper helper) {
        FlowingFluid water = (FlowingFluid) Fluids.WATER;
        FluidState partial = water.getFlowing(4, false);
        helper.assertTrue(!partial.isSource(), "a partial (amount<8) Flowing-Fluids-style state must be a flowing state, exactly like vanilla's own flowing state");
        double amount = FluidMath.amount(partial.isSource(), partial.getAmount());
        helper.assertTrue(amount > 0, "a flowing state must report a positive erosion amount, got " + amount);
        // Matches the same amount a LiquidBlock.LEVEL-constructed state of the same fluid level would
        // report (the other GameTests' flowingWater() helper) -- same FluidState contract, same result.
        helper.assertTrue(amount == FluidMath.amount(false, 4),
                "a Flowing-Fluids-style partial state must compute the same erosion amount as the equivalent LiquidBlock-constructed state");
        helper.succeed();
    }
}
