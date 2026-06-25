package io.arona74.aronalayersgen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for layer generation.
 * Loaded from layer_config.json in the config/aronalayersgen/ directory.
 */
public class LayerConfig {
    private static final Gson GSON = new Gson();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("aronalayersgen");
    private static final String CONFIG_FILE = "layer_config.json";

    /**
     * Enable layer injection during terrain generation (vanilla heightmap-based).
     * When enabled, layers are generated during chunk creation using heightmap analysis.
     * Requires game restart to take effect.
     */
    public static boolean LAYER_INJECTION = false;

    /**
     * Skip layer generation in snowy/cold biomes.
     * Prevents layer blocks from interfering with natural snow placement.
     */
    public static boolean SKIP_SNOWY_BIOMES = true;

    /**
     * Enable layer injection using ReTerraForged terrain data.
     * When enabled and ReTerraForged is installed, layers are generated using RTF's
     * Cell data (gradient, sediment, erosion) for realistic layer distribution.
     * Requires ReTerraForged mod to be installed.
     * Requires game restart to take effect.
     */
    public static boolean RTF_LAYER_INJECTION = false;

    /**
     * Use vanilla's NoiseRouter density functions (continents, erosion, ridges) to
     * compute a synthetic cell height for layer placement, replacing the slope-based
     * heuristic. Applies the same fractional formula as RTF layer injection for smooth,
     * terrain-correlated layer count variation. Requires layer_injection=true.
     * Has no effect when RTF is active. Requires game restart.
     */
    public static boolean VANILLA_NOISE_ROUTER_LAYER_INJECTION = false;

    /**
     * When to inject layers during world generation.
     * CARVERS: Inject after carvers but before features/structures.
     * POST_FEATURES: Inject after all features including structures.
     * Requires RTF_LAYER_INJECTION to be enabled.
     */
    public static InjectionMode INJECTION_MODE = InjectionMode.CARVERS;

    public enum InjectionMode {
        CARVERS,
        POST_FEATURES
    }

    public static boolean DEBUG_LOGGING = false;

    // Per-category debug flags — only active when DEBUG_LOGGING=true.
    public static boolean DEBUG_LOG_CHUNK_INIT         = true;
    public static boolean DEBUG_LOG_RTF                = true;
    public static boolean DEBUG_LOG_STRUCTURE          = true;
    public static boolean DEBUG_LOG_SKIP_ELEVATED      = true;
    public static boolean DEBUG_LOG_STRUCTURE_NO_LAYERS = true;
    public static boolean DEBUG_LOG_SECOND_PASS        = true;
    public static boolean DEBUG_LOG_FOLIAGE            = true;
    public static boolean DEBUG_LOG_PLANTS             = true;
    public static boolean DEBUG_LOG_ROCKS              = true;
    public static boolean DEBUG_LOG_CORRECTION         = true;
    public static boolean DEBUG_LOG_SNOW               = true;
    public static boolean DEBUG_LOG_TREE_SOIL          = true;
    public static boolean DEBUG_LOG_VANILLA            = true;
    public static boolean DEBUG_LOG_NBT_TREES          = true;

    public static boolean logChunkInit()         { return DEBUG_LOGGING && DEBUG_LOG_CHUNK_INIT; }
    public static boolean logRtf()               { return DEBUG_LOGGING && DEBUG_LOG_RTF; }
    public static boolean logStructure()         { return DEBUG_LOGGING && DEBUG_LOG_STRUCTURE; }
    public static boolean logSkipElevated()      { return DEBUG_LOGGING && DEBUG_LOG_SKIP_ELEVATED; }
    public static boolean logStructureNoLayers() { return DEBUG_LOGGING && DEBUG_LOG_STRUCTURE_NO_LAYERS; }
    public static boolean logSecondPass()        { return DEBUG_LOGGING && DEBUG_LOG_SECOND_PASS; }
    public static boolean logFoliage()           { return DEBUG_LOGGING && DEBUG_LOG_FOLIAGE; }
    public static boolean logPlants()            { return DEBUG_LOGGING && DEBUG_LOG_PLANTS; }
    public static boolean logRocks()             { return DEBUG_LOGGING && DEBUG_LOG_ROCKS; }
    public static boolean logCorrection()        { return DEBUG_LOGGING && DEBUG_LOG_CORRECTION; }
    public static boolean logSnow()              { return DEBUG_LOGGING && DEBUG_LOG_SNOW; }
    public static boolean logTreeSoil()          { return DEBUG_LOGGING && DEBUG_LOG_TREE_SOIL; }
    public static boolean logVanilla()           { return DEBUG_LOGGING && DEBUG_LOG_VANILLA; }
    public static boolean logNbtTrees()          { return DEBUG_LOGGING && DEBUG_LOG_NBT_TREES; }

