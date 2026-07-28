package io.arona74.aronalayersgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Per-biome NBT tree placement registry.
 *
 * Config file: cr_nbt_trees.json
 * NBT files:   nbt_trees/<species>/<variant>.nbt  (project root, bundled into jar via build.gradle)
 *
 * Format:
 * {
 *   "biomes": {
 *     "minecraft:birch_forest": {
 *       "chance": 0.05,
 *       "attempts_per_chunk": 3,
 *       "surface_blocks": ["minecraft:grass_block", "minecraft:dirt"],
 *       "trees": [
 *         { "species": "aspen", "weight": 3 },
 *         { "species": "oak",   "weight": 1 }
 *       ]
 *     }
 *   }
 * }
 */
public class NbtTreeRegistry {

    private record WeightedSpecies(String species, int weight) {}

    private record BiomeEntry(
        Set<Block> surfaceBlocks,
        List<WeightedSpecies> species,
        int totalWeight,
        float chance,
        int attemptsPerChunk
    ) {
        String selectSpecies(RandomSource random) {
            if (species.isEmpty()) return null;
            int value = random.nextInt(totalWeight);
            int cumulative = 0;
            for (WeightedSpecies ws : species) {
                cumulative += ws.weight();
                if (value < cumulative) return ws.species();
            }
            return species.get(species.size() - 1).species();
        }

        boolean allowsSurface(Block block) {
            return surfaceBlocks.isEmpty() || surfaceBlocks.contains(block);
        }
    }

    private static volatile NbtTreeRegistry instance = null;
    private final Map<ResourceLocation, BiomeEntry> entries;
    private final Map<String, List<Path>> speciesFiles;   // species → sorted list of .nbt Paths
    private final Map<ResourceLocation, String>   saplingSpecies; // sapling block ID → species name

    private NbtTreeRegistry(Map<ResourceLocation, BiomeEntry> entries, Map<String, List<Path>> speciesFiles,
                             Map<ResourceLocation, String> saplingSpecies) {
        this.entries = entries;
        this.speciesFiles = speciesFiles;
        this.saplingSpecies = saplingSpecies;
    }

    public static NbtTreeRegistry getInstance() {
        if (instance == null) {
            synchronized (NbtTreeRegistry.class) {
                if (instance == null) {
                    instance = load();
                }
            }
        }
        return instance;
    }

