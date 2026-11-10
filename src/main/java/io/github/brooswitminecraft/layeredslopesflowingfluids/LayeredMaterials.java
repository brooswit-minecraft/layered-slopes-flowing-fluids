package io.github.brooswitminecraft.layeredslopesflowingfluids;

import io.github.brooswitminecraft.layeredslopes.LayeredBlock;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.world.level.block.Block;

/**
 * Pairs each vanilla full block with its Layered Slopes layered variant, so fluid erosion can
 * convert a full block into a 15-layer layered block on first contact. Mirrors Dynamic
 * Terrain's own {@code LayeredMaterials} base-to-layered pairing (duplicated per-mod per this
 * extraction's ticket, MINECRAFT-513, not shared via a common library yet).
 */
public final class LayeredMaterials {
    private static final Map<Block, Supplier<? extends LayeredBlock>> LAYERED_BY_BASE = new HashMap<>();
    private static final Map<Supplier<? extends LayeredBlock>, Supplier<? extends LayeredBlock>> DISTURBS_TO = new HashMap<>();

    private LayeredMaterials() {}

    public static void register(Block base, Supplier<? extends LayeredBlock> layered) {
        LAYERED_BY_BASE.put(base, layered);
    }

    /** The layered variant of a base block, or null if it has none registered. */
    public static LayeredBlock layeredFor(Block base) {
        Supplier<? extends LayeredBlock> layered = LAYERED_BY_BASE.get(base);
        return layered == null ? null : layered.get();
    }

    /**
     * What {@code original} settles into when disturbed by erosion (grass disturbed into dirt,
     * matching Dynamic Terrain's established "erosion disturbs grass to dirt" semantic). Members
     * with no registered disturbance are untouched.
     */
    public static void disturbsTo(Supplier<? extends LayeredBlock> original, Supplier<? extends LayeredBlock> disturbed) {
        DISTURBS_TO.put(original, disturbed);
    }

    /** The disturbed settling of {@code block}, or {@code block} itself if it has none registered. */
    public static LayeredBlock disturbed(LayeredBlock block) {
        for (Map.Entry<Supplier<? extends LayeredBlock>, Supplier<? extends LayeredBlock>> entry : DISTURBS_TO.entrySet()) {
            if (entry.getKey().get() == block) {
                return entry.getValue().get();
            }
        }
        return block;
    }
}
