package io.arona74.aronalayersgen;

import io.arona74.aronalayersgen.block.LayerBlock;
import io.arona74.aronalayersgen.block.PowderSnowLayerBlock;
import io.arona74.aronalayersgen.command.ChunkDebugCommand;
import io.arona74.aronalayersgen.command.TellusDebugCommand;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
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
            FabricBlockSettings.copyOf(Blocks.SNOW).velocityMultiplier(0.9f)
    );

    // Native layer blocks for surfaces whose backend has no matching layer block.
    // At layers=8 they convert to the given full block (like powder_snow_layer -> powder_snow).
    public static final Block DEEPSLATE_LAYER = new LayerBlock(FabricBlockSettings.copyOf(Blocks.DEEPSLATE), Blocks.DEEPSLATE);
    public static final Block MOSS_LAYER = new LayerBlock(FabricBlockSettings.copyOf(Blocks.MOSS_BLOCK), Blocks.MOSS_BLOCK);

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Arona Layers Generator");

        Registry.register(BuiltInRegistries.BLOCK, Compat.id(MOD_ID, "powder_snow_layer"), POWDER_SNOW_LAYER);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(MOD_ID, "powder_snow_layer"),
                new BlockItem(POWDER_SNOW_LAYER, new Item.Properties()));

        Registry.register(BuiltInRegistries.BLOCK, Compat.id(MOD_ID, "deepslate_layer"), DEEPSLATE_LAYER);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(MOD_ID, "deepslate_layer"),
                new BlockItem(DEEPSLATE_LAYER, new Item.Properties()));

        Registry.register(BuiltInRegistries.BLOCK, Compat.id(MOD_ID, "moss_layer"), MOSS_LAYER);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(MOD_ID, "moss_layer"),
                new BlockItem(MOSS_LAYER, new Item.Properties()));

        ChunkDebugCommand.register();
        TellusDebugCommand.register();

        // cr_nbt_trees places Conquest Reforged NBT tree structures; without CR present the
        // referenced blocks don't exist, so the feature must not run even if the flag is on.
        // Force it off once here so every downstream gate (mixins, injector) sees it disabled.
        if (LayerConfig.CR_NBT_TREES && !LayerPlacementHelper.isConquestReforged()) {
            LayerConfig.CR_NBT_TREES = false;
            LOGGER.warn("[AronaLayersGen] cr_nbt_trees is enabled but Conquest Reforged is not present — disabling the feature.");
        }

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> NbtTreeInjector.onChunkLoad(world, chunk));
        ServerTickEvents.END_SERVER_TICK.register(server -> NbtTreeInjector.flushReadyChunks());

        LOGGER.info("Arona Layers Generator initialized successfully");
        LOGGER.info("[Config] cr_nbt_trees={} vanilla_fallback={} layer_injection={} rtf_layer_injection={} plant_injection={} tree_injection={}",
            LayerConfig.CR_NBT_TREES, LayerConfig.CR_NBT_TREES_VANILLA_FALLBACK,
            LayerConfig.LAYER_INJECTION, LayerConfig.RTF_LAYER_INJECTION,
            LayerConfig.PLANT_INJECTION, LayerConfig.TREE_INJECTION);
    }
}
