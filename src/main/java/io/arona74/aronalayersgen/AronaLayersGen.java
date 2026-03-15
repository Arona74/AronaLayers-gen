package io.arona74.aronalayersgen;

import io.arona74.aronalayersgen.block.PowderSnowLayerBlock;
import io.arona74.aronalayersgen.command.ChunkDebugCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
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

        Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "powder_snow_layer"), POWDER_SNOW_LAYER);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "powder_snow_layer"),
                new BlockItem(POWDER_SNOW_LAYER, new FabricItemSettings()));

        ChunkDebugCommand.register();

        LOGGER.info("Arona Layers Generator initialized successfully");
    }
}