    /**
     * Enable layer placement on underwater surfaces.
     * Layer blocks must support waterlogging.
     */
    public static boolean UNDERWATER_LAYERS = false;

    /**
     * Enable plant injection during terrain generation.
     * Requires Conquest Reforged. Layer blocks become transparent to plant placement,
     * and vanilla plants above layers are converted to CR equivalents.
     * Requires game restart to take effect.
     */
    public static boolean PLANT_INJECTION = false;

    /**
     * Enable tree injection during terrain generation.
     * Layer blocks are treated as replaceable by tree features.
     * Requires game restart to take effect.
     */
    public static boolean TREE_INJECTION = false;

    /**
     * Enable structure-aware layer injection.
     * Layers are not placed inside structure bounding boxes.
     */
    public static boolean STRUCTURE_INJECTION = false;

    /**
     * Expand structure bounding boxes by extra_bounds_distance blocks in XZ
     * when checking if a position is inside a structure. Adds a buffer zone around
     * structure pieces where layers are also suppressed.
     * Requires STRUCTURE_INJECTION.
     */
    public static boolean STRUCTURE_INJECTION_EXTRA_BOUNDS = false;

    /**
     * Radius (in blocks) to expand each structure bounding box outward in XZ
     * when STRUCTURE_INJECTION_EXTRA_BOUNDS is enabled.
     */
    public static int STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE = 4;

    /**
     * Enable cross-chunk structure detection.
     * When enabled, also checks structure references in neighboring chunks to catch
     * structures that start outside the current chunk but extend into it.
     * May cause hangs during heavy pre-generation (e.g. Chunky). Disable if you
     * experience server tick timeouts during worldgen. Requires STRUCTURE_INJECTION.
     */
    public static boolean CROSS_CHUNK_STRUCTURE_DETECTION = false;

    public static boolean ENCLOSED_SPACE_CHECK = false;
    public static int ENCLOSED_SPACE_HEIGHT = 5;
    public static boolean STRUCTURE_CLEANUP = false;

    /**
     * Enable heightmap-based structure detection.
     * Removes layers where structures changed the heightmap.
     */
    public static boolean STRUCTURE_NO_LAYERS = false;

    /**
     * Skip layer placement at positions where a structure elevated the terrain above the
     * original surface. Compares the post-structure surface against a pre-structure
     * heightmap snapshot captured during the carvers phase.
     * Only effective with injection_mode=POST_FEATURES.
     */
    public static boolean STRUCTURE_SKIP_ELEVATED = false;

    /**
     * When STRUCTURE_SKIP_ELEVATED is true: remove layer blocks within
     * STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE of any position where elevation was detected.
     * Cleans up layers that were placed on or near the raised terrain around structures.
     * Requires STRUCTURE_SKIP_ELEVATED.
     */
    public static boolean STRUCTURE_SKIP_EXTRA_CLEANUP = false;

    /**
     * Radius (in blocks, Chebyshev distance) around each detected elevated position
     * within which layer blocks are removed. Only used when STRUCTURE_SKIP_EXTRA_CLEANUP is true.
     */
    public static int STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE = 2;

