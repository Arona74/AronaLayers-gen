package io.arona74.crlayers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simplified configuration for layer generation
 * Loaded from layer_config.json
 */
public class LayerConfig {
    private static final Gson GSON = new Gson();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("crlayers");
    private static final String CONFIG_FILE = "layer_config.json";

    /**
     * Generation mode
     * BASIC: Simple linear gradient from edge (7→6→5→4→3→2→1)
     * EXTENDED: Extended gradients with 2x distance:
     *   - Uses 2x MAX_LAYER_DISTANCE for spreading
     *   - Gradual gradients with repeated values (7,7,6,6,5,5,4,4,3,3,2,2,1,1)
     * EXTREME: Extreme gradients with 3x distance:
     *   - Uses 3x MAX_LAYER_DISTANCE for spreading
     *   - Very gradual gradients with triple repeated values
     */
    public static GenerationMode MODE = GenerationMode.BASIC;

    /**
     * Maximum distance from edge to place layers (in blocks)
     * BASIC mode: Layers fade over this distance (7→6→5→4→3→2→1→0)
     * EXTENDED mode: Automatically uses 2x this distance with repeated value gradients
     * EXTREME mode: Automatically uses 3x this distance with triple repeated value gradients
     *
     * Recommended: 5-10 blocks (EXTENDED will use 10-20 blocks, EXTREME will use 15-30 blocks)
     */
    public static int MAX_LAYER_DISTANCE = 7;

    /**
     * Minimum height difference to be considered an "edge"
     * 1 = Any height change creates an edge
     * 2 = Only 2+ block differences create edges
     *
     * Recommended: 1 block
     */
    public static int EDGE_HEIGHT_THRESHOLD = 1;

    public enum GenerationMode {
        BASIC,    // Linear gradient: 7→6→5→4→3→2→1
        EXTENDED, // Extended gradients with 2x distance: 7,7→6,6→5,5→4,4→3,3→2,2→1,1
        EXTREME   // Extreme gradients with 3x distance: 7,7,7→6,6,6→5,5,5→4,4,4→3,3,3→2,2,2→1,1,1
    }

    /**
     * Number of smoothing cycles to run after layer spreading
     * More cycles = smoother transitions between layers
     *
     * Recommended: 4-8 cycles
     */
    public static int SMOOTHING_CYCLES = 6;

    /**
     * How to round averages during smoothing
     */
    public static RoundingMode SMOOTHING_ROUNDING_MODE = RoundingMode.NEAREST;

    public enum RoundingMode {
        UP,      // Always round up (more aggressive layers)
        DOWN,    // Always round down (more conservative)
        NEAREST  // Round to nearest integer
    }

    /**
     * Smoothing priority mode
     * UP: Only apply smoothing if it increases the value (preserves extended/extreme gradients)
     * DOWN: Same as UP except near edge where it will use smoothed value even if it go down from existing value (traditional smoothing)
     */
    public static SmoothingPriority SMOOTHING_PRIORITY = SmoothingPriority.DOWN;

    public enum SmoothingPriority {
        UP,   // Always preserve higher gradient values (only smooth if it increases value)
        DOWN  // Traditional smoothing near edges and always up everywhere else
    }

    /**
     * Enable top generation mode
     * When enabled, the highest Y-level will use an inverse gradient to create small hills
     * between E blocks instead of the normal gradient spreading from H blocks.
     *
     * false = Normal generation (skip highest Y-level)
     * true = Generate inverse gradient hills on highest Y-level
     */
    public static boolean TOP_GENERATION = false;

    /**
     * Enable layer injection during terrain generation (vanilla)
     * When enabled, layers are automatically generated during chunk creation
     * using heightmap analysis for layer distribution.
     *
     * This is separate from the command-based generation system.
     * Requires game restart to take effect.
     *
     * false = Disabled (use commands for layer generation)
     * true = Inject layers during terrain generation
     */
    public static boolean LAYER_INJECTION = false;

    /**
     * Skip layer generation in snowy/cold biomes
     * When enabled, layers will not be placed in biomes where snow can fall.
     * This prevents layer blocks from interfering with natural snow placement.
     *
     * Requires game restart to take effect.
     *
     * true = Skip snowy biomes (default)
     * false = Place layers in all biomes
     */
    public static boolean SKIP_SNOWY_BIOMES = true;

