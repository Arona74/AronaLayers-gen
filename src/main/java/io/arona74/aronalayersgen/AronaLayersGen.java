package io.arona74.aronalayersgen;

import io.arona74.aronalayersgen.block.LayerBlock;
import io.arona74.aronalayersgen.block.PowderSnowLayerBlock;
import io.arona74.aronalayersgen.grounding.GroundingFlags;
import io.arona74.aronalayersgen.command.ChunkDebugCommand;
import io.arona74.aronalayersgen.command.TellusDebugCommand;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AronaLayersGen implements ModInitializer {
    public static final String MOD_ID = "aronalayersgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Block POWDER_SNOW_LAYER = new PowderSnowLayerBlock(
            Compat.blockSettings(Blocks.SNOW, "powder_snow_layer").speedFactor(0.9f)
    );

    // Native layer blocks for surfaces whose backend has no matching layer block.
    // At layers=8 they convert to the given full block (like powder_snow_layer -> powder_snow).
    public static final Block DEEPSLATE_LAYER = new LayerBlock(Compat.blockSettings(Blocks.DEEPSLATE, "deepslate_layer"), Blocks.DEEPSLATE);
    public static final Block MOSS_LAYER = new LayerBlock(Compat.blockSettings(Blocks.MOSS_BLOCK, "moss_layer"), Blocks.MOSS_BLOCK);

    // Create's zinc ores. Registered whether or not Create is installed (block registries are synced,
    // and the mappings simply skip create:* ids when it is absent). Properties come from the iron ores,
    // which zinc ore matches in hardness; Create's own blocks may not exist yet at this point.
    public static final Block ZINC_ORE_LAYER = new LayerBlock(Compat.blockSettings(Blocks.IRON_ORE, "zinc_ore_layer"),
            () -> Compat.blockFromId("create:zinc_ore"));
    public static final Block DEEPSLATE_ZINC_ORE_LAYER = new LayerBlock(Compat.blockSettings(Blocks.DEEPSLATE_IRON_ORE, "deepslate_zinc_ore_layer"),
            () -> Compat.blockFromId("create:deepslate_zinc_ore"));

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Arona Layers Generator");

        Registry.register(BuiltInRegistries.BLOCK, Compat.id(MOD_ID, "powder_snow_layer"), POWDER_SNOW_LAYER);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(MOD_ID, "powder_snow_layer"),
                new BlockItem(POWDER_SNOW_LAYER, Compat.itemSettings("powder_snow_layer")));

        Registry.register(BuiltInRegistries.BLOCK, Compat.id(MOD_ID, "deepslate_layer"), DEEPSLATE_LAYER);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(MOD_ID, "deepslate_layer"),
                new BlockItem(DEEPSLATE_LAYER, Compat.itemSettings("deepslate_layer")));

        Registry.register(BuiltInRegistries.BLOCK, Compat.id(MOD_ID, "moss_layer"), MOSS_LAYER);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(MOD_ID, "moss_layer"),
                new BlockItem(MOSS_LAYER, Compat.itemSettings("moss_layer")));

        Registry.register(BuiltInRegistries.BLOCK, Compat.id(MOD_ID, "zinc_ore_layer"), ZINC_ORE_LAYER);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(MOD_ID, "zinc_ore_layer"),
                new BlockItem(ZINC_ORE_LAYER, Compat.itemSettings("zinc_ore_layer")));

        Registry.register(BuiltInRegistries.BLOCK, Compat.id(MOD_ID, "deepslate_zinc_ore_layer"), DEEPSLATE_ZINC_ORE_LAYER);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(MOD_ID, "deepslate_zinc_ore_layer"),
                new BlockItem(DEEPSLATE_ZINC_ORE_LAYER, Compat.itemSettings("deepslate_zinc_ore_layer")));

        ChunkDebugCommand.register();
        TellusDebugCommand.register();

        // cr_nbt_trees places Conquest Reforged NBT tree structures; without CR present the
        // referenced blocks don't exist, so the feature must not run even if the flag is on.
        // Force it off once here so every downstream gate (mixins, injector) sees it disabled.
        if (LayerConfig.CR_NBT_TREES && !LayerPlacementHelper.isConquestReforged()) {
            LayerConfig.CR_NBT_TREES = false;
            LOGGER.warn("[AronaLayersGen] cr_nbt_trees is enabled but Conquest Reforged is not present — disabling the feature.");
        }

        Compat.registerChunkLoad(NbtTreeInjector::onChunkLoad);
        // Re-map layers whose RTF surface was swapped to beach/river sand after POST_FEATURES
        // injection; runs at chunk-load so it catches spawn/cross-border chunks the in-init pass misses.
        Compat.registerChunkLoad(io.arona74.aronalayersgen.injection.RTFLayerInjector::onChunkLoadRemap);
        ServerTickEvents.END_SERVER_TICK.register(server -> NbtTreeInjector.flushReadyChunks());

        // Plant grounding answers "not grounded" until now: shapes are queried during vanilla's
        // block bootstrap, before the config can safely load (see GroundingFlags).
        GroundingFlags.ready = true;

        // Distant Horizons: layers in LODs. The compat class names DH types, so it is only loaded
        // when DH is installed; any API mismatch is logged rather than taking the game down.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons")) {
            try {
                io.arona74.aronalayersgen.compat.DistantHorizonsCompat.register();
            } catch (Throwable t) {
                LOGGER.warn("[DistantHorizons] support disabled: {}", t.toString());
            }
        }

        LOGGER.info("Arona Layers Generator initialized successfully");
        LOGGER.info("[Config] cr_nbt_trees={} vanilla_fallback={} layer_injection={} rtf_layer_injection={} plant_injection={} tree_injection={}",
            LayerConfig.CR_NBT_TREES, LayerConfig.CR_NBT_TREES_VANILLA_FALLBACK,
            LayerConfig.LAYER_INJECTION, LayerConfig.RTF_LAYER_INJECTION,
            LayerConfig.PLANT_INJECTION, LayerConfig.TREE_INJECTION);
    }
}
