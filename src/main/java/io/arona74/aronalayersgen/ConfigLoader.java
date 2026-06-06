package io.arona74.aronalayersgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.JsonArray;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for loading mapping configurations from JSON files
 */
public class ConfigLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("aronalayersgen");

    /**
     * Load mappings from a JSON config file.
     * First tries to load from the config directory, then falls back to resources.
     * @param configFileName The name of the config file (e.g., "cr_block_mappings.json")
     * @return Map of vanilla block IDs to layer block IDs
     */
    public static Map<String, String> loadMappings(String configFileName) {
        Map<String, String> mappings = new HashMap<>();

        // First try to load from external config directory
        Path externalConfigPath = CONFIG_DIR.resolve(configFileName);
        if (Files.exists(externalConfigPath)) {
            try {
                mappings = loadMappingsFromFile(externalConfigPath);
                AronaLayersGen.LOGGER.info("Loaded mappings from external config: {}", externalConfigPath);
                return mappings;
            } catch (IOException e) {
                AronaLayersGen.LOGGER.error("Failed to load external config file: {}", externalConfigPath, e);
            }
        }

        // Fall back to resource file
        try {
            mappings = loadMappingsFromResource(configFileName);
            AronaLayersGen.LOGGER.info("Loaded mappings from resource: {}", configFileName);

            // Create external config file for user customization
            createExternalConfig(configFileName, mappings);
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to load resource config file: {}", configFileName, e);
        }

        return mappings;
    }

    private static Map<String, String> loadMappingsFromFile(Path filePath) throws IOException {
        try (Reader reader = Files.newBufferedReader(filePath)) {
            return parseMappingsJson(reader);
        }
    }

    private static Map<String, String> loadMappingsFromResource(String resourceName) throws IOException {
        InputStream inputStream = ConfigLoader.class.getClassLoader().getResourceAsStream(resourceName);
        if (inputStream == null) {
            throw new IOException("Resource not found: " + resourceName);
        }

        try (Reader reader = new InputStreamReader(inputStream)) {
            return parseMappingsJson(reader);
        }
    }

    private static Map<String, String> parseMappingsJson(Reader reader) {
        Map<String, String> mappings = new HashMap<>();

        JsonObject root = GSON.fromJson(reader, JsonObject.class);
        if (root.has("mappings")) {
            JsonObject mappingsObj = root.getAsJsonObject("mappings");
            for (String key : mappingsObj.keySet()) {
                var val = mappingsObj.get(key);
                mappings.put(key, val.isJsonNull() ? null : val.getAsString());
            }
        }

        return mappings;
    }

    private static void createExternalConfig(String configFileName, Map<String, String> defaultMappings) {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
                AronaLayersGen.LOGGER.info("Created config directory: {}", CONFIG_DIR);
            }

            Path configPath = CONFIG_DIR.resolve(configFileName);

            if (!Files.exists(configPath)) {
                JsonObject root = new JsonObject();
                root.addProperty("_comment",
                    "This file can be edited to customize mappings. Changes will be loaded on next startup.");

                JsonObject mappingsObj = new JsonObject();
                for (Map.Entry<String, String> entry : defaultMappings.entrySet()) {
                    mappingsObj.addProperty(entry.getKey(), entry.getValue());
                }
                root.add("mappings", mappingsObj);

                try (Writer writer = Files.newBufferedWriter(configPath)) {
                    GSON.toJson(root, writer);
                }

                AronaLayersGen.LOGGER.info("Created default config file: {}", configPath);
            }
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to create external config file", e);
        }
    }

    /**
     * Load a list of block IDs from a JSON file with a top-level "blocks" array.
     * First tries the external config directory, then falls back to resources.
     * @param configFileName The name of the config file (e.g., "structure_elevation_blocks.json")
     * @return List of block ID strings (e.g., "minecraft:grass_block")
     */
    public static List<String> loadBlockList(String configFileName) {
        // Try external config directory first
        Path externalConfigPath = CONFIG_DIR.resolve(configFileName);
        if (Files.exists(externalConfigPath)) {
            try (Reader reader = Files.newBufferedReader(externalConfigPath)) {
                List<String> result = parseBlockListJson(reader);
                AronaLayersGen.LOGGER.info("Loaded block list from external config: {}", externalConfigPath);
                return result;
            } catch (IOException e) {
                AronaLayersGen.LOGGER.error("Failed to load external config file: {}", externalConfigPath, e);
            }
        }

        // Fall back to resource
        try {
            InputStream inputStream = ConfigLoader.class.getClassLoader().getResourceAsStream(configFileName);
            if (inputStream == null) {
                AronaLayersGen.LOGGER.warn("Block list resource not found: {}", configFileName);
                return new ArrayList<>();
            }
            try (Reader reader = new InputStreamReader(inputStream)) {
                List<String> result = parseBlockListJson(reader);
                AronaLayersGen.LOGGER.info("Loaded block list from resource: {}", configFileName);
                // Copy to external config for user customization
                copyResourceToExternalConfig(configFileName);
                return result;
            }
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to load block list resource: {}", configFileName, e);
        }
        return new ArrayList<>();
    }

    private static List<String> parseBlockListJson(Reader reader) {
        List<String> list = new ArrayList<>();
        JsonObject root = GSON.fromJson(reader, JsonObject.class);
        if (root != null && root.has("blocks")) {
            JsonArray arr = root.getAsJsonArray("blocks");
            for (var elem : arr) {
                if (!elem.isJsonNull()) {
                    list.add(elem.getAsString());
                }
            }
        }
        return list;
    }

    private static void copyResourceToExternalConfig(String configFileName) {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            Path configPath = CONFIG_DIR.resolve(configFileName);
            if (!Files.exists(configPath)) {
                InputStream inputStream = ConfigLoader.class.getClassLoader().getResourceAsStream(configFileName);
                if (inputStream != null) {
                    Files.copy(inputStream, configPath);
                    AronaLayersGen.LOGGER.info("Created default config file: {}", configPath);
                }
            }
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to copy resource config to external: {}", configFileName, e);
        }
    }

    /**
     * Load a raw JsonObject from a config file.
     * First tries the external config directory, then falls back to resources.
     */
    public static JsonObject loadJsonObject(String configFileName) {
        Path externalConfigPath = CONFIG_DIR.resolve(configFileName);
        if (Files.exists(externalConfigPath)) {
            try (Reader reader = Files.newBufferedReader(externalConfigPath)) {
                AronaLayersGen.LOGGER.info("Loaded JSON from external config: {}", externalConfigPath);
                return GSON.fromJson(reader, JsonObject.class);
            } catch (IOException e) {
                AronaLayersGen.LOGGER.error("Failed to load external config file: {}", externalConfigPath, e);
            }
        }

        try {
            InputStream inputStream = ConfigLoader.class.getClassLoader().getResourceAsStream(configFileName);
            if (inputStream == null) {
                AronaLayersGen.LOGGER.warn("JSON resource not found: {}", configFileName);
                return new JsonObject();
            }
            try (Reader reader = new InputStreamReader(inputStream)) {
                AronaLayersGen.LOGGER.info("Loaded JSON from resource: {}", configFileName);
                copyResourceToExternalConfig(configFileName);
                return GSON.fromJson(reader, JsonObject.class);
            }
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to load JSON resource: {}", configFileName, e);
        }
        return new JsonObject();
    }

    public static void saveMappings(String configFileName, Map<String, String> mappings) throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
        }

        Path configPath = CONFIG_DIR.resolve(configFileName);

        JsonObject root = new JsonObject();
        root.addProperty("_comment",
            "This file can be edited to customize mappings. Changes will be loaded on next startup.");

        JsonObject mappingsObj = new JsonObject();
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            mappingsObj.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("mappings", mappingsObj);

        try (Writer writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(root, writer);
        }

        AronaLayersGen.LOGGER.info("Saved mappings to config file: {}", configPath);
    }
}