    /**
     * Enable layer injection using ReTerraForged terrain data
     * When enabled and ReTerraForged is installed, layers are generated
     * using RTF's Cell data (gradient, sediment, erosion) for realistic
     * layer distribution that matches the terrain generation.
     *
     * This provides better results than vanilla injection as it uses
     * actual terrain generation data rather than post-hoc analysis.
     *
     * Requires ReTerraForged mod to be installed.
     * Requires game restart to take effect.
     *
     * false = Disabled
     * true = Use RTF terrain data for layer injection
     */
    public static boolean RTF_LAYER_INJECTION = false;

    /**
     * When to inject layers during world generation.
     *
     * CARVERS: Inject after carvers but before features/structures.
     *   - Layers placed before structures generate
     *   - Structures may overwrite layers or leave them floating inside buildings
     *   - Use structure_injection settings to mitigate
     *
     * POST_FEATURES: Inject after all features including structures.
     *   - Layers placed after structures are fully generated
     *   - Can see final terrain including structure modifications
     *   - Layers won't be placed where structures already exist
     *   - Best for clean structure interiors
     *
     * Requires RTF_LAYER_INJECTION to be enabled.
     * Requires game restart to take effect.
     */
    public static InjectionMode INJECTION_MODE = InjectionMode.CARVERS;

    public enum InjectionMode {
        CARVERS,       // Inject after carvers, before features (current behavior)
        POST_FEATURES  // Inject after all features including structures
    }

    /**
     * Enable debug logging for layer injection.
     * When enabled, detailed info about cell data, skip reasons, and
     * correction passes is logged. Useful for troubleshooting.
     *
     * false = Minimal logging (default)
     * true = Verbose debug logging
     */
    public static boolean DEBUG_LOGGING = false;

    /**
     * Enable layer placement on underwater surfaces.
     * When enabled, layers are placed on ocean/river floors with waterlogging
     * if the layer block supports it.
     *
     * false = Skip submerged terrain (default)
     * true = Place waterlogged layers underwater
     */
    public static boolean UNDERWATER_LAYERS = false;

    /**
     * Enable plant injection during terrain generation.
     * When enabled, layer blocks become transparent to plant placement checks,
     * allowing plants to grow on layered terrain. Vanilla plants above layers
     * are converted to their Conquest Reforged equivalents.
     *
     * Requires game restart to take effect (mixin-based).
     *
     * false = Plants cannot grow on layer blocks (default)
     * true = Plants see through layers and are converted to conquest variants
     */
    public static boolean PLANT_INJECTION = false;

    /**
     * Enable tree injection during terrain generation.
     * When enabled, layer blocks are treated as replaceable by tree features,
     * allowing trees to generate normally on layered terrain. The tree trunk
     * simply overwrites the layer block at its base position.
     *
     * Requires game restart to take effect (mixin-based).
     *
     * false = Trees cannot replace layer blocks (default)
     * true = Layer blocks are transparent to tree generation
     */
    public static boolean TREE_INJECTION = false;

    /**
     * Enable structure-aware layer injection.
     * When enabled, layers are not placed inside structure bounding boxes
     * (villages, pillager outposts, etc.), preventing structures from being
     * elevated by layer blocks underneath their foundations.
     *
     * false = Layers are placed everywhere including structure areas (default)
     * true = Skip layer placement inside structure bounds
     */
    public static boolean STRUCTURE_INJECTION = false;

    /**
     * Enable enclosed space detection for layer placement.
     * When enabled, layers are not placed if there's a solid block within
     * ENCLOSED_SPACE_HEIGHT blocks above the layer position. This detects
     * ceilings/roofs and prevents layers inside building interiors.
     *
     * Works during initial layer injection (before structures place blocks).
     * Requires STRUCTURE_INJECTION to be enabled.
     *
     * false = Only use bounding box detection (default)
     * true = Also check for solid blocks above (ceiling detection)
     */
    public static boolean ENCLOSED_SPACE_CHECK = false;

    /**
     * Maximum height to check for enclosed spaces (ceilings).
     * If a solid block is found within this many blocks above the layer position,
     * the layer will not be placed (assumes it's inside a building).
     *
     * Recommended: 4-6 blocks (typical room height)
     */
    public static int ENCLOSED_SPACE_HEIGHT = 5;