    /** Drop the cached instance so it is reloaded on next access (e.g. after config change). */
    public static void invalidate() {
        instance = null;
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private static NbtTreeRegistry load() {
        JsonObject root = ConfigLoader.loadJsonObject("cr_nbt_trees.json");
        Map<ResourceLocation, BiomeEntry> entries = new LinkedHashMap<>();
        Set<String> allSpecies = new LinkedHashSet<>();

        if (!root.has("biomes")) {
            AronaLayersGen.LOGGER.warn("[NbtTrees] No 'biomes' key in cr_nbt_trees.json");
        } else {
            JsonObject biomes = root.getAsJsonObject("biomes");
            for (String biomeId : biomes.keySet()) {
                JsonElement biomeElem = biomes.get(biomeId);
                if (!biomeElem.isJsonObject()) continue;

                ResourceLocation biomeIdent = ResourceLocation.tryParse(biomeId);
                if (biomeIdent == null) {
                    AronaLayersGen.LOGGER.warn("[NbtTrees] Invalid biome ID: {}", biomeId);
                    continue;
                }

                JsonObject biomeObj = biomeElem.getAsJsonObject();
                float chance = biomeObj.has("chance") ? biomeObj.get("chance").getAsFloat() : 0.05f;
                int attempts = biomeObj.has("attempts_per_chunk") ? biomeObj.get("attempts_per_chunk").getAsInt() : 3;

                Set<Block> surfaceBlocks = new HashSet<>();
                if (biomeObj.has("surface_blocks")) {
                    for (JsonElement elem : biomeObj.getAsJsonArray("surface_blocks")) {
                        ResourceLocation blockId = ResourceLocation.tryParse(elem.getAsString());
                        if (blockId == null) continue;
                        Block block = BuiltInRegistries.BLOCK.get(blockId);
                        if (block == Blocks.AIR) {
                            AronaLayersGen.LOGGER.warn("[NbtTrees] Surface block not found: {}", elem.getAsString());
                            continue;
                        }
                        surfaceBlocks.add(block);
                    }
                }

                List<WeightedSpecies> species = new ArrayList<>();
                int totalWeight = 0;
                if (biomeObj.has("trees")) {
                    JsonArray treesArr = biomeObj.getAsJsonArray("trees");
                    for (JsonElement elem : treesArr) {
                        JsonObject treeObj = elem.getAsJsonObject();
                        String sp = treeObj.get("species").getAsString();
                        int weight = treeObj.has("weight") ? treeObj.get("weight").getAsInt() : 1;
                        if (weight <= 0) continue;
                        species.add(new WeightedSpecies(sp, weight));
                        totalWeight += weight;
                        allSpecies.add(sp);
                    }
                }

                if (totalWeight > 0) {
                    entries.put(biomeIdent, new BiomeEntry(surfaceBlocks, species, totalWeight, chance, attempts));
                    AronaLayersGen.LOGGER.info("[NbtTrees] Registered biome={} chance={} attempts={}", biomeIdent, chance, attempts);
                }
            }
        }

        // Parse sapling-to-species mappings
        Map<ResourceLocation, String> saplingSpecies = new LinkedHashMap<>();
        if (root.has("saplings")) {
            JsonObject saplings = root.getAsJsonObject("saplings");
            for (String blockId : saplings.keySet()) {
                JsonElement elem = saplings.get(blockId);
                if (!elem.isJsonPrimitive()) continue; // skip _comment entries
                ResourceLocation saplingIdent = ResourceLocation.tryParse(blockId);
                if (saplingIdent == null) {
                    AronaLayersGen.LOGGER.warn("[NbtTrees] Invalid sapling ID: {}", blockId);
                    continue;
                }
                String sp = elem.getAsString();
                saplingSpecies.put(saplingIdent, sp);
                allSpecies.add(sp);
                AronaLayersGen.LOGGER.info("[NbtTrees] Sapling {} -> species '{}'", blockId, sp);
            }
        }

        // Scan mod resources for .nbt files per species
        Map<String, List<Path>> speciesFiles = new HashMap<>();
        for (String species : allSpecies) {
            List<Path> files = scanSpeciesFiles(species);
            if (!files.isEmpty()) {
                speciesFiles.put(species, files);
                AronaLayersGen.LOGGER.info("[NbtTrees] Species '{}': {} variant(s)", species, files.size());
            } else {
                AronaLayersGen.LOGGER.warn("[NbtTrees] Species '{}': no .nbt files found under config/aronalayersgen/nbt_trees/{}/", species, species);
            }
        }

        AronaLayersGen.LOGGER.info("[NbtTrees] Loaded {} biome entries, {} sapling mappings, {} species",
            entries.size(), saplingSpecies.size(), speciesFiles.size());
        return new NbtTreeRegistry(entries, speciesFiles, saplingSpecies);
    }

    private static final Path NBT_TREES_DIR = FabricLoader.getInstance().getConfigDir()
        .resolve("aronalayersgen").resolve("nbt_trees");

    private static List<Path> scanSpeciesFiles(String species) {
        List<Path> result = new ArrayList<>();
        Path dir = NBT_TREES_DIR.resolve(species);
        if (!Files.isDirectory(dir)) return result;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".nbt"))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .forEach(result::add);
        } catch (IOException e) {
            AronaLayersGen.LOGGER.warn("[NbtTrees] Failed to scan species '{}': {}", species, e.getMessage());
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean hasBiome(ResourceLocation biomeId) {
        return entries.containsKey(biomeId);
    }

    public float getChance(ResourceLocation biomeId) {
        BiomeEntry entry = entries.get(biomeId);
        return entry != null ? entry.chance() : 0f;
    }

    public int getAttemptsPerChunk(ResourceLocation biomeId) {
        BiomeEntry entry = entries.get(biomeId);
        return entry != null ? entry.attemptsPerChunk() : 0;
    }

    /**
     * Pick a random variant path for the given sapling block ID.
     * Returns empty if the sapling has no configured species or no files exist for it.
     */
    public Optional<Path> selectVariantForSapling(ResourceLocation saplingBlockId, RandomSource random) {
        String species = saplingSpecies.get(saplingBlockId);
        if (species == null) return Optional.empty();
        List<Path> files = speciesFiles.get(species);
        if (files == null || files.isEmpty()) return Optional.empty();
        return Optional.of(files.get(random.nextInt(files.size())));
    }

    /**
     * Pick a random variant path for the given biome and surface block.
     * Returns an empty Optional if: no biome entry, surface block not allowed,
     * no files found for the selected species.
     */
    public Optional<Path> selectVariant(ResourceLocation biomeId, Block surfaceBlock, RandomSource random) {
        BiomeEntry entry = entries.get(biomeId);
        if (entry == null) return Optional.empty();
        if (!entry.allowsSurface(surfaceBlock)) return Optional.empty();

        String species = entry.selectSpecies(random);
        if (species == null) return Optional.empty();

        List<Path> files = speciesFiles.get(species);
        if (files == null || files.isEmpty()) return Optional.empty();

        return Optional.of(files.get(random.nextInt(files.size())));
    }
}
