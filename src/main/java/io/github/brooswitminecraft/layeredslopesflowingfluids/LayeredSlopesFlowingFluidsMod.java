package io.github.brooswitminecraft.layeredslopesflowingfluids;

import io.github.brooswitminecraft.layeredslopes.LayeredSlopesMod;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Entry point for Layered Slopes Flowing Fluids (MINECRAFT-513): flowing fluid passing over or
 * beside a layered or layerable block has a chance to erode it by one layer. Extracted from
 * Dynamic Terrain's water erosion; required dependency on Layered Slopes only, no Dynamic
 * Terrain runtime dependency. This mod owns its own base-block-to-layered-block mapping (see
 * {@link LayeredMaterials}) independently of Dynamic Terrain or the sibling Layered Slope
 * Grading extraction, per the coordination direction on MINECRAFT-513/511/512: sibling
 * extractions must not acquire a required dependency on each other merely to reuse erosion
 * helpers.
 */
@Mod(LayeredSlopesFlowingFluidsMod.MODID)
public class LayeredSlopesFlowingFluidsMod {
    public static final String MODID = "layeredslopesflowingfluids";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LayeredSlopesFlowingFluidsMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, FlowingFluidsConfig.SPEC);
        registerMaterials();
        NeoForge.EVENT_BUS.addListener(FlowingFluidErosion::onServerTick);
        LOGGER.info("Layered Slopes Flowing Fluids loaded");
    }

    /**
     * Vanilla-block-to-layered-block mapping, mirroring Dynamic Terrain's own registration
     * (same ~40 base blocks it maps to Layered Slopes' registered variants), kept standalone in
     * this mod so fluid erosion works whether or not Dynamic Terrain or Layered Slope Grading
     * are installed.
     */
    private static void registerMaterials() {
        LayeredMaterials.register(Blocks.DIRT, LayeredSlopesMod.LAYERED_DIRT);
        LayeredMaterials.register(Blocks.GRASS_BLOCK, LayeredSlopesMod.LAYERED_GRASS);
        LayeredMaterials.register(Blocks.SAND, LayeredSlopesMod.LAYERED_SAND);
        LayeredMaterials.register(Blocks.GRAVEL, LayeredSlopesMod.LAYERED_GRAVEL);
        LayeredMaterials.register(Blocks.STONE, LayeredSlopesMod.LAYERED_STONE);
        LayeredMaterials.register(Blocks.COBBLESTONE, LayeredSlopesMod.LAYERED_COBBLESTONE);
        LayeredMaterials.register(Blocks.SMOOTH_STONE, LayeredSlopesMod.LAYERED_SMOOTH_STONE);
        LayeredMaterials.register(Blocks.DEEPSLATE, LayeredSlopesMod.LAYERED_DEEPSLATE);
        LayeredMaterials.register(Blocks.COBBLED_DEEPSLATE, LayeredSlopesMod.LAYERED_COBBLED_DEEPSLATE);
        LayeredMaterials.register(Blocks.TERRACOTTA, LayeredSlopesMod.LAYERED_TERRACOTTA);
        LayeredMaterials.register(Blocks.RED_TERRACOTTA, LayeredSlopesMod.LAYERED_RED_TERRACOTTA);
        LayeredMaterials.register(Blocks.ORANGE_TERRACOTTA, LayeredSlopesMod.LAYERED_ORANGE_TERRACOTTA);
        LayeredMaterials.register(Blocks.YELLOW_TERRACOTTA, LayeredSlopesMod.LAYERED_YELLOW_TERRACOTTA);
        LayeredMaterials.register(Blocks.BROWN_TERRACOTTA, LayeredSlopesMod.LAYERED_BROWN_TERRACOTTA);
        LayeredMaterials.register(Blocks.WHITE_TERRACOTTA, LayeredSlopesMod.LAYERED_WHITE_TERRACOTTA);
        LayeredMaterials.register(Blocks.LIGHT_GRAY_TERRACOTTA, LayeredSlopesMod.LAYERED_LIGHT_GRAY_TERRACOTTA);
        LayeredMaterials.register(Blocks.MAGENTA_TERRACOTTA, LayeredSlopesMod.LAYERED_MAGENTA_TERRACOTTA);
        LayeredMaterials.register(Blocks.LIGHT_BLUE_TERRACOTTA, LayeredSlopesMod.LAYERED_LIGHT_BLUE_TERRACOTTA);
        LayeredMaterials.register(Blocks.LIME_TERRACOTTA, LayeredSlopesMod.LAYERED_LIME_TERRACOTTA);
        LayeredMaterials.register(Blocks.PINK_TERRACOTTA, LayeredSlopesMod.LAYERED_PINK_TERRACOTTA);
        LayeredMaterials.register(Blocks.GRAY_TERRACOTTA, LayeredSlopesMod.LAYERED_GRAY_TERRACOTTA);
        LayeredMaterials.register(Blocks.CYAN_TERRACOTTA, LayeredSlopesMod.LAYERED_CYAN_TERRACOTTA);
        LayeredMaterials.register(Blocks.PURPLE_TERRACOTTA, LayeredSlopesMod.LAYERED_PURPLE_TERRACOTTA);
        LayeredMaterials.register(Blocks.BLUE_TERRACOTTA, LayeredSlopesMod.LAYERED_BLUE_TERRACOTTA);
        LayeredMaterials.register(Blocks.GREEN_TERRACOTTA, LayeredSlopesMod.LAYERED_GREEN_TERRACOTTA);
        LayeredMaterials.register(Blocks.BLACK_TERRACOTTA, LayeredSlopesMod.LAYERED_BLACK_TERRACOTTA);
        LayeredMaterials.register(Blocks.RED_SAND, LayeredSlopesMod.LAYERED_RED_SAND);
        LayeredMaterials.register(Blocks.ICE, LayeredSlopesMod.LAYERED_ICE);
        LayeredMaterials.register(Blocks.PACKED_ICE, LayeredSlopesMod.LAYERED_PACKED_ICE);
        LayeredMaterials.register(Blocks.SNOW_BLOCK, LayeredSlopesMod.LAYERED_SNOW_BLOCK);
        LayeredMaterials.register(Blocks.COARSE_DIRT, LayeredSlopesMod.LAYERED_COARSE_DIRT);
        LayeredMaterials.register(Blocks.PODZOL, LayeredSlopesMod.LAYERED_PODZOL);
        LayeredMaterials.register(Blocks.MYCELIUM, LayeredSlopesMod.LAYERED_MYCELIUM);
        LayeredMaterials.register(Blocks.MUD, LayeredSlopesMod.LAYERED_MUD);
        LayeredMaterials.register(Blocks.CLAY, LayeredSlopesMod.LAYERED_CLAY);
        LayeredMaterials.register(Blocks.SANDSTONE, LayeredSlopesMod.LAYERED_SANDSTONE);
        LayeredMaterials.register(Blocks.RED_SANDSTONE, LayeredSlopesMod.LAYERED_RED_SANDSTONE);
        LayeredMaterials.register(Blocks.TUFF, LayeredSlopesMod.LAYERED_TUFF);
        LayeredMaterials.register(Blocks.ANDESITE, LayeredSlopesMod.LAYERED_ANDESITE);
        LayeredMaterials.register(Blocks.GRANITE, LayeredSlopesMod.LAYERED_GRANITE);
        LayeredMaterials.register(Blocks.DIORITE, LayeredSlopesMod.LAYERED_DIORITE);
        LayeredMaterials.register(Blocks.NETHERRACK, LayeredSlopesMod.LAYERED_NETHERRACK);
        LayeredMaterials.register(Blocks.BLACKSTONE, LayeredSlopesMod.LAYERED_BLACKSTONE);
        LayeredMaterials.register(Blocks.ROOTED_DIRT, LayeredSlopesMod.LAYERED_DIRT);
        // Erosion disturbance settles grass as dirt instead of keeping a grass top, matching
        // Dynamic Terrain's established disturb semantic for erosion (see LayerErosion).
        LayeredMaterials.disturbsTo(LayeredSlopesMod.LAYERED_GRASS, LayeredSlopesMod.LAYERED_DIRT);
    }
}
