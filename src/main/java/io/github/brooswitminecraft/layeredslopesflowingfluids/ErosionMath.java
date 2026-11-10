package io.github.brooswitminecraft.layeredslopesflowingfluids;

/**
 * Pure erosion arithmetic, free of Minecraft types so it can be unit tested without
 * bootstrapping the game. Resistance comes from hardness and blast resistance, each
 * log-compressed against an obsidian-scale ceiling so extreme outliers cannot dominate, then
 * averaged into 0..1. Copied from Dynamic Terrain's own {@code ErosionMath} (the same curve
 * dynamic-terrain's water/heat erosion use), not shared via a common library: this extraction's
 * ticket (MINECRAFT-513) treats the small pure helpers as fine to duplicate per-mod for now,
 * matching the pattern layered-slope-grading's own extraction (MINECRAFT-511) already set.
 */
public final class ErosionMath {
    /** Obsidian-scale ceilings the log compression normalises against. */
    public static final double HARDNESS_CEILING = 50.0;
    public static final double BLAST_CEILING = 1200.0;

    private ErosionMath() {}

    private static double compress(double value, double ceiling) {
        if (value < 0) {
            return 1.0; // unbreakable blocks (bedrock) never erode
        }
        return Math.min(1.0, Math.log1p(value) / Math.log1p(ceiling));
    }

    /** 0 = erodes freely, 1 = does not erode. */
    public static double resistance(double hardness, double blastResistance) {
        return 0.5 * compress(hardness, HARDNESS_CEILING) + 0.5 * compress(blastResistance, BLAST_CEILING);
    }

    /** Chance that one erode(amount) call lets the block lose a layer. */
    public static double successChance(double amount, double resistance) {
        double open = 1.0 - resistance;
        double open2 = open * open;
        return Math.max(0.0, Math.min(1.0, amount * open2 * open2));
    }
}
