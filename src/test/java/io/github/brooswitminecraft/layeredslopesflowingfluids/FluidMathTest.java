package io.github.brooswitminecraft.layeredslopesflowingfluids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FluidMathTest {
    @Test
    void sourceWaterNeverErodes() {
        assertEquals(0, FluidMath.amount(true, 8));
        assertEquals(0, FluidMath.amount(true, 1));
    }

    @Test
    void flowingWaterScalesWithFullness() {
        assertEquals(FluidMath.FLOWING, FluidMath.amount(false, FluidMath.FULL_FLUID));
        assertEquals(FluidMath.FLOWING / 2, FluidMath.amount(false, FluidMath.FULL_FLUID / 2));
        assertTrue(FluidMath.amount(false, 1) > 0);
    }

    @Test
    void fluidAmountIsClamped() {
        assertEquals(FluidMath.FLOWING, FluidMath.amount(false, 999));
        assertEquals(0, FluidMath.amount(false, -5));
    }
}
