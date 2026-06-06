package io.arona74.aronalayersgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Per-biome weighted rock placement registry for the conquest_enhanced_rocks feature.
 *
 * Config file: cr_enhanced_rock_mappings.json
 * Format:
 * {
 *   "biomes": {
 *     "minecraft:plains": {
 *       "surface_blocks": ["minecraft:stone", "minecraft:gravel"],
 *       "rocks": [
 *         { "block": "conquest:limestone_rocks", "weight": 3 },
 *         { "block": "conquest:andesite_rocks",  "weight": 1 }
 *       ],
 *       "chance": 0.05
 *     }
 *   }
 * }
 *
 * "chance" is optional; omit to use the global chance_to_place_rocks value.
 */
public class EnhancedRockRegistry {

    private record WeightedBlock(Block block, int weight) {}

    private record BiomeEntry(Set<Block> surfaceBlocks, List<WeightedBlock> rocks, int totalWeight, float chance) {

        /** Pick a rock block using the provided hash as the random source. Returns null if no rocks. */
        Block selectRock(long hash) {
            if (rocks.isEmpty()) return null;
            int value = (int) ((hash & 0x7FFFFFFF) % totalWeight);
            int cumulative = 0;
            for (WeightedBlock wr : rocks) {
                cumulative += wr.weight();
                if (value < cumulative) return wr.block();
            }
            return rocks.get(rocks.size() - 1).block();
        }

        boolean allowsSurface(Block block) {
            return surfaceBlocks.contains(block);
        }
    }

    private static volatile EnhancedRockRegistry instance = null;
    private final Map<Identifier, BiomeEntry> entries;

    private EnhancedRockRegistry(Map<Identifier, BiomeEntry> entries) {
        this.entries = entries;
    }

    public static EnhancedRockRegistry getInstance() {
        if (instance == null) {
            synchronized (EnhancedRockRegistry.class) {
                if (instance == null) {
                    instance = load();
                }
            }
        }
        return instance;
    }

    private static EnhancedRockRegistry load() {
        JsonObject root = ConfigLoader.loadJsonObject("cr_enhanced_rock_mappings.json");
        Map<Identifier, BiomeEntry> entries = new LinkedHashMap<>();

        if (!root.has("biomes")) {
            AronaLayersGen.LOGGER.warn("[EnhancedRock] No 'biomes' key in cr_enhanced_rock_mappings.json");
            return new EnhancedRockRegistry(entries);
        }

        JsonObject biomes = root.getAsJsonObject("biomes");
        for (String biomeId : biomes.keySet()) {
            JsonElement biomeElem = biomes.get(biomeId);
            if (!biomeElem.isJsonObject()) continue;

            Identifier biomeIdent = Identifier.tryParse(biomeId);
            if (biomeIdent == null) {
                AronaLayersGen.LOGGER.warn("[EnhancedRock] Invalid biome ID: {}", biomeId);
                continue;
            }

            JsonObject biomeObj = biomeElem.getAsJsonObject();

            // Parse surface_blocks
            Set<Block> surfaceBlocks = new HashSet<>();
            if (biomeObj.has("surface_blocks")) {
                for (JsonElement elem : biomeObj.getAsJsonArray("surface_blocks")) {
                    Identifier blockId = Identifier.tryParse(elem.getAsString());
                    if (blockId == null) {
                        AronaLayersGen.LOGGER.warn("[EnhancedRock] Invalid surface block ID: {}", elem.getAsString());
                        continue;
                    }
                    Block block = Registries.BLOCK.get(blockId);
                    if (block == net.minecraft.block.Blocks.AIR) {
                        AronaLayersGen.LOGGER.warn("[EnhancedRock] Surface block not found: {}", elem.getAsString());
                        continue;
                    }
                    surfaceBlocks.add(block);
                }
            }

            // Parse rocks with weights
            List<WeightedBlock> rocks = new ArrayList<>();
            int totalWeight = 0;
            if (biomeObj.has("rocks")) {
                JsonArray rockArray = biomeObj.getAsJsonArray("rocks");
                for (JsonElement elem : rockArray) {
                    JsonObject rockObj = elem.getAsJsonObject();
                    String blockIdStr = rockObj.get("block").getAsString();
                    int weight = rockObj.has("weight") ? rockObj.get("weight").getAsInt() : 1;
                    if (weight <= 0) continue;

                    Identifier blockId = Identifier.tryParse(blockIdStr);
                    if (blockId == null) {
                        AronaLayersGen.LOGGER.warn("[EnhancedRock] Invalid rock block ID: {}", blockIdStr);
                        continue;
                    }
                    Block block = Registries.BLOCK.get(blockId);
                    if (block == net.minecraft.block.Blocks.AIR) {
                        AronaLayersGen.LOGGER.warn("[EnhancedRock] Rock block not found: {}", blockIdStr);
                        continue;
                    }
                    rocks.add(new WeightedBlock(block, weight));
                    totalWeight += weight;
                }
            }

            // -1 means "use global chance_to_place_rocks"
            float chance = biomeObj.has("chance") ? biomeObj.get("chance").getAsFloat() : -1f;

            if (totalWeight > 0) {
                entries.put(biomeIdent, new BiomeEntry(surfaceBlocks, rocks, totalWeight, chance));
            }
        }

        AronaLayersGen.LOGGER.info("[EnhancedRock] Loaded {} biome entries", entries.size());
        return new EnhancedRockRegistry(entries);
    }

    /**
     * Select a rock block for the given biome and surface block using the hash as random source.
     * Returns null if no entry exists for the biome, the surface block is not allowed, or no rocks defined.
     */
    public Block selectRock(Identifier biomeId, Block surfaceBlock, long hash) {
        BiomeEntry entry = entries.get(biomeId);
        if (entry == null) return null;
        if (!entry.allowsSurface(surfaceBlock)) return null;
        return entry.selectRock(hash);
    }

    public boolean hasBiome(Identifier biomeId) {
        return entries.containsKey(biomeId);
    }

    /**
     * Returns the biome-specific placement chance, or -1 if not set (caller should use global).
     */
    public float getChance(Identifier biomeId) {
        BiomeEntry entry = entries.get(biomeId);
        return entry != null ? entry.chance() : -1f;
    }
}
