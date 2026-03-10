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

    public static boolean ENCLOSED_SPACE_CHECK = false;
    public static int ENCLOSED_SPACE_HEIGHT = 5;
    public static boolean STRUCTURE_CLEANUP = false;

    /**
     * Enable heightmap-based structure detection.
     * Removes layers where structures changed the heightmap.
     */
    public static boolean STRUCTURE_NO_LAYERS = false;

    /**
     * When placing layers on dirt_path, also replace the dirt_path block
     * with the full-block equivalent of the layer.
     */
    public static boolean REPLACE_DIRT_PATH = false;

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
        if (config.has("injection_mode")) {
            try {
                INJECTION_MODE = InjectionMode.valueOf(config.get("injection_mode").getAsString().toUpperCase());
            } catch (IllegalArgumentException e) {
                AronaLayersGen.LOGGER.warn("Invalid injection_mode in config, using default");
            }
        }
        if (config.has("skip_snowy_biomes")) SKIP_SNOWY_BIOMES = config.get("skip_snowy_biomes").getAsBoolean();
        if (config.has("debug_logging")) DEBUG_LOGGING = config.get("debug_logging").getAsBoolean();
        if (config.has("underwater_layers")) UNDERWATER_LAYERS = config.get("underwater_layers").getAsBoolean();
        if (config.has("plant_injection")) PLANT_INJECTION = config.get("plant_injection").getAsBoolean();
        if (config.has("tree_injection")) TREE_INJECTION = config.get("tree_injection").getAsBoolean();
        if (config.has("structure_injection")) STRUCTURE_INJECTION = config.get("structure_injection").getAsBoolean();
        if (config.has("enclosed_space_check")) ENCLOSED_SPACE_CHECK = config.get("enclosed_space_check").getAsBoolean();
        if (config.has("enclosed_space_height")) ENCLOSED_SPACE_HEIGHT = config.get("enclosed_space_height").getAsInt();
        if (config.has("structure_cleanup")) STRUCTURE_CLEANUP = config.get("structure_cleanup").getAsBoolean();
        if (config.has("structure_no_layers")) STRUCTURE_NO_LAYERS = config.get("structure_no_layers").getAsBoolean();
        if (config.has("replace_dirt_path")) REPLACE_DIRT_PATH = config.get("replace_dirt_path").getAsBoolean();
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
            config.addProperty("injection_mode", INJECTION_MODE.name());
            config.addProperty("skip_snowy_biomes", SKIP_SNOWY_BIOMES);
            config.addProperty("debug_logging", DEBUG_LOGGING);
            config.addProperty("underwater_layers", UNDERWATER_LAYERS);
            config.addProperty("plant_injection", PLANT_INJECTION);
            config.addProperty("tree_injection", TREE_INJECTION);
            config.addProperty("structure_injection", STRUCTURE_INJECTION);
            config.addProperty("enclosed_space_check", ENCLOSED_SPACE_CHECK);
            config.addProperty("enclosed_space_height", ENCLOSED_SPACE_HEIGHT);
            config.addProperty("structure_cleanup", STRUCTURE_CLEANUP);
            config.addProperty("structure_no_layers", STRUCTURE_NO_LAYERS);
            config.addProperty("replace_dirt_path", REPLACE_DIRT_PATH);
            config.addProperty("replace_sea_grass", REPLACE_SEA_GRASS);
            config.addProperty("improve_snowy_biomes", IMPROVE_SNOWY_BIOMES);
            config.addProperty("break_snow_layers_to_mapped_layers", BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS);
            config.addProperty("place_rocks", PLACE_ROCKS);
            config.addProperty("chance_to_place_rocks", CHANCE_TO_PLACE_ROCKS);
            config.addProperty("rock_density_mode", ROCK_DENSITY_MODE.name());
            config.addProperty("rock_density_factor", ROCK_DENSITY_FACTOR);
            config.addProperty("place_extra_foliage", PLACE_EXTRA_FOLIAGE);
            config.addProperty("chance_to_place_extra_foliage", CHANCE_TO_PLACE_EXTRA_FOLIAGE);

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