    /**
     * When STRUCTURE_SKIP_ELEVATED is true: if a structure placed a mapped block exactly
     * at the original layer target position (1-block elevation), replace that block with
     * the layer instead of skipping. Has no effect for elevations of 2+ blocks.
     */
    public static boolean STRUCTURE_REPLACE_ELEVATED = false;

    /**
     * When STRUCTURE_SKIP_ELEVATED is true: also replace elevated terrain that is more
     * than 1 block above the original surface (up to STRUCTURE_REPLACE_ELEVATED_HIGH_MAX).
     * Has no effect when STRUCTURE_REPLACE_ELEVATED is false.
     */
    public static boolean STRUCTURE_REPLACE_ELEVATED_HIGH = false;

    /**
     * Maximum elevation delta (in blocks) for which STRUCTURE_REPLACE_ELEVATED_HIGH applies.
     * Positions elevated by more than this many blocks above the natural surface are skipped.
     */
    public static int STRUCTURE_REPLACE_ELEVATED_HIGH_MAX = 3;

    /**
     * When placing layers on dirt_path, also replace the dirt_path block
     * with the full-block equivalent of the layer.
     */
    public static boolean REPLACE_DIRT_PATH = false;

    /**
     * When placing a sand layer (conquest:sand_layer), substitute conquest:wet_sand_layer
     * if the layer will be waterlogged or is positioned below Y=63 (sea level).
     */
    public static boolean PLACE_WET_SAND = false;

    /**
     * Convert vanilla seagrass/tall_seagrass to Conquest equivalents.
     * Requires PLANT_INJECTION and Conquest Reforged.
     */
    public static boolean REPLACE_SEA_GRASS = false;

    /**
     * Use vanilla snow layers in snowy biomes with original un-reduced layer value.
     * Requires skip_snowy_biomes=false.
     */
    public static boolean IMPROVE_SNOWY_BIOMES = false;

    /**
     * When breaking a snow layer, replace it with the mapped layer block
     * based on the block below, with layer value reduced by 1.
     */
    public static boolean BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS = false;

    /**
     * Place rock blocks above layers on mapped surface blocks.
     * Requires Conquest Reforged and cr_rock_mappings.json.
     */
    public static boolean PLACE_ROCKS = false;
    public static float CHANCE_TO_PLACE_ROCKS = 0.1f;
    public static RockDensityMode ROCK_DENSITY_MODE = RockDensityMode.DECREASED;

    public enum RockDensityMode {
        RANDOM,
        DECREASED
    }

    public static float ROCK_DENSITY_FACTOR = 4.0f;

    /**
     * Place conquest foliage above layers on mapped surface blocks.
     * Requires Conquest Reforged and cr_extra_foliage_mappings.json.
     */
    public static boolean PLACE_EXTRA_FOLIAGE = false;
    public static float CHANCE_TO_PLACE_EXTRA_FOLIAGE = 0.1f;

    /**
     * Per-biome weighted grass placement using cr_enhanced_extra_foliage.json.
     * Requires Conquest Reforged. Uses the same chance_to_place_extra_foliage probability.
     */
    public static boolean CONQUEST_ENHANCED_EXTRA_FOLIAGE = false;

    /**
     * Per-biome weighted rock placement using cr_enhanced_rock_mappings.json.
     * Requires Conquest Reforged. Uses the same chance_to_place_rocks probability unless overridden per biome.
     * When enabled and the biome has an entry, falls back to cr_rock_mappings.json for unregistered biomes.
     */
    public static boolean CONQUEST_ENHANCED_ROCKS = false;

    /**
     * When structure_injection or structure_no_layers blocks/removes a layer whose surface
     * block is listed in second_pass_cleanup_blocks.json, taper the 8 in-chunk neighbors:
     * count > 4 → 4, count ≤ 4 → count-1, count 0 → remove (plant above moved down).
     */
    public static boolean SECOND_PASS_CLEANUP = false;