    /**
     * Enable post-structure layer cleanup.
     * When enabled, after structures place their blocks (FEATURES phase),
     * layers are removed if:
     * - There's a solid block directly above (structure placed a floor/ceiling)
     * - The surface block changed to a structure-typical block (cobblestone, planks, etc.)
     * - There's a ceiling within ENCLOSED_SPACE_HEIGHT blocks above
     *
     * This runs during the correction pass at WorldChunk creation.
     * Requires STRUCTURE_INJECTION to be enabled.
     *
     * false = Only correct mismatched layers (default)
     * true = Also remove layers inside detected structures
     */
    public static boolean STRUCTURE_CLEANUP = false;

    /**
     * Enable heightmap-based structure detection for layer placement.
     * Captures a heightmap snapshot before structures generate, then compares
     * with the post-structure heightmap. Any column where the heightmap changed
     * (structure modified terrain) will have layers removed.
     *
     * More precise than bounding-box detection (STRUCTURE_INJECTION) as it only
     * affects columns where structures actually modified blocks.
     *
     * Works in both CARVERS and POST_FEATURES injection modes.
     *
     * false = Disabled (default)
     * true = Remove layers where structures changed the heightmap
     */
    public static boolean STRUCTURE_NO_LAYERS = false;

    /**
     * When enabled, if a layer is placed on top of a dirt_path block and a
     * block mapping exists for it, the dirt_path block is also replaced with
     * the full-block equivalent of the layer (e.g. conquest:sandy_soil_slab
     * layer → conquest:sandy_soil full block underneath).
     *
     * false = Only place layer above dirt_path (default)
     * true = Also replace dirt_path with the conquest full block
     */
    public static boolean REPLACE_DIRT_PATH = false;

    /**
     * When enabled, vanilla seagrass and tall_seagrass are converted to their
     * Conquest Reforged equivalents (if present in plant_mappings.json).
     * Conquest seagrass blocks do not have the "layer" property, so they are
     * placed as-is without a layer value.
     *
     * Requires PLANT_INJECTION to be enabled.
     *
     * false = Skip seagrass conversion (default)
     * true = Convert seagrass to conquest variants
     */
    public static boolean REPLACE_SEA_GRASS = false;

    /**
     * Improve snowy biome layer placement.
     * When enabled (and skip_snowy_biomes is false), snowy biomes use vanilla
     * snow layers instead of the block_mappings blocks, with the original
     * un-reduced layer value. All other improvements (plant conversion, rocks,
     * foliage) still apply.
     *
     * This gives a similar look and feel to vanilla snow layers but with the
     * benefits of the mod's plant handling, rocks, foliage, etc.
     *
     * Requires skip_snowy_biomes to be false to take effect.
     *
     * false = Use normal block_mappings in snowy biomes (default)
     * true = Use snow layers with original layer value in snowy biomes
     */
    public static boolean IMPROVE_SNOWY_BIOMES = false;

    /**
     * Convert broken snow layers to mapped layer blocks.
     * When enabled, breaking a snow layer block replaces it with the
     * block_mappings layer block based on the surface block below,
     * with the layer value reduced by 1.
     *
     * Useful with improve_snowy_biomes: snow layers placed during generation
     * become CR layers when broken by players.
     *
     * false = Snow breaks normally (default)
     * true = Snow is replaced by mapped layer block on break
     */
    public static boolean BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS = false;

    /**
     * Enable rock placement above layers.
     * When enabled, rock blocks are placed above layers on mapped surface blocks
     * with a configurable chance. Rocks use Conquest's density property (1-4).
     *
     * Requires layer injection to be enabled.
     *
     * false = No rocks placed (default)
     * true = Place rocks above layers based on rock_mappings.json
     */
    public static boolean PLACE_ROCKS = false;

    /**
     * Chance to place a rock above a layer (0.0 to 1.0).
     * 0.0 = never, 1.0 = always, 0.1 = 10% chance per eligible position.
     *
     * Recommended: 0.05-0.2
     */
    public static float CHANCE_TO_PLACE_ROCKS = 0.1f;

