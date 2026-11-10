package io.github.brooswitminecraft.layeredslopesflowingfluids;

/**
 * Pure flowing-fluid erosion arithmetic, free of Minecraft types so it can be unit tested
 * without bootstrapping the game. Unlike Dynamic Terrain's retired water erosion (which let
 * even still source water erode weakly), this mod's explicit scope is flowing fluid only:
 * {@code amount(amount)} returns 0 for a source/still contact, and scales with how full the
 * flowing cell is otherwise.
 */
public final class FluidMath {
    /** Erosion amount from a full-strength flowing cell. */
    public static final double FLOWING = 0.5;
    public static final int FULL_FLUID = 8;

    private FluidMath() {}

    /**
     * @param source whether the contacting fluid cell is a still source block
     * @param fluidAmount 1..8, the fluid state's amount
     * @return 0 for a source/still contact (never erodes); otherwise scaled by how full the
     *     flowing cell is
     */
    public static double amount(boolean source, int fluidAmount) {
        if (source) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(FULL_FLUID, fluidAmount));
        return FLOWING * clamped / FULL_FLUID;
    }
}
