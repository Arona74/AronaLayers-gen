package io.arona74.aronalayersgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The plants a layer can be placed under: when a layer is generated where one of them stands, the
 * plant is moved up one block onto the layer instead of blocking it (plant grounding then draws it
 * on the layer's surface).
 *
 * <p>Read from plant_mappings.json, a {@code "plants"} list of block ids. Files written by earlier
 * versions hold a {@code "mappings"} object instead (plant to replacement plant); its keys are read
 * as the list, so those files keep working.
 */
public class PlantMappingRegistry {
    private final Set<Block> plants = new HashSet<>();

    public PlantMappingRegistry() {
        load();
    }

    private void load() {
        JsonObject root = ConfigLoader.loadJsonObject("plant_mappings.json");
        Set<String> ids = new LinkedHashSet<>();
        if (root != null && root.has("plants") && root.get("plants").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("plants")) {
                if (!element.isJsonNull()) ids.add(element.getAsString());
            }
        }
        if (root != null && root.has("mappings") && root.get("mappings").isJsonObject()) {
            ids.addAll(root.getAsJsonObject("mappings").keySet());
        }

        for (String id : ids) {
            String plantId = Compat.normalizeId(id);
            if (plantId == null) {
                AronaLayersGen.LOGGER.warn("Invalid plant ID in config: {}", id);
                continue;
            }

            Block plant = Compat.blockFromId(plantId);
            if (plant == Blocks.AIR) {
                AronaLayersGen.LOGGER.warn("Vanilla plant not found: {}", id);
                continue;
            }

            plants.add(plant);
        }

        // "minecraft:grass" was renamed to "minecraft:short_grass" in 1.20.3. A list naming the old
        // id (not found above on later versions) covers the new one too. No-op on 1.20.1, where
        // short_grass does not exist.
        if (ids.contains("minecraft:grass")) {
            String shortGrassId = Compat.normalizeId("minecraft:short_grass");
            if (shortGrassId != null) {
                Block shortGrass = Compat.blockFromId(shortGrassId);
                if (shortGrass != Blocks.AIR && plants.add(shortGrass)) {
                    AronaLayersGen.LOGGER.info("Auto-aliased minecraft:short_grass (renamed from minecraft:grass)");
                }
            }
        }

        AronaLayersGen.LOGGER.info("Registered {} plants from plant_mappings.json", plants.size());
    }

    /** Whether a layer may take this plant's place, moving the plant up onto it. */
    public boolean isReplaceablePlant(Block block) {
        return plants.contains(block);
    }
}