    /**
     * How rock density (1-4) is determined.
     * RANDOM: Uniform random pick from 1-4
     * DECREASED: Biased toward lower density (smaller rocks more common),
     *            controlled by ROCK_DENSITY_FACTOR
     */
    public static RockDensityMode ROCK_DENSITY_MODE = RockDensityMode.DECREASED;

    public enum RockDensityMode {
        RANDOM,    // Uniform random density 1-4
        DECREASED  // Biased toward lower density (smaller rocks more common)
    }

    /**
     * Controls the bias strength in DECREASED density mode.
     * Each density level has 1/factor the probability of the previous one.
     * 1.0 = uniform (same as RANDOM), 4.0 = steep falloff.
     *
     * With factor 4.0: ~75% density 1, ~19% density 2, ~5% density 3, ~1% density 4
     * With factor 2.0: ~53% density 1, ~27% density 2, ~13% density 3, ~7% density 4
     *
     * Recommended: 3.0-5.0
     */
    public static float ROCK_DENSITY_FACTOR = 4.0f;

    /**
     * Enable extra foliage placement above layers.
     * When enabled, conquest foliage plants are scattered above layers on
     * mapped surface blocks where the position above is empty (air).
     *
     * Uses extra_foliage_mappings.json for surface-to-foliage mapping.
     *
     * false = No extra foliage (default)
     * true = Place foliage above layers based on mappings
     */
    public static boolean PLACE_EXTRA_FOLIAGE = false;

    /**
     * Chance to place extra foliage above a layer (0.0 to 1.0).
     * 0.0 = never, 1.0 = always, 0.1 = 10% chance per eligible position.
     *
     * Recommended: 0.05-0.3
     */
    public static float CHANCE_TO_PLACE_EXTRA_FOLIAGE = 0.1f;

    static {
        loadConfig();
    }

