package io.arona74.aronalayersgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.*;

/**
 * Per-biome weighted grass placement registry for the conquest_enhanced_extra_foliage feature.
 *
 * Config file: cr_enhanced_extra_foliage.json
 * Format:
 * {
 *   "biomes": {
 *     "minecraft:plains": {
 *       "surface_blocks": ["minecraft:grass_block", "minecraft:dirt"],
 *       "grasses": [
 *         { "block": "conquest:lush_grass", "weight": 3 },
 *         { "block": "conquest:cotton_grass", "weight": 1 }
 *       ]
 *     }
 *   }
 * }
 */
public class EnhancedFoliageRegistry {

    private record WeightedBlock(Block block, int weight) {}

    private record BiomeEntry(Set<Block> surfaceBlocks, List<WeightedBlock> grasses, int totalWeight, float chance) {

        /** Pick a grass block using the provided hash as the random source. Returns null if no grasses. */
        Block selectGrass(long hash) {
            if (grasses.isEmpty()) return null;
            int value = (int) ((hash & 0x7FFFFFFF) % totalWeight);
            int cumulative = 0;
            for (WeightedBlock wg : grasses) {
                cumulative += wg.weight();
                if (value < cumulative) return wg.block();
            }
            return grasses.get(grasses.size() - 1).block();
        }

        boolean allowsSurface(Block block) {
            return surfaceBlocks.contains(block);
        }
    }

    private static volatile EnhancedFoliageRegistry instance = null;
    private final Map<String, BiomeEntry> entries;

    private EnhancedFoliageRegistry(Map<String, BiomeEntry> entries) {
        this.entries = entries;
    }

    public static EnhancedFoliageRegistry getInstance() {
        if (instance == null) {
            synchronized (EnhancedFoliageRegistry.class) {
                if (instance == null) {
                    instance = load();
                }
            }
        }
        return instance;
    }

    private static EnhancedFoliageRegistry load() {
        JsonObject root = ConfigLoader.loadJsonObject("cr_enhanced_extra_foliage.json");
        Map<String, BiomeEntry> entries = new LinkedHashMap<>();

        if (!root.has("biomes")) {
            AronaLayersGen.LOGGER.warn("[EnhancedFoliage] No 'biomes' key in cr_enhanced_extra_foliage.json");
            return new EnhancedFoliageRegistry(entries);
        }

        JsonObject biomes = root.getAsJsonObject("biomes");
        for (String biomeId : biomes.keySet()) {
            // Skip comment entries or any non-object value
            JsonElement biomeElem = biomes.get(biomeId);
            if (!biomeElem.isJsonObject()) continue;

            String biomeIdent = Compat.normalizeId(biomeId);
            if (biomeIdent == null) {
                AronaLayersGen.LOGGER.warn("[EnhancedFoliage] Invalid biome ID: {}", biomeId);
                continue;
            }

            JsonObject biomeObj = biomeElem.getAsJsonObject();

            // Parse surface_blocks
            Set<Block> surfaceBlocks = new HashSet<>();
            if (biomeObj.has("surface_blocks")) {
                for (JsonElement elem : biomeObj.getAsJsonArray("surface_blocks")) {
                    String blockId = Compat.normalizeId(elem.getAsString());
                    if (blockId == null) {
                        AronaLayersGen.LOGGER.warn("[EnhancedFoliage] Invalid surface block ID: {}", elem.getAsString());
                        continue;
                    }
                    Block block = Compat.blockFromId(blockId);
                    if (block == net.minecraft.world.level.block.Blocks.AIR) {
                        AronaLayersGen.LOGGER.warn("[EnhancedFoliage] Surface block not found: {}", elem.getAsString());
                        continue;
                    }
                    surfaceBlocks.add(block);
                }
            }

            // Parse grasses with weights
            List<WeightedBlock> grasses = new ArrayList<>();
            int totalWeight = 0;
            if (biomeObj.has("grasses")) {
                JsonArray grassArray = biomeObj.getAsJsonArray("grasses");
                for (JsonElement elem : grassArray) {
                    JsonObject grassObj = elem.getAsJsonObject();
                    String blockIdStr = grassObj.get("block").getAsString();
                    int weight = grassObj.has("weight") ? grassObj.get("weight").getAsInt() : 1;
                    if (weight <= 0) continue;

                    String blockId = Compat.normalizeId(blockIdStr);
                    if (blockId == null) {
                        AronaLayersGen.LOGGER.warn("[EnhancedFoliage] Invalid grass block ID: {}", blockIdStr);
                        continue;
                    }
                    Block block = Compat.blockFromId(blockId);
                    if (block == net.minecraft.world.level.block.Blocks.AIR) {
                        AronaLayersGen.LOGGER.warn("[EnhancedFoliage] Grass block not found: {}", blockIdStr);
                        continue;
                    }
                    grasses.add(new WeightedBlock(block, weight));
                    totalWeight += weight;
                }
            }

            // -1 means "use global chance_to_place_extra_foliage"
            float chance = biomeObj.has("chance") ? biomeObj.get("chance").getAsFloat() : -1f;

            if (totalWeight > 0) {
                entries.put(biomeIdent, new BiomeEntry(surfaceBlocks, grasses, totalWeight, chance));
            }
        }

        AronaLayersGen.LOGGER.info("[EnhancedFoliage] Loaded {} biome entries", entries.size());
        return new EnhancedFoliageRegistry(entries);
    }

    /**
     * Select a grass block for the given biome and surface block using the hash as random source.
     * Returns null if no entry exists for the biome, the surface block is not allowed, or no grasses are defined.
     */
    public Block selectGrass(String biomeId, Block surfaceBlock, long hash) {
        BiomeEntry entry = entries.get(biomeId);
        if (entry == null) return null;
        if (!entry.allowsSurface(surfaceBlock)) return null;
        return entry.selectGrass(hash);
    }

    public boolean hasBiome(String biomeId) {
        return entries.containsKey(biomeId);
    }

    /**
     * Returns the biome-specific placement chance, or -1 if not set (caller should use global).
     */
    public float getChance(String biomeId) {
        BiomeEntry entry = entries.get(biomeId);
        return entry != null ? entry.chance() : -1f;
    }
}
