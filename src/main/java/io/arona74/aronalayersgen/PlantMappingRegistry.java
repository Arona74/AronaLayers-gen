package io.arona74.aronalayersgen;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for mapping vanilla plants to their Conquest Reforged equivalents.
 * Only used when Conquest Reforged is the active backend.
 */
public class PlantMappingRegistry {
    private final Map<Block, String> vanillaToConquestPlant;
    private final Map<String, Block> conquestToVanillaPlant;

    public PlantMappingRegistry() {
        this.vanillaToConquestPlant = new HashMap<>();
        this.conquestToVanillaPlant = new HashMap<>();
        registerDefaultMappings();
    }

    private void registerDefaultMappings() {
        Map<String, String> configMappings = ConfigLoader.loadMappings("plant_mappings.json");

        for (Map.Entry<String, String> entry : configMappings.entrySet()) {
            Identifier vanillaId = Identifier.tryParse(entry.getKey());
            if (vanillaId == null) {
                AronaLayersGen.LOGGER.warn("Invalid vanilla plant ID in config: {}", entry.getKey());
                continue;
            }

            Block vanillaBlock = Registries.BLOCK.get(vanillaId);
            if (vanillaBlock == Blocks.AIR) {
                AronaLayersGen.LOGGER.warn("Vanilla plant not found: {}", entry.getKey());
                continue;
            }

            registerPlantMapping(vanillaBlock, entry.getValue());
        }

        // 1.21.1 compat: "minecraft:grass" was renamed to "minecraft:short_grass".
        // If the config used the old name (block not found above) but short_grass exists,
        // auto-alias it to the same conquest mapping. No-op on 1.20.1 where short_grass is AIR.
        String grassMapping = configMappings.get("minecraft:grass");
        if (grassMapping != null) {
            Identifier shortGrassId = Identifier.tryParse("minecraft:short_grass");
            if (shortGrassId != null) {
                Block shortGrass = Registries.BLOCK.get(shortGrassId);
                if (shortGrass != Blocks.AIR && !vanillaToConquestPlant.containsKey(shortGrass)) {
                    registerPlantMapping(shortGrass, grassMapping);
                    AronaLayersGen.LOGGER.info("Auto-aliased minecraft:short_grass -> {} (renamed from minecraft:grass in 1.21.1)", grassMapping);
                }
            }
        }

        AronaLayersGen.LOGGER.info("Registered {} plant mappings from config", vanillaToConquestPlant.size());
    }

    private void registerPlantMapping(Block vanillaPlant, String conquestPlantId) {
        vanillaToConquestPlant.put(vanillaPlant, conquestPlantId);
        if (conquestPlantId != null) {
            conquestToVanillaPlant.put(conquestPlantId, vanillaPlant);
        }
    }

    public Block getConquestPlant(Block vanillaPlant) {
        String conquestId = vanillaToConquestPlant.get(vanillaPlant);
        if (conquestId == null) {
            return null;
        }

        Identifier identifier = Identifier.tryParse(conquestId);
        if (identifier == null) {
            AronaLayersGen.LOGGER.warn("Invalid conquest plant identifier: {}", conquestId);
            return null;
        }

        Block conquestPlant = Registries.BLOCK.get(identifier);
        if (conquestPlant == Blocks.AIR) {
            AronaLayersGen.LOGGER.warn("Conquest plant not found: {}. Is Conquest Reforged installed?", conquestId);
            return null;
        }

        return conquestPlant;
    }

    public Block getVanillaPlant(String conquestPlantId) {
        return conquestToVanillaPlant.get(conquestPlantId);
    }

    public Block getVanillaPlant(Block conquestPlant) {
        String conquestId = Registries.BLOCK.getId(conquestPlant).toString();
        return conquestToVanillaPlant.get(conquestId);
    }

    public boolean isReplaceablePlant(Block block) {
        return vanillaToConquestPlant.containsKey(block);
    }

    public boolean isConquestPlant(Block block) {
        String blockId = Registries.BLOCK.getId(block).toString();
        return conquestToVanillaPlant.containsKey(blockId);
    }
}