    /**
     * Place CR hand-made trees from NBT files during world generation.
     * NBT files are loaded from nbt_trees/<species>/ bundled in the mod jar.
     * Biome-to-species mapping is configured in cr_nbt_trees.json.
     * Requires Conquest Reforged.
     */
    public static boolean CR_NBT_TREES = false;

    /**
     * When cr_nbt_trees=true and no NBT species is found for a growing sapling
     * (sapling type not configured, biome not configured, or no NBT files available),
     * allow vanilla tree generation to proceed as a fallback.
     * false = suppress vanilla entirely; the sapling will not grow.
     */
    public static boolean CR_NBT_TREES_VANILLA_FALLBACK = true;

    static {
        loadConfig();
    }

    public static void loadConfig() {
        Path externalConfig = CONFIG_DIR.resolve(CONFIG_FILE);
        if (Files.exists(externalConfig)) {
            try {
                loadFromFile(externalConfig);
                AronaLayersGen.LOGGER.info("Loaded layer config from: {}", externalConfig);
                return;
            } catch (IOException e) {
                AronaLayersGen.LOGGER.error("Failed to load external config, using resource", e);
            }
        }

        try {
            loadFromResource();
            AronaLayersGen.LOGGER.info("Loaded layer config from resource");
            createExternalConfig();
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to load config, using defaults", e);
        }
    }

