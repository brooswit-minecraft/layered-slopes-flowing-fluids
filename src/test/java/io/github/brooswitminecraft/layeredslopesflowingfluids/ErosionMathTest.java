package io.github.brooswitminecraft.layeredslopesflowingfluids;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ErosionMathTest {
    @Test
    void unbreakableBlocksNeverErode() {
        // Bedrock-like: unbreakable (negative hardness) AND blast-proof (resistance far past the
        // ceiling) -- both terms of the 0.5/0.5 average must hit 1.0 for the overall result to.
        assertEquals(1.0, ErosionMath.resistance(-1, 1_000_000));
        assertEquals(0.0, ErosionMath.successChance(1.0, ErosionMath.resistance(-1, 1_000_000)));
    }

    @Test
    void softerBlocksErodeMoreReadilyThanHarderOnes() {
        double soft = ErosionMath.resistance(0.5, 2.5);
        double hard = ErosionMath.resistance(50.0, 1200.0);
        assertTrue(soft < hard);
        assertTrue(ErosionMath.successChance(0.5, soft) > ErosionMath.successChance(0.5, hard));
    }

    @Test
    void successChanceIsClampedToZeroOne() {
        assertTrue(ErosionMath.successChance(1000.0, 0.0) <= 1.0);
        assertEquals(0.0, ErosionMath.successChance(0.0, 0.0));
    }
}
