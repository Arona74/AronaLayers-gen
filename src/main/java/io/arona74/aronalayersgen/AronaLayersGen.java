package io.arona74.aronalayersgen;

import io.arona74.aronalayersgen.block.PowderSnowLayerBlock;
import io.arona74.aronalayersgen.command.ChunkDebugCommand;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AronaLayersGen implements ModInitializer {
    public static final String MOD_ID = "aronalayersgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Block POWDER_SNOW_LAYER = new PowderSnowLayerBlock(
            FabricBlockSettings.copyOf(Blocks.SNOW).velocityMultiplier(0.9f)
    );

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Arona Layers Generator");

        Registry.register(Registries.BLOCK, Compat.id(MOD_ID, "powder_snow_layer"), POWDER_SNOW_LAYER);
        Registry.register(Registries.ITEM, Compat.id(MOD_ID, "powder_snow_layer"),
                new BlockItem(POWDER_SNOW_LAYER, new Item.Settings()));

        ChunkDebugCommand.register();

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> NbtTreeInjector.onChunkLoad(world, chunk));
        ServerTickEvents.END_SERVER_TICK.register(server -> NbtTreeInjector.flushReadyChunks());

        LOGGER.info("Arona Layers Generator initialized successfully");
        LOGGER.info("[Config] cr_nbt_trees={} vanilla_fallback={} layer_injection={} rtf_layer_injection={} plant_injection={} tree_injection={}",
            LayerConfig.CR_NBT_TREES, LayerConfig.CR_NBT_TREES_VANILLA_FALLBACK,
            LayerConfig.LAYER_INJECTION, LayerConfig.RTF_LAYER_INJECTION,
            LayerConfig.PLANT_INJECTION, LayerConfig.TREE_INJECTION);
    }
}