    private static void loadFromFile(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            parseConfig(reader);
        }
    }

    private static void loadFromResource() throws IOException {
        InputStream inputStream = LayerConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
        if (inputStream == null) {
            throw new IOException("Resource not found: " + CONFIG_FILE);
        }

        try (Reader reader = new InputStreamReader(inputStream)) {
            parseConfig(reader);
        }
    }

    private static void parseConfig(Reader reader) {
        JsonObject config = GSON.fromJson(reader, JsonObject.class);
        if (config.has("layer_injection")) LAYER_INJECTION = config.get("layer_injection").getAsBoolean();
        if (config.has("rtf_layer_injection")) RTF_LAYER_INJECTION = config.get("rtf_layer_injection").getAsBoolean();
        if (config.has("vanilla_noise_router_layer_injection")) VANILLA_NOISE_ROUTER_LAYER_INJECTION = config.get("vanilla_noise_router_layer_injection").getAsBoolean();
        if (config.has("injection_mode")) {
            try {
                INJECTION_MODE = InjectionMode.valueOf(config.get("injection_mode").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                AronaLayersGen.LOGGER.warn("Invalid injection_mode in config, using default");
            }
        }
        if (config.has("skip_snowy_biomes")) SKIP_SNOWY_BIOMES = config.get("skip_snowy_biomes").getAsBoolean();
        if (config.has("debug_logging")) DEBUG_LOGGING = config.get("debug_logging").getAsBoolean();
        if (config.has("debug_log_chunk_init")) DEBUG_LOG_CHUNK_INIT = config.get("debug_log_chunk_init").getAsBoolean();
        if (config.has("debug_log_rtf")) DEBUG_LOG_RTF = config.get("debug_log_rtf").getAsBoolean();
        if (config.has("debug_log_structure")) DEBUG_LOG_STRUCTURE = config.get("debug_log_structure").getAsBoolean();
        if (config.has("debug_log_skip_elevated")) DEBUG_LOG_SKIP_ELEVATED = config.get("debug_log_skip_elevated").getAsBoolean();
        if (config.has("debug_log_structure_no_layers")) DEBUG_LOG_STRUCTURE_NO_LAYERS = config.get("debug_log_structure_no_layers").getAsBoolean();
        if (config.has("debug_log_second_pass")) DEBUG_LOG_SECOND_PASS = config.get("debug_log_second_pass").getAsBoolean();
        if (config.has("debug_log_foliage")) DEBUG_LOG_FOLIAGE = config.get("debug_log_foliage").getAsBoolean();
        if (config.has("debug_log_plants")) DEBUG_LOG_PLANTS = config.get("debug_log_plants").getAsBoolean();
        if (config.has("debug_log_rocks")) DEBUG_LOG_ROCKS = config.get("debug_log_rocks").getAsBoolean();
        if (config.has("debug_log_correction")) DEBUG_LOG_CORRECTION = config.get("debug_log_correction").getAsBoolean();
        if (config.has("debug_log_snow")) DEBUG_LOG_SNOW = config.get("debug_log_snow").getAsBoolean();
        if (config.has("debug_log_tree_soil")) DEBUG_LOG_TREE_SOIL = config.get("debug_log_tree_soil").getAsBoolean();
        if (config.has("debug_log_vanilla")) DEBUG_LOG_VANILLA = config.get("debug_log_vanilla").getAsBoolean();
        if (config.has("debug_log_nbt_trees")) DEBUG_LOG_NBT_TREES = config.get("debug_log_nbt_trees").getAsBoolean();
        if (config.has("underwater_layers")) UNDERWATER_LAYERS = config.get("underwater_layers").getAsBoolean();
        if (config.has("plant_injection")) PLANT_INJECTION = config.get("plant_injection").getAsBoolean();
        if (config.has("tree_injection")) TREE_INJECTION = config.get("tree_injection").getAsBoolean();
        if (config.has("structure_injection")) STRUCTURE_INJECTION = config.get("structure_injection").getAsBoolean();
        if (config.has("cross_chunk_structure_detection")) CROSS_CHUNK_STRUCTURE_DETECTION = config.get("cross_chunk_structure_detection").getAsBoolean();
        if (config.has("structure_injection_extra_bounds")) STRUCTURE_INJECTION_EXTRA_BOUNDS = config.get("structure_injection_extra_bounds").getAsBoolean();
        if (config.has("structure_injection_extra_bounds_distance")) STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE = config.get("structure_injection_extra_bounds_distance").getAsInt();
        if (config.has("enclosed_space_check")) ENCLOSED_SPACE_CHECK = config.get("enclosed_space_check").getAsBoolean();
        if (config.has("enclosed_space_height")) ENCLOSED_SPACE_HEIGHT = config.get("enclosed_space_height").getAsInt();
        if (config.has("structure_cleanup")) STRUCTURE_CLEANUP = config.get("structure_cleanup").getAsBoolean();
        if (config.has("structure_no_layers")) STRUCTURE_NO_LAYERS = config.get("structure_no_layers").getAsBoolean();
        if (config.has("structure_skip_elevated")) STRUCTURE_SKIP_ELEVATED = config.get("structure_skip_elevated").getAsBoolean();
        if (config.has("structure_replace_elevated")) STRUCTURE_REPLACE_ELEVATED = config.get("structure_replace_elevated").getAsBoolean();
        if (config.has("structure_skip_extra_cleanup")) STRUCTURE_SKIP_EXTRA_CLEANUP = config.get("structure_skip_extra_cleanup").getAsBoolean();
        if (config.has("structure_skip_extra_cleanup_distance")) STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE = config.get("structure_skip_extra_cleanup_distance").getAsInt();
        if (config.has("structure_replace_elevated_high")) STRUCTURE_REPLACE_ELEVATED_HIGH = config.get("structure_replace_elevated_high").getAsBoolean();
        if (config.has("structure_replace_elevated_high_max")) STRUCTURE_REPLACE_ELEVATED_HIGH_MAX = config.get("structure_replace_elevated_high_max").getAsInt();
        if (config.has("replace_dirt_path")) REPLACE_DIRT_PATH = config.get("replace_dirt_path").getAsBoolean();
        if (config.has("place_wet_sand")) PLACE_WET_SAND = config.get("place_wet_sand").getAsBoolean();
        if (config.has("replace_sea_grass")) REPLACE_SEA_GRASS = config.get("replace_sea_grass").getAsBoolean();
        if (config.has("improve_snowy_biomes")) IMPROVE_SNOWY_BIOMES = config.get("improve_snowy_biomes").getAsBoolean();
        if (config.has("break_snow_layers_to_mapped_layers")) BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS = config.get("break_snow_layers_to_mapped_layers").getAsBoolean();
        if (config.has("place_rocks")) PLACE_ROCKS = config.get("place_rocks").getAsBoolean();
        if (config.has("chance_to_place_rocks")) CHANCE_TO_PLACE_ROCKS = config.get("chance_to_place_rocks").getAsFloat();
        if (config.has("rock_density_mode")) {
            try {
                ROCK_DENSITY_MODE = RockDensityMode.valueOf(config.get("rock_density_mode").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                AronaLayersGen.LOGGER.warn("Invalid rock_density_mode in config, using default");
            }
        }
        if (config.has("rock_density_factor")) ROCK_DENSITY_FACTOR = config.get("rock_density_factor").getAsFloat();
        if (config.has("place_extra_foliage")) PLACE_EXTRA_FOLIAGE = config.get("place_extra_foliage").getAsBoolean();
        if (config.has("chance_to_place_extra_foliage")) CHANCE_TO_PLACE_EXTRA_FOLIAGE = config.get("chance_to_place_extra_foliage").getAsFloat();
        if (config.has("conquest_enhanced_extra_foliage")) CONQUEST_ENHANCED_EXTRA_FOLIAGE = config.get("conquest_enhanced_extra_foliage").getAsBoolean();
        if (config.has("conquest_enhanced_rocks")) CONQUEST_ENHANCED_ROCKS = config.get("conquest_enhanced_rocks").getAsBoolean();
        if (config.has("second_pass_cleanup")) SECOND_PASS_CLEANUP = config.get("second_pass_cleanup").getAsBoolean();
        if (config.has("cr_nbt_trees")) CR_NBT_TREES = config.get("cr_nbt_trees").getAsBoolean();
        if (config.has("cr_nbt_trees_vanilla_fallback")) CR_NBT_TREES_VANILLA_FALLBACK = config.get("cr_nbt_trees_vanilla_fallback").getAsBoolean();
    }

    private static void createExternalConfig() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }

            Path configPath = CONFIG_DIR.resolve(CONFIG_FILE);
            if (!Files.exists(configPath)) {
                InputStream inputStream = LayerConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
                if (inputStream != null) {
                    Files.copy(inputStream, configPath);
                    AronaLayersGen.LOGGER.info("Created external config: {}", configPath);
                }
            }
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to create external config", e);
        }
    }

    public static void reload() {
        loadConfig();
    }

    public static void save() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }

            Path configPath = CONFIG_DIR.resolve(CONFIG_FILE);

            JsonObject config = new JsonObject();
            config.addProperty("_comment", "Configuration for Arona Layers Generator. Edit this file to customize layer generation.");
            config.addProperty("layer_injection", LAYER_INJECTION);
            config.addProperty("rtf_layer_injection", RTF_LAYER_INJECTION);
            config.addProperty("vanilla_noise_router_layer_injection", VANILLA_NOISE_ROUTER_LAYER_INJECTION);
            config.addProperty("injection_mode", INJECTION_MODE.name());
            config.addProperty("skip_snowy_biomes", SKIP_SNOWY_BIOMES);
            config.addProperty("debug_logging", DEBUG_LOGGING);
            config.addProperty("debug_log_chunk_init", DEBUG_LOG_CHUNK_INIT);
            config.addProperty("debug_log_rtf", DEBUG_LOG_RTF);
            config.addProperty("debug_log_structure", DEBUG_LOG_STRUCTURE);
            config.addProperty("debug_log_skip_elevated", DEBUG_LOG_SKIP_ELEVATED);
            config.addProperty("debug_log_structure_no_layers", DEBUG_LOG_STRUCTURE_NO_LAYERS);
            config.addProperty("debug_log_second_pass", DEBUG_LOG_SECOND_PASS);
            config.addProperty("debug_log_foliage", DEBUG_LOG_FOLIAGE);
            config.addProperty("debug_log_plants", DEBUG_LOG_PLANTS);
            config.addProperty("debug_log_rocks", DEBUG_LOG_ROCKS);
            config.addProperty("debug_log_correction", DEBUG_LOG_CORRECTION);
            config.addProperty("debug_log_snow", DEBUG_LOG_SNOW);
            config.addProperty("debug_log_tree_soil", DEBUG_LOG_TREE_SOIL);
            config.addProperty("debug_log_vanilla", DEBUG_LOG_VANILLA);
            config.addProperty("underwater_layers", UNDERWATER_LAYERS);
            config.addProperty("plant_injection", PLANT_INJECTION);
            config.addProperty("tree_injection", TREE_INJECTION);
            config.addProperty("structure_injection", STRUCTURE_INJECTION);
            config.addProperty("cross_chunk_structure_detection", CROSS_CHUNK_STRUCTURE_DETECTION);
            config.addProperty("structure_injection_extra_bounds", STRUCTURE_INJECTION_EXTRA_BOUNDS);
            config.addProperty("structure_injection_extra_bounds_distance", STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE);
            config.addProperty("enclosed_space_check", ENCLOSED_SPACE_CHECK);
            config.addProperty("enclosed_space_height", ENCLOSED_SPACE_HEIGHT);
            config.addProperty("structure_cleanup", STRUCTURE_CLEANUP);
            config.addProperty("structure_no_layers", STRUCTURE_NO_LAYERS);
            config.addProperty("structure_skip_elevated", STRUCTURE_SKIP_ELEVATED);
            config.addProperty("structure_replace_elevated", STRUCTURE_REPLACE_ELEVATED);
            config.addProperty("structure_skip_extra_cleanup", STRUCTURE_SKIP_EXTRA_CLEANUP);
            config.addProperty("structure_skip_extra_cleanup_distance", STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE);
            config.addProperty("structure_replace_elevated_high", STRUCTURE_REPLACE_ELEVATED_HIGH);
            config.addProperty("structure_replace_elevated_high_max", STRUCTURE_REPLACE_ELEVATED_HIGH_MAX);
            config.addProperty("replace_dirt_path", REPLACE_DIRT_PATH);
            config.addProperty("place_wet_sand", PLACE_WET_SAND);
            config.addProperty("replace_sea_grass", REPLACE_SEA_GRASS);
            config.addProperty("improve_snowy_biomes", IMPROVE_SNOWY_BIOMES);
            config.addProperty("break_snow_layers_to_mapped_layers", BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS);
            config.addProperty("place_rocks", PLACE_ROCKS);
            config.addProperty("chance_to_place_rocks", CHANCE_TO_PLACE_ROCKS);
            config.addProperty("rock_density_mode", ROCK_DENSITY_MODE.name());
            config.addProperty("rock_density_factor", ROCK_DENSITY_FACTOR);
            config.addProperty("place_extra_foliage", PLACE_EXTRA_FOLIAGE);
            config.addProperty("chance_to_place_extra_foliage", CHANCE_TO_PLACE_EXTRA_FOLIAGE);
            config.addProperty("conquest_enhanced_extra_foliage", CONQUEST_ENHANCED_EXTRA_FOLIAGE);
            config.addProperty("conquest_enhanced_rocks", CONQUEST_ENHANCED_ROCKS);
            config.addProperty("second_pass_cleanup", SECOND_PASS_CLEANUP);
            config.addProperty("cr_nbt_trees", CR_NBT_TREES);
            config.addProperty("cr_nbt_trees_vanilla_fallback", CR_NBT_TREES_VANILLA_FALLBACK);
            config.addProperty("debug_log_nbt_trees", DEBUG_LOG_NBT_TREES);

            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.newBuilder().setPrettyPrinting().create().toJson(config, writer);
            }

            AronaLayersGen.LOGGER.info("Saved config to: {}", configPath);
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to save config", e);
            throw new RuntimeException("Failed to save config: " + e.getMessage());
        }
    }
}