    /**
     * Load configuration from file
     */
    public static void loadConfig() {
        // Try external config first
        Path externalConfig = CONFIG_DIR.resolve(CONFIG_FILE);
        if (Files.exists(externalConfig)) {
            try {
                loadFromFile(externalConfig);
                CRLayers.LOGGER.info("Loaded layer config from: {}", externalConfig);
                return;
            } catch (IOException e) {
                CRLayers.LOGGER.error("Failed to load external config, using resource", e);
            }
        }

        // Fall back to resource
        try {
            loadFromResource();
            CRLayers.LOGGER.info("Loaded layer config from resource");
            // Create external config for user customization
            createExternalConfig();
        } catch (IOException e) {
            CRLayers.LOGGER.error("Failed to load config, using defaults", e);
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

        if (config.has("mode")) {
            try {
                MODE = GenerationMode.valueOf(config.get("mode").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                CRLayers.LOGGER.warn("Invalid mode in config, using default");
            }
        }

        if (config.has("max_layer_distance")) {
            MAX_LAYER_DISTANCE = config.get("max_layer_distance").getAsInt();
        }

        if (config.has("edge_height_threshold")) {
            EDGE_HEIGHT_THRESHOLD = config.get("edge_height_threshold").getAsInt();
        }

        if (config.has("smoothing_cycles")) {
            SMOOTHING_CYCLES = config.get("smoothing_cycles").getAsInt();
        }

        if (config.has("smoothing_rounding_mode")) {
            try {
                SMOOTHING_ROUNDING_MODE = RoundingMode.valueOf(
                    config.get("smoothing_rounding_mode").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                CRLayers.LOGGER.warn("Invalid rounding mode in config, using default");
            }
        }

        if (config.has("smoothing_priority")) {
            try {
                SMOOTHING_PRIORITY = SmoothingPriority.valueOf(
                    config.get("smoothing_priority").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                CRLayers.LOGGER.warn("Invalid smoothing priority in config, using default");
            }
        }

        if (config.has("top_generation")) {
            TOP_GENERATION = config.get("top_generation").getAsBoolean();
        }

        if (config.has("layer_injection")) {
            LAYER_INJECTION = config.get("layer_injection").getAsBoolean();
        }

        if (config.has("rtf_layer_injection")) {
            RTF_LAYER_INJECTION = config.get("rtf_layer_injection").getAsBoolean();
        }

        if (config.has("injection_mode")) {
            try {
                INJECTION_MODE = InjectionMode.valueOf(config.get("injection_mode").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                CRLayers.LOGGER.warn("Invalid injection_mode in config, using default");
            }
        }

        if (config.has("skip_snowy_biomes")) {
            SKIP_SNOWY_BIOMES = config.get("skip_snowy_biomes").getAsBoolean();
        }

        if (config.has("debug_logging")) {
            DEBUG_LOGGING = config.get("debug_logging").getAsBoolean();
        }

        if (config.has("underwater_layers")) {
            UNDERWATER_LAYERS = config.get("underwater_layers").getAsBoolean();
        }

        if (config.has("plant_injection")) {
            PLANT_INJECTION = config.get("plant_injection").getAsBoolean();
        }

        if (config.has("tree_injection")) {
            TREE_INJECTION = config.get("tree_injection").getAsBoolean();
        }

        if (config.has("structure_injection")) {
            STRUCTURE_INJECTION = config.get("structure_injection").getAsBoolean();
        }

        if (config.has("enclosed_space_check")) {
            ENCLOSED_SPACE_CHECK = config.get("enclosed_space_check").getAsBoolean();
        }

        if (config.has("enclosed_space_height")) {
            ENCLOSED_SPACE_HEIGHT = config.get("enclosed_space_height").getAsInt();
        }

        if (config.has("structure_cleanup")) {
            STRUCTURE_CLEANUP = config.get("structure_cleanup").getAsBoolean();
        }

        if (config.has("structure_no_layers")) {
            STRUCTURE_NO_LAYERS = config.get("structure_no_layers").getAsBoolean();
        }

        if (config.has("replace_dirt_path")) {
            REPLACE_DIRT_PATH = config.get("replace_dirt_path").getAsBoolean();
        }

        if (config.has("replace_sea_grass")) {
            REPLACE_SEA_GRASS = config.get("replace_sea_grass").getAsBoolean();
        }

        if (config.has("improve_snowy_biomes")) {
            IMPROVE_SNOWY_BIOMES = config.get("improve_snowy_biomes").getAsBoolean();
        }

        if (config.has("break_snow_layers_to_mapped_layers")) {
            BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS = config.get("break_snow_layers_to_mapped_layers").getAsBoolean();
        }

        if (config.has("place_rocks")) {
            PLACE_ROCKS = config.get("place_rocks").getAsBoolean();
        }

        if (config.has("chance_to_place_rocks")) {
            CHANCE_TO_PLACE_ROCKS = config.get("chance_to_place_rocks").getAsFloat();
        }

        if (config.has("rock_density_mode")) {
            try {
                ROCK_DENSITY_MODE = RockDensityMode.valueOf(config.get("rock_density_mode").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                CRLayers.LOGGER.warn("Invalid rock_density_mode in config, using default");
            }
        }

        if (config.has("rock_density_factor")) {
            ROCK_DENSITY_FACTOR = config.get("rock_density_factor").getAsFloat();
        }

        if (config.has("place_extra_foliage")) {
            PLACE_EXTRA_FOLIAGE = config.get("place_extra_foliage").getAsBoolean();
        }

        if (config.has("chance_to_place_extra_foliage")) {
            CHANCE_TO_PLACE_EXTRA_FOLIAGE = config.get("chance_to_place_extra_foliage").getAsFloat();
        }
    }

    private static void createExternalConfig() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }

            Path configPath = CONFIG_DIR.resolve(CONFIG_FILE);
            if (!Files.exists(configPath)) {
                // Copy from resource
                InputStream inputStream = LayerConfig.class.getClassLoader().getResourceAsStream(CONFIG_FILE);
                if (inputStream != null) {
                    Files.copy(inputStream, configPath);
                    CRLayers.LOGGER.info("Created external config: {}", configPath);
                }
            }
        } catch (IOException e) {
            CRLayers.LOGGER.error("Failed to create external config", e);
        }
    }

    /**
     * Reload configuration from file
     */
    public static void reload() {
        loadConfig();
    }

    /**
     * Save current configuration to external config file
     */
    public static void save() {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }

            Path configPath = CONFIG_DIR.resolve(CONFIG_FILE);

            JsonObject config = new JsonObject();
            config.addProperty("_comment", "Configuration for CR Layers generation behavior. Edit this file to customize layer generation.");
            config.addProperty("_comment_mode", "Generation mode: BASIC (linear gradients 7→6→5→4→3→2→1), EXTENDED (2x distance with repeated values 7,7,6,6,5,5,4,4,3,3,2,2,1,1), or EXTREME (3x distance with triple repeated values 7,7,7→6,6,6→5,5,5→4,4,4→3,3,3→2,2,2→1,1,1)");
            config.addProperty("mode", MODE.name());
            config.addProperty("_comment_max_layer_distance", "Maximum distance from edges to place layers. EXTENDED mode automatically uses 2x this value, EXTREME mode uses 3x. Recommended: 5-10");
            config.addProperty("max_layer_distance", MAX_LAYER_DISTANCE);
            config.addProperty("_comment_edge_height_threshold", "Minimum height difference to detect an edge. 1 = any height change, 2 = only 2+ block differences. Recommended: 1");
            config.addProperty("edge_height_threshold", EDGE_HEIGHT_THRESHOLD);
            config.addProperty("_comment_smoothing_cycles", "Number of smoothing passes after layer spreading. More cycles = smoother transitions. Recommended: 4-8");
            config.addProperty("smoothing_cycles", SMOOTHING_CYCLES);
            config.addProperty("_comment_smoothing_rounding_mode", "How to round averages during smoothing: UP (aggressive), DOWN (conservative), NEAREST (balanced)");
            config.addProperty("smoothing_rounding_mode", SMOOTHING_ROUNDING_MODE.name());
            config.addProperty("_comment_smoothing_priority", "Smoothing priority: UP (preserve higher gradient values), DOWN (traditional smoothing near edges)");
            config.addProperty("smoothing_priority", SMOOTHING_PRIORITY.name());
            config.addProperty("_comment_top_generation", "Enable top generation mode: Creates inverse gradient hills on the highest Y-level between E blocks. false = skip highest Y-level (default), true = generate hills on top");
            config.addProperty("top_generation", TOP_GENERATION);
            config.addProperty("_comment_layer_injection", "Enable automatic layer injection during terrain generation. Uses heightmap analysis for layer distribution. Requires restart to take effect.");
            config.addProperty("layer_injection", LAYER_INJECTION);
            config.addProperty("_comment_rtf_layer_injection", "Enable layer injection using ReTerraForged terrain data. Requires ReTerraForged mod.");
            config.addProperty("rtf_layer_injection", RTF_LAYER_INJECTION);
            config.addProperty("_comment_injection_mode", "When to inject layers: CARVERS (before structures, may need cleanup) or POST_FEATURES (after structures, cleaner result). Requires restart.");
            config.addProperty("injection_mode", INJECTION_MODE.name());
            config.addProperty("_comment_skip_snowy_biomes", "Skip layer generation in snowy/cold biomes to avoid interfering with natural snow. Default: true");
            config.addProperty("skip_snowy_biomes", SKIP_SNOWY_BIOMES);
            config.addProperty("_comment_debug_logging", "Enable verbose debug logging for layer injection. Useful for troubleshooting. Default: false");
            config.addProperty("debug_logging", DEBUG_LOGGING);
            config.addProperty("_comment_underwater_layers", "Enable layer placement on underwater surfaces (ocean/river floors). Layer blocks must support waterlogging. Default: false");
            config.addProperty("underwater_layers", UNDERWATER_LAYERS);
            config.addProperty("_comment_plant_injection", "Enable plant injection: layer blocks become transparent to plant placement, and vanilla plants above layers are converted to Conquest variants. Requires restart.");
            config.addProperty("plant_injection", PLANT_INJECTION);
            config.addProperty("_comment_tree_injection", "Enable tree injection: layer blocks become transparent to tree feature generation, allowing trees to grow on layered terrain. Requires restart.");
            config.addProperty("tree_injection", TREE_INJECTION);
            config.addProperty("_comment_structure_injection", "Enable structure-aware layer injection: layers are not placed inside structure bounds (villages, etc.), preventing elevation issues. Default: false");
            config.addProperty("structure_injection", STRUCTURE_INJECTION);
            config.addProperty("_comment_enclosed_space_check", "Enable enclosed space detection: skip layers if there's a solid block (ceiling) within enclosed_space_height blocks above. Requires structure_injection. Default: false");
            config.addProperty("enclosed_space_check", ENCLOSED_SPACE_CHECK);
            config.addProperty("_comment_enclosed_space_height", "Maximum height to check for ceilings above layer positions. Recommended: 4-6 blocks");
            config.addProperty("enclosed_space_height", ENCLOSED_SPACE_HEIGHT);
            config.addProperty("_comment_structure_cleanup", "Enable post-structure cleanup: remove layers after structures place their blocks if ceiling detected or surface changed to structure blocks. Requires structure_injection. Default: false");
            config.addProperty("structure_cleanup", STRUCTURE_CLEANUP);
            config.addProperty("_comment_structure_no_layers", "Enable heightmap-based structure detection: captures pre-structure heightmap and removes layers where structures changed terrain. More precise than bounding-box detection. Default: false");
            config.addProperty("structure_no_layers", STRUCTURE_NO_LAYERS);
            config.addProperty("_comment_replace_dirt_path", "When placing layers on dirt_path, also replace the dirt_path block with the full-block equivalent of the layer (e.g. conquest:sandy_soil). Default: false");
            config.addProperty("replace_dirt_path", REPLACE_DIRT_PATH);
            config.addProperty("_comment_replace_sea_grass", "Convert vanilla seagrass/tall_seagrass to Conquest equivalents. Conquest seagrass lacks the layer property, so they are placed without a layer value. Requires plant_injection. Default: false");
            config.addProperty("replace_sea_grass", REPLACE_SEA_GRASS);
            config.addProperty("_comment_improve_snowy_biomes", "Use vanilla snow layers in snowy biomes with original un-reduced layer value. Plant conversion, rocks, foliage still apply. Requires skip_snowy_biomes=false. Default: false");
            config.addProperty("improve_snowy_biomes", IMPROVE_SNOWY_BIOMES);
            config.addProperty("_comment_break_snow_layers_to_mapped_layers", "When breaking a snow layer, replace it with the mapped layer block (from block_mappings) based on the block below, with layer value reduced by 1. Default: false");
            config.addProperty("break_snow_layers_to_mapped_layers", BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS);
            config.addProperty("_comment_place_rocks", "Place rock blocks above layers on mapped surface blocks. Uses rock_mappings.json for surface-to-rock mapping. Default: false");
            config.addProperty("place_rocks", PLACE_ROCKS);
            config.addProperty("_comment_chance_to_place_rocks", "Chance (0.0-1.0) to place a rock above an eligible layer. 0.1 = 10% chance. Recommended: 0.05-0.2");
            config.addProperty("chance_to_place_rocks", CHANCE_TO_PLACE_ROCKS);
            config.addProperty("_comment_rock_density_mode", "How rock density (1-4) is chosen: RANDOM (uniform) or DECREASED (biased toward smaller rocks, controlled by rock_density_factor)");
            config.addProperty("rock_density_mode", ROCK_DENSITY_MODE.name());
            config.addProperty("_comment_rock_density_factor", "Bias strength for DECREASED mode. Each density has 1/factor the chance of the previous. 1.0 = uniform, 4.0 = ~75/19/5/1% distribution. Recommended: 3.0-5.0");
            config.addProperty("rock_density_factor", ROCK_DENSITY_FACTOR);
            config.addProperty("_comment_place_extra_foliage", "Place conquest foliage above layers on mapped surface blocks where the position is empty. Uses extra_foliage_mappings.json. Default: false");
            config.addProperty("place_extra_foliage", PLACE_EXTRA_FOLIAGE);
            config.addProperty("_comment_chance_to_place_extra_foliage", "Chance (0.0-1.0) to place extra foliage above an eligible layer. 0.1 = 10% chance. Recommended: 0.05-0.3");
            config.addProperty("chance_to_place_extra_foliage", CHANCE_TO_PLACE_EXTRA_FOLIAGE);

            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.newBuilder().setPrettyPrinting().create().toJson(config, writer);
            }

            CRLayers.LOGGER.info("Saved config to: {}", configPath);
        } catch (IOException e) {
            CRLayers.LOGGER.error("Failed to save config", e);
            throw new RuntimeException("Failed to save config: " + e.getMessage());
        }
    }
}