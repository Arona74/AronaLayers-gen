package io.arona74.aronalayersgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.arona74.aronalayersgen.config.ConfigOption;
import io.arona74.aronalayersgen.config.ConfigOption.Apply;
import io.arona74.aronalayersgen.config.ConfigOption.Side;
import io.arona74.aronalayersgen.config.ConfigTab;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.arona74.aronalayersgen.config.ConfigOption.biomeList;
import static io.arona74.aronalayersgen.config.ConfigOption.blockList;
import static io.arona74.aronalayersgen.config.ConfigOption.bool;
import static io.arona74.aronalayersgen.config.ConfigOption.choice;
import static io.arona74.aronalayersgen.config.ConfigOption.decimal;
import static io.arona74.aronalayersgen.config.ConfigOption.integer;

/**
 * Configuration for layer generation, stored in config/aronalayersgen/layer_config.json.
 *
 * <p>Values live in the static fields below, which worldgen reads directly. Each field is bound to a
 * {@link ConfigOption} in the Options section, which holds its default, range and screen placement;
 * the fields have no initializers of their own because {@link #loadConfig()} sets every one of them.
 */
public class LayerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("aronalayersgen");
    private static final String CONFIG_FILE = "layer_config.json";
    private static final String LANG_FILE = "assets/aronalayersgen/lang/en_us.json";

    /**
     * Master switch for layer generation. Where the layer counts come from is not configured:
     * each dimension's backend (Tellus, TerraForged, the vanilla density field, or none) is
     * detected from its own chunk generator, see injection.LayerBackend.
     */
    public static boolean LAYER_INJECTION;

    /**
     * Skip layer generation in snowy/cold biomes.
     * Prevents layer blocks from interfering with natural snow placement.
     */
    public static boolean SKIP_SNOWY_BIOMES;

    /**
     * Reduce the Tellus layer count by one (round(depth*8) - 1) instead of using the raw value.
     * The reduction is a legacy aesthetic tweak inherited from RTF; leaving it OFF makes the
     * layer height track the true sub-block elevation more closely and removes the ~19% of
     * columns that would otherwise drop to zero layers on near-integer elevations.
     *
     * Applied uniformly and independently of snow: snow columns always use the un-reduced value
     * (as before), so setting this true restores the old behaviour exactly — non-snow columns
     * reduced, snow columns not. Only affects the Tellus backend.
     */
    public static boolean TELLUS_REDUCE_LAYER_COUNT;

    /**
     * Reduce the fractional-surface layer count by one, as the RTF path does.
     *
     * <p>True keeps the established look: the shallowest columns stay bare — {@code t < 3/16},
     * about 19% of them — and the rendered surface sits a constant ~0.875 above the true one.
     * False layers every column with any fill at all, giving thicker layers and a clean 1.0
     * offset, at the cost of almost no bare ground.
     *
     * <p>Independent of the cap that stops a layer filling its whole block, so turning this off
     * does not reintroduce block-height layers. Only affects the fractional-surface backend.
     */
    public static boolean FRACTIONAL_SURFACE_REDUCE_LAYER_COUNT;

    /**
     * Smallest vertical density change per block at which the surface position is trusted.
     *
     * <p>The reconstructed field carries a small absolute error, around 0.02. Where density falls
     * steeply through the surface — beaches, plains, ordinary slopes — that error moves the
     * crossing by a hundredth of a block and the fractions are exact. On high peaks the field
     * flattens out near the top of the terrain envelope: a measured jagged-peaks column changed by
     * only 0.019 per block, so the same error moved the crossing a whole block and 251 of 256
     * columns were reported as full. Below this threshold the column is left to vanilla rather
     * than given a fabricated depth.
     *
     * <p>Only consulted where the field and the placed world already disagree, so raising or
     * lowering it cannot affect terrain that produces a valid crossing. Only affects the
     * fractional-surface backend.
     */
    public static double FRACTIONAL_SURFACE_MIN_GRADIENT;

    /**
     * Fraction of a chunk's columns that may disagree with the density field before the whole
     * chunk is left to vanilla. Set to 1.0 to never skip.
     *
     * <p>Measured rates: a beach chunk 0%, plains chunks between 23% and 42%, a jagged-peaks
     * chunk 61%. So this separates peaks from ordinary ground by roughly eight points, not by the
     * wide margin the first beach-versus-peak comparison suggested — an earlier default of 0.25
     * cut straight through normal plains and stripped their layers.
     *
     * <p>0.5 is therefore a judgement call, not a boundary the data draws for us: it clears every
     * plains chunk measured and still skips peaks, but a plains chunk above 50% would lose its
     * layers. Raise toward 1.0 if layers go missing on ordinary terrain; the cost is that peaks
     * get a uniform full-thickness slab instead of vanilla ground.
     *
     * <p>Nothing here models the underlying cause, which remains unidentified: reproducing
     * vanilla's marker interpolation changed the peak numbers not at all, its quart-resolution
     * caching is a no-op at the sampled corners, and vertical gradient runs the wrong way
     * (beaches sit lower than peaks). Only affects the fractional-surface backend.
     */
    public static double FRACTIONAL_SURFACE_MAX_DISAGREEMENT;

    /**
     * Interpolate each of the router's Interpolated markers separately, as NoiseChunk does,
     * instead of interpolating the whole density tree as one unit.
     *
     * <p>A measured world carries five Interpolated markers: vanilla interpolates five separate
     * sub-expressions and evaluates everything combining them per block. Where those combining
     * operations are nonlinear — clamps, squeeze, multiplication, the slides —
     * {@code interp(f(a,b))} is not {@code f(interp(a), interp(b))}, and whole-tree interpolation
     * placed the surface one to three blocks above the world on jagged peaks while agreeing
     * closely on gentle terrain.
     *
     * <p>Costs a tree rebuild per chunk and eight sub-expression evaluations per query, memoised
     * per chunk. Off by default until it has been measured against real terrain; when it works,
     * the overfull, block-mismatch and minimum-gradient guards all exist only to paper over the
     * error it removes. Only affects the fractional-surface backend.
     */
    public static boolean FRACTIONAL_SURFACE_EXACT_INTERPOLATION;

    /**
     * When to inject layers during world generation.
     * CARVERS: Inject after carvers but before features/structures.
     * POST_FEATURES: Inject after all features including structures.
     */
    public static InjectionMode INJECTION_MODE;

    public enum InjectionMode {
        CARVERS,
        POST_FEATURES
    }

    public static boolean DEBUG_LOGGING;

    // Per-category debug flags — only active when DEBUG_LOGGING=true.
    public static boolean DEBUG_LOG_CHUNK_INIT;
    public static boolean DEBUG_LOG_RTF;
    public static boolean DEBUG_LOG_STRUCTURE;
    public static boolean DEBUG_LOG_SKIP_ELEVATED;
    public static boolean DEBUG_LOG_STRUCTURE_NO_LAYERS;
    public static boolean DEBUG_LOG_SECOND_PASS;
    public static boolean DEBUG_LOG_FOLIAGE;
    public static boolean DEBUG_LOG_PLANTS;
    public static boolean DEBUG_LOG_ROCKS;
    public static boolean DEBUG_LOG_CORRECTION;
    public static boolean DEBUG_LOG_SNOW;
    public static boolean DEBUG_LOG_TREE_SOIL;
    public static boolean DEBUG_LOG_VANILLA;
    public static boolean DEBUG_LOG_NBT_TREES;
    public static boolean DEBUG_LOG_TELLUS;

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
    public static boolean logTellus()            { return DEBUG_LOGGING && DEBUG_LOG_TELLUS; }

    /**
     * Enable layer placement on underwater surfaces.
     * Layer blocks must support waterlogging.
     */
    public static boolean UNDERWATER_LAYERS;

    /**
     * Enable plant injection during terrain generation.
     * Requires Conquest Reforged. Layer blocks become transparent to plant placement,
     * and vanilla plants above layers are converted to CR equivalents.
     * Requires game restart to take effect.
     */
    public static boolean PLANT_INJECTION;

    /**
     * Enable tree injection during terrain generation.
     * Layer blocks are treated as replaceable by tree features.
     * Requires game restart to take effect.
     */
    public static boolean TREE_INJECTION;

    /**
     * Trees, huge mushrooms and chorus plants stand on top of the layer instead of in it:
     * generated ones start one block higher, and their trunk or stem is drawn down to the layer.
     * false = the old behaviour, where the soil layer under a tree turns into dirt and chorus
     * columns are left unlayered.
     */
    public static boolean TREES_ON_LAYERS;

    /** True when {@link #TREES_ON_LAYERS} is on and this version's client has the stem models. */
    public static boolean treesOnLayers() {
        return Compat.STEM_MODELS && TREES_ON_LAYERS;
    }

    /**
     * Fences, walls, panes and iron bars placed on a layer are drawn down to it, and
     * their outline and collision reach down with them, so nothing slips under a fence on low ground.
     */
    public static boolean POSTS_ON_LAYERS;

    /** True when {@link #POSTS_ON_LAYERS} is on and this version's client has the post models. */
    public static boolean postsOnLayers() {
        return Compat.POST_MODELS && POSTS_ON_LAYERS;
    }

    /** How a post on a layer reaches it. */
    public static PostMode POSTS_MODE;

    public enum PostMode {
        /** Drawn down to the layer; its top stays level with its neighbours, and a step is bridged. */
        EXTEND,
        /** Lowered onto the layer whole, like a grounded decoration; its open ends are closed. */
        LOWER
    }

    /**
     * A lowered post reaches back up to a block standing on it (a slab, a lantern), closing the gap
     * lowering opens under that block. false = the gap stays.
     */
    public static boolean LOWERED_POSTS_REACH_UP;

    /** True when posts on layers are lowered rather than drawn down (see {@link PostMode}). */
    public static boolean postsLowered() {
        return postsOnLayers() && POSTS_MODE == PostMode.LOWER;
    }

    /**
     * Enable structure-aware layer injection.
     * Layers are not placed inside structure bounding boxes.
     */
    public static boolean STRUCTURE_INJECTION;

    /**
     * Expand structure bounding boxes by extra_bounds_distance blocks in XZ
     * when checking if a position is inside a structure. Adds a buffer zone around
     * structure pieces where layers are also suppressed.
     * Requires STRUCTURE_INJECTION.
     */
    public static boolean STRUCTURE_INJECTION_EXTRA_BOUNDS;

    /**
     * Radius (in blocks) to expand each structure bounding box outward in XZ
     * when STRUCTURE_INJECTION_EXTRA_BOUNDS is enabled.
     */
    public static int STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE;

    /**
     * Enable cross-chunk structure detection.
     * When enabled, also checks structure references in neighboring chunks to catch
     * structures that start outside the current chunk but extend into it.
     * May cause hangs during heavy pre-generation (e.g. Chunky). Disable if you
     * experience server tick timeouts during worldgen. Requires STRUCTURE_INJECTION.
     */
    public static boolean CROSS_CHUNK_STRUCTURE_DETECTION;

    public static boolean ENCLOSED_SPACE_CHECK;
    public static int ENCLOSED_SPACE_HEIGHT;
    public static boolean STRUCTURE_CLEANUP;

    /**
     * Enable structure footprint detection.
     * In addition to piece-level bounding boxes (STRUCTURE_INJECTION), also suppresses layers
     * within the overall XZ footprint of each StructureStart (the hull of all its pieces).
     * This catches the cleared ground, plazas, and paths between buildings that fall between
     * individual piece boxes. Trees are not affected (they have no StructureStart).
     * Requires STRUCTURE_INJECTION.
     */
    public static boolean STRUCTURE_FOOTPRINT_CHECK;

    /**
     * How many blocks below the structure start's minimum Y to still suppress layers.
     * Handles terrain that was carved into (paths dug into a hillside, excavated basements).
     * Requires STRUCTURE_FOOTPRINT_CHECK.
     */
    public static int STRUCTURE_FOOTPRINT_BELOW_MARGIN;

    /**
     * How many blocks above the structure start's maximum Y to still suppress layers.
     * RTF terrain within a structure footprint can sit a few blocks above the highest
     * roof piece; without this margin those columns escape detection. Keep small to
     * avoid suppressing surface layers that are legitimately above an underground
     * structure (e.g. a mineshaft at Y=50 vs. surface at Y=65).
     * Requires STRUCTURE_FOOTPRINT_CHECK.
     */
    public static int STRUCTURE_FOOTPRINT_ABOVE_MARGIN;

    /**
     * At injection time, compare the current surface Y against RTF's noise-derived base Y.
     * If they differ by more than CONSERVATIVE_SURFACE_TOLERANCE blocks, skip the layer.
     * Detects structure modification (terrain raised or lowered) per column without relying
     * on bounding boxes. RTF-only (requires rtfExpectedBaseY).
     */
    public static boolean CONSERVATIVE_SURFACE_HEIGHTMAP;

    /**
     * Maximum upward deviation (in blocks) allowed between the actual surface Y and RTF's
     * expected base Y. 1 tolerates natural snow (+1 block) while still catching structures
     * that raised the terrain by 2+.
     */
    public static int CONSERVATIVE_SURFACE_TOLERANCE_UP;

    /**
     * Maximum downward deviation (in blocks) allowed between the actual surface Y and RTF's
     * expected base Y. 0 is strict (natural terrain sits exactly at RTF's base Y); increase
     * if natural terrain variation causes false positives on downward slopes.
     */
    public static int CONSERVATIVE_SURFACE_TOLERANCE_DOWN;

    /**
     * When conservative_surface_heightmap detects a modified column (deviation beyond tolerance),
     * place a layer with a fixed value instead of skipping entirely.
     */
    public static boolean CONSERVATIVE_SURFACE_FALLBACK;

    /**
     * Layer value (1-8) to use when the surface is above RTF's expected base (terrain raised by structure), set to 0 to skip.
     */
    public static int CONSERVATIVE_SURFACE_FALLBACK_VALUE_UP;

    /**
     * Layer value (1-8) to use when the surface is below RTF's expected base (terrain lowered by structure), set to 0 to skip.
     */
    public static int CONSERVATIVE_SURFACE_FALLBACK_VALUE_DOWN;

    /**
     * Enable heightmap-based structure detection.
     * Removes layers where structures changed the heightmap.
     */
    public static boolean STRUCTURE_NO_LAYERS;

    /**
     * Skip layer placement at positions where a structure elevated the terrain above the
     * original surface. Compares the post-structure surface against a pre-structure
     * heightmap snapshot captured during the carvers phase.
     * Only effective with injection_mode=POST_FEATURES.
     */
    public static boolean STRUCTURE_SKIP_ELEVATED;

    /**
     * When STRUCTURE_SKIP_ELEVATED is true: remove layer blocks within
     * STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE of any position where elevation was detected.
     * Cleans up layers that were placed on or near the raised terrain around structures.
     * Requires STRUCTURE_SKIP_ELEVATED.
     */
    public static boolean STRUCTURE_SKIP_EXTRA_CLEANUP;

    /**
     * Radius (in blocks, Chebyshev distance) around each detected elevated position
     * within which layer blocks are removed. Only used when STRUCTURE_SKIP_EXTRA_CLEANUP is true.
     */
    public static int STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE;

    /**
     * When STRUCTURE_SKIP_ELEVATED is true: if a structure placed a mapped block exactly
     * at the original layer target position (1-block elevation), replace that block with
     * the layer instead of skipping. Has no effect for elevations of 2+ blocks.
     */
    public static boolean STRUCTURE_REPLACE_ELEVATED;

    /**
     * When STRUCTURE_SKIP_ELEVATED is true: also replace elevated terrain that is more
     * than 1 block above the original surface (up to STRUCTURE_REPLACE_ELEVATED_HIGH_MAX).
     * Has no effect when STRUCTURE_REPLACE_ELEVATED is false.
     */
    public static boolean STRUCTURE_REPLACE_ELEVATED_HIGH;

    /**
     * Maximum elevation delta (in blocks) for which STRUCTURE_REPLACE_ELEVATED_HIGH applies.
     * Positions elevated by more than this many blocks above the natural surface are skipped.
     */
    public static int STRUCTURE_REPLACE_ELEVATED_HIGH_MAX;

    /**
     * When placing layers on dirt_path, also replace the dirt_path block
     * with the full-block equivalent of the layer.
     */
    public static boolean REPLACE_DIRT_PATH;

    /**
     * When placing a sand layer (conquest:sand_layer), substitute conquest:wet_sand_layer
     * if the layer will be waterlogged or is positioned below Y=63 (sea level).
     */
    public static boolean PLACE_WET_SAND;

    /**
     * Convert vanilla seagrass/tall_seagrass to Conquest equivalents.
     * Requires PLANT_INJECTION and Conquest Reforged.
     */
    public static boolean REPLACE_SEA_GRASS;

    /**
     * Use vanilla snow layers in snowy biomes with original un-reduced layer value.
     * Requires skip_snowy_biomes=false.
     */
    public static boolean IMPROVE_SNOWY_BIOMES;

    /** Which biomes Super Snowy Biomes covers. */
    public static SuperSnowyMode SUPER_SNOWY_MODE;

    public enum SuperSnowyMode {
        /** Every biome cold enough to snow at the column (and wherever vanilla already laid snow). */
        ALL_SNOWY_BIOMES,
        /** Only the biomes in {@link #SUPER_SNOWY_BIOMES}, whatever their temperature. */
        CONTROLLED_BY_LIST
    }

    /** The biome ids Super Snowy Biomes covers in CONTROLLED_BY_LIST mode. */
    public static List<String> SUPER_SNOWY_BIOMES;

    /**
     * When breaking a snow layer, replace it with the mapped layer block
     * based on the block below, with layer value reduced by 1.
     */
    public static boolean BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS;

    /**
     * Place rock blocks above layers on mapped surface blocks.
     * Requires Conquest Reforged and cr_rock_mappings.json.
     */
    public static boolean PLACE_ROCKS;
    public static float CHANCE_TO_PLACE_ROCKS;
    public static RockDensityMode ROCK_DENSITY_MODE;

    public enum RockDensityMode {
        RANDOM,
        DECREASED
    }

    public static float ROCK_DENSITY_FACTOR;

    /**
     * Place conquest foliage above layers on mapped surface blocks.
     * Requires Conquest Reforged and cr_extra_foliage_mappings.json.
     */
    public static boolean PLACE_EXTRA_FOLIAGE;
    public static float CHANCE_TO_PLACE_EXTRA_FOLIAGE;

    /**
     * Per-biome weighted grass placement using cr_enhanced_extra_foliage.json.
     * Requires Conquest Reforged. Uses the same chance_to_place_extra_foliage probability.
     */
    public static boolean CONQUEST_ENHANCED_EXTRA_FOLIAGE;

    /**
     * Per-biome weighted rock placement using cr_enhanced_rock_mappings.json.
     * Requires Conquest Reforged. Uses the same chance_to_place_rocks probability unless overridden per biome.
     * When enabled and the biome has an entry, falls back to cr_rock_mappings.json for unregistered biomes.
     */
    public static boolean CONQUEST_ENHANCED_ROCKS;

    /**
     * When structure_injection or structure_no_layers blocks/removes a layer whose surface
     * block is listed in second_pass_cleanup_blocks.json, taper the 8 in-chunk neighbors:
     * count > 4 → 4, count ≤ 4 → count-1, count 0 → remove (plant above moved down).
     */
    public static boolean SECOND_PASS_CLEANUP;

    /**
     * Place CR hand-made trees from NBT files during world generation.
     * NBT files are loaded from nbt_trees/<species>/ bundled in the mod jar.
     * Biome-to-species mapping is configured in cr_nbt_trees.json.
     * Requires Conquest Reforged.
     */
    public static boolean CR_NBT_TREES;

    /**
     * When cr_nbt_trees=true and no NBT species is found for a growing sapling
     * (sapling type not configured, biome not configured, or no NBT files available),
     * allow vanilla tree generation to proceed as a fallback.
     * false = suppress vanilla entirely; the sapling will not grow.
     */
    public static boolean CR_NBT_TREES_VANILLA_FALLBACK;

    /**
     * Lower plants standing on a partial-height layer block so they sit on its surface instead of
     * floating at the top of the block. Applies at runtime to every plant, generated or placed by
     * hand. Which blocks count as plants is detected automatically (see PlantGrounding); the two
     * lists below only override that detection. Must match on client and server.
     */
    public static boolean PLANT_GROUNDING;

    /**
     * Ground free-standing decorations the way plants are: lanterns, floor torches, candles, flower
     * pots, carpets, pressure plates, brewing stands and cakes. Their particles move down with them.
     */
    public static boolean GROUND_DECORATIONS;

    /**
     * VanillaLayer+ 3D layers on TerraForged terrain, measured from the surfaces on either side of
     * each step (see Layer3DWalls): a floor layer beside a higher terrain block becomes a 3D layer
     * (VLP 5.0.0+), the floor merged with a wall toward the step, and the open cells up the step
     * get a 3D layer with no floor part (vertical_layers 0), so only hard materials (those with a 3D
     * layer) get one. Uses vp_block_mappings.json, even when Conquest
     * Reforged is also installed. Read once, at the first world load; changes need a restart.
     */
    public static boolean THREE_D_LAYER_INJECTION;

    /**
     * 3D layers at chunk borders: a border cell's wall stands in the next chunk, read through the
     * feature step's region instead of being skipped (see Layer3DWalls.Margin). Without it, steep
     * cliffs crossing a chunk border stay bare there.
     */
    public static boolean THREE_D_LAYER_CROSS_CHUNK;

    /**
     * Lower the layer count in swamp biomes (any biome whose id contains "swamp": vanilla swamp and
     * mangrove swamp, and modded ones) by REDUCE_SWAMP_LAYERS_VALUE. Swamp ground sits around the
     * water line with puddles cut into it, and full-height layers there stand proud of the water.
     */
    public static boolean REDUCE_SWAMP_LAYERS;
    public static int REDUCE_SWAMP_LAYERS_VALUE;

    /**
     * Leave swamp puddles as water: a column whose top block a surface rule turned into a one-block
     * puddle gets no layer (see LayerPlacementHelper.isSurfacePuddle). false = the puddle receives
     * the near-full waterlogged layer the density field asks for.
     */
    public static boolean SWAMP_PUDDLES_AS_WATER;

    /**
     * Distant Horizons: show layers in LODs rounded to whole blocks (DH stores whole blocks only).
     * A layer with DH_LAYER_ROUNDING_THRESHOLD layers or more becomes its full block, a thinner
     * one disappears (or becomes water when waterlogged). See compat.DistantHorizonsCompat.
     */
    public static boolean DH_LAYER_ROUNDING;
    public static int DH_LAYER_ROUNDING_THRESHOLD;

    /**
     * Distant Horizons: exact layer heights in LODs (eighths of a block), through mixins into DH's
     * internals; only for DH versions they were checked against (3.3.2-3.3.4). Other versions, or
     * false here, fall back to the rounding above. Read at startup by mixin.dh.DhMixinPlugin
     * straight from the file (mixins load before this class may be touched), so changes need a
     * restart.
     */
    public static boolean DH_EXACT_LAYERS;

    /**
     * Distant Horizons exact heights: keep DH's render heights in whole blocks and carry the eighths
     * in the two lowest bits of each colour channel (a colour shift of at most 3/255). Works in
     * dimensions up to DH's own 4096-block limit, where the eighths-in-height-fields encoding stops
     * at 512. Only affects DH's in-memory render data, never its saved LOD data. Read at startup by
     * mixin.dh.DhMixinPlugin; changes need a restart.
     */
    public static boolean DH_EXACT_LAYERS_COLOR_BITS;

    /**
     * Also lower the selection outline and collision box of grounded plants, so they can be
     * targeted where they are drawn. false = visual offset only.
     */
    public static boolean PLANT_GROUNDING_SHAPES;

    /** Block ids grounded even though auto-detection rejects them. */
    public static List<String> PLANT_GROUNDING_FORCE_BLOCKS;

    /** Block ids never grounded, even though auto-detection accepts them. */
    public static List<String> PLANT_GROUNDING_EXCLUDE_BLOCKS;

    // ========== Options ==========
    //
    // One declaration per key of layer_config.json, in file and screen order. The defaults are what
    // a new install gets; the label and description of each key live in en_us.json under
    // config.aronalayersgen.option.<key>, and the file's comments are generated from them.

    private static final List<ConfigOption<?>> OPTIONS = new ArrayList<>();

    /**
     * Per-backend switches replaced by detection (LayerBackend); dropped from the file on rewrite.
     * Declared before the options so it is set when the static block below runs loadConfig().
     */
    private static final List<String> OBSOLETE_KEYS = List.of("rtf_layer_injection", "tellus_layer_injection");

    /**
     * Registers an option after the last one of its tab, so each tab's options stay together in
     * the screen and in layer_config.json even where their declarations are interleaved (a tree
     * option in World Gen next to a post option in Visuals). Within a tab, declaration order holds.
     */
    private static <T> ConfigOption<T> add(ConfigOption.Builder<T> builder) {
        ConfigOption<T> option = builder.build();
        int at = OPTIONS.size();
        while (at > 0 && OPTIONS.get(at - 1).tab.ordinal() > option.tab.ordinal()) at--;
        OPTIONS.add(at, option);
        return option;
    }

    private static final String[] TERRAFORGED = {"reterraforged", "freeterraforged"};

    // --- Terrain ---
    private static final ConfigOption<Boolean> O_LAYER_INJECTION = add(bool("layer_injection", true,
            () -> LAYER_INJECTION, v -> LAYER_INJECTION = v).in(ConfigTab.WORLDGEN, "backends"));
    private static final ConfigOption<InjectionMode> O_INJECTION_MODE = add(choice("injection_mode", InjectionMode.POST_FEATURES,
            () -> INJECTION_MODE, v -> INJECTION_MODE = v).in(ConfigTab.WORLDGEN, "backends"));
    private static final ConfigOption<Boolean> O_3D_LAYER_INJECTION = add(bool("3D_layer_injection", false,
            () -> THREE_D_LAYER_INJECTION, v -> THREE_D_LAYER_INJECTION = v)
            .in(ConfigTab.WORLDGEN, "backends").mods("vanillalayerplus").apply(Apply.RESTART));
    static {
        add(bool("3D_layer_cross_chunk", false, () -> THREE_D_LAYER_CROSS_CHUNK, v -> THREE_D_LAYER_CROSS_CHUNK = v)
                .in(ConfigTab.WORLDGEN, "backends").requires(O_3D_LAYER_INJECTION).mods("vanillalayerplus"));

        add(bool("tellus_reduce_layer_count", true, () -> TELLUS_REDUCE_LAYER_COUNT, v -> TELLUS_REDUCE_LAYER_COUNT = v)
                .in(ConfigTab.WORLDGEN, "tuning").mods("tellus"));
        add(bool("fractional_surface_reduce_layer_count", false,
                () -> FRACTIONAL_SURFACE_REDUCE_LAYER_COUNT, v -> FRACTIONAL_SURFACE_REDUCE_LAYER_COUNT = v)
                .in(ConfigTab.WORLDGEN, "tuning").requires(O_LAYER_INJECTION));
        add(decimal("fractional_surface_min_gradient", 0.0, 0.0, 0.1, 0.005,
                () -> FRACTIONAL_SURFACE_MIN_GRADIENT, v -> FRACTIONAL_SURFACE_MIN_GRADIENT = v)
                .in(ConfigTab.WORLDGEN, "tuning").requires(O_LAYER_INJECTION).advanced());
        add(decimal("fractional_surface_max_disagreement", 1.0, 0.0, 1.0, 0.05,
                () -> FRACTIONAL_SURFACE_MAX_DISAGREEMENT, v -> FRACTIONAL_SURFACE_MAX_DISAGREEMENT = v)
                .in(ConfigTab.WORLDGEN, "tuning").requires(O_LAYER_INJECTION).advanced());
        add(bool("fractional_surface_exact_interpolation", false,
                () -> FRACTIONAL_SURFACE_EXACT_INTERPOLATION, v -> FRACTIONAL_SURFACE_EXACT_INTERPOLATION = v)
                .in(ConfigTab.WORLDGEN, "tuning").requires(O_LAYER_INJECTION).advanced());
    }

    // --- Biomes ---
    private static final ConfigOption<Boolean> O_SKIP_SNOWY_BIOMES = add(bool("skip_snowy_biomes", false,
            () -> SKIP_SNOWY_BIOMES, v -> SKIP_SNOWY_BIOMES = v).in(ConfigTab.WORLDGEN, "snow"));
    private static final ConfigOption<Boolean> O_IMPROVE_SNOWY_BIOMES = add(bool("improve_snowy_biomes", false,
            () -> IMPROVE_SNOWY_BIOMES, v -> IMPROVE_SNOWY_BIOMES = v)
            .in(ConfigTab.WORLDGEN, "snow").requiresOff(O_SKIP_SNOWY_BIOMES));
    private static final ConfigOption<SuperSnowyMode> O_SUPER_SNOWY_MODE = add(choice("super_snowy_mode",
            SuperSnowyMode.ALL_SNOWY_BIOMES, () -> SUPER_SNOWY_MODE, v -> SUPER_SNOWY_MODE = v)
            .in(ConfigTab.WORLDGEN, "snow").requiresOff(O_SKIP_SNOWY_BIOMES).requires(O_IMPROVE_SNOWY_BIOMES));
    static {
        add(biomeList("super_snowy_biomes", List.of(
                    "minecraft:snowy_plains", "minecraft:ice_spikes", "minecraft:snowy_taiga", "minecraft:snowy_beach",
                    "minecraft:frozen_river", "minecraft:snowy_slopes", "minecraft:grove", "minecraft:frozen_peaks",
                    "minecraft:jagged_peaks"),
                () -> SUPER_SNOWY_BIOMES, v -> SUPER_SNOWY_BIOMES = v)
                .in(ConfigTab.WORLDGEN, "snow").requiresOff(O_SKIP_SNOWY_BIOMES).requires(O_IMPROVE_SNOWY_BIOMES)
                .requires(O_SUPER_SNOWY_MODE, SuperSnowyMode.CONTROLLED_BY_LIST));
        add(bool("break_snow_layers_to_mapped_layers", true,
                () -> BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS, v -> BREAK_SNOW_LAYERS_TO_MAPPED_LAYERS = v)
                .in(ConfigTab.GAMEPLAY, "snow").apply(Apply.LIVE));

        add(bool("underwater_layers", true, () -> UNDERWATER_LAYERS, v -> UNDERWATER_LAYERS = v)
                .in(ConfigTab.WORLDGEN, "surface"));
        add(bool("replace_dirt_path", true, () -> REPLACE_DIRT_PATH, v -> REPLACE_DIRT_PATH = v)
                .in(ConfigTab.WORLDGEN, "surface"));
        add(bool("place_wet_sand", true, () -> PLACE_WET_SAND, v -> PLACE_WET_SAND = v)
                .in(ConfigTab.WORLDGEN, "surface"));
    }
    private static final ConfigOption<Boolean> O_REDUCE_SWAMP_LAYERS = add(bool("reduce_swamp_layers", false,
            () -> REDUCE_SWAMP_LAYERS, v -> REDUCE_SWAMP_LAYERS = v).in(ConfigTab.WORLDGEN, "swamps"));
    static {
        add(integer("reduce_swamp_layers_value", 2, 0, 8, () -> REDUCE_SWAMP_LAYERS_VALUE, v -> REDUCE_SWAMP_LAYERS_VALUE = v)
                .in(ConfigTab.WORLDGEN, "swamps").requires(O_REDUCE_SWAMP_LAYERS));
        add(bool("swamp_puddles_as_water", true, () -> SWAMP_PUDDLES_AS_WATER, v -> SWAMP_PUDDLES_AS_WATER = v)
                .in(ConfigTab.WORLDGEN, "swamps"));
    }

    // --- Plants & trees ---
    private static final ConfigOption<Boolean> O_PLANT_INJECTION = add(bool("plant_injection", true,
            () -> PLANT_INJECTION, v -> PLANT_INJECTION = v).in(ConfigTab.WORLDGEN, "plants"));
    static {
        add(bool("replace_sea_grass", true, () -> REPLACE_SEA_GRASS, v -> REPLACE_SEA_GRASS = v)
                .in(ConfigTab.WORLDGEN, "plants").requires(O_PLANT_INJECTION).mods("conquest"));
        add(bool("tree_injection", true, () -> TREE_INJECTION, v -> TREE_INJECTION = v)
                .in(ConfigTab.WORLDGEN, "trees"));
        add(bool("trees_on_layers", true, () -> TREES_ON_LAYERS, v -> TREES_ON_LAYERS = v)
                .in(ConfigTab.WORLDGEN, "trees").side(Side.BOTH).apply(Apply.RESTART));
    }
    private static final ConfigOption<Boolean> O_POSTS_ON_LAYERS = add(bool("posts_on_layers", true,
            () -> POSTS_ON_LAYERS, v -> POSTS_ON_LAYERS = v).in(ConfigTab.VISUALS, "posts")
            .side(Side.BOTH).apply(Apply.RESTART));
    private static final ConfigOption<PostMode> O_POSTS_MODE = add(choice("posts_on_layers_mode", PostMode.EXTEND,
            () -> POSTS_MODE, v -> POSTS_MODE = v)
            .in(ConfigTab.VISUALS, "posts").requires(O_POSTS_ON_LAYERS).side(Side.BOTH).apply(Apply.RESTART));
    static {
        add(bool("lowered_posts_reach_up", true, () -> LOWERED_POSTS_REACH_UP, v -> LOWERED_POSTS_REACH_UP = v)
                .in(ConfigTab.VISUALS, "posts").requires(O_POSTS_ON_LAYERS).requires(O_POSTS_MODE, PostMode.LOWER)
                .side(Side.BOTH).apply(Apply.RESOURCE_RELOAD));
    }
    private static final ConfigOption<Boolean> O_CR_NBT_TREES = add(bool("cr_nbt_trees", true,
            () -> CR_NBT_TREES, v -> CR_NBT_TREES = v).in(ConfigTab.WORLDGEN, "trees").mods("conquest"));
    static {
        add(bool("cr_nbt_trees_vanilla_fallback", true,
                () -> CR_NBT_TREES_VANILLA_FALLBACK, v -> CR_NBT_TREES_VANILLA_FALLBACK = v)
                .in(ConfigTab.GAMEPLAY, "saplings").requires(O_CR_NBT_TREES).apply(Apply.LIVE));
    }
    private static final ConfigOption<Boolean> O_PLANT_GROUNDING = add(bool("plant_grounding", true,
            () -> PLANT_GROUNDING, v -> PLANT_GROUNDING = v).in(ConfigTab.VISUALS, "grounding")
            .side(Side.BOTH).apply(Apply.RESTART));
    static {
        add(bool("plant_grounding_shapes", true, () -> PLANT_GROUNDING_SHAPES, v -> PLANT_GROUNDING_SHAPES = v)
                .in(ConfigTab.VISUALS, "grounding").requires(O_PLANT_GROUNDING).side(Side.BOTH).apply(Apply.LIVE));
        add(bool("ground_decorations", true, () -> GROUND_DECORATIONS, v -> GROUND_DECORATIONS = v)
                .in(ConfigTab.VISUALS, "grounding").requires(O_PLANT_GROUNDING).side(Side.BOTH).apply(Apply.RESTART));
        add(blockList("plant_grounding_force_blocks", List.of("minecraft:light_gray_petals"),
                () -> PLANT_GROUNDING_FORCE_BLOCKS, v -> PLANT_GROUNDING_FORCE_BLOCKS = v)
                .in(ConfigTab.VISUALS, "grounding").requires(O_PLANT_GROUNDING).side(Side.BOTH).apply(Apply.RESOURCE_RELOAD));
        add(blockList("plant_grounding_exclude_blocks", List.of(),
                () -> PLANT_GROUNDING_EXCLUDE_BLOCKS, v -> PLANT_GROUNDING_EXCLUDE_BLOCKS = v)
                .in(ConfigTab.VISUALS, "grounding").requires(O_PLANT_GROUNDING).side(Side.BOTH).apply(Apply.RESOURCE_RELOAD));
    }

    // --- Rocks & foliage ---
    private static final ConfigOption<Boolean> O_PLACE_ROCKS = add(bool("place_rocks", true,
            () -> PLACE_ROCKS, v -> PLACE_ROCKS = v).in(ConfigTab.WORLDGEN, "rocks").mods("conquest"));
    static {
        add(bool("conquest_enhanced_rocks", true, () -> CONQUEST_ENHANCED_ROCKS, v -> CONQUEST_ENHANCED_ROCKS = v)
                .in(ConfigTab.WORLDGEN, "rocks").requires(O_PLACE_ROCKS).mods("conquest"));
        add(decimal("chance_to_place_rocks", 0.05, 0.0, 1.0, 0.01,
                () -> toDouble(CHANCE_TO_PLACE_ROCKS), v -> CHANCE_TO_PLACE_ROCKS = v.floatValue())
                .in(ConfigTab.WORLDGEN, "rocks").requires(O_PLACE_ROCKS).mods("conquest"));
    }
    private static final ConfigOption<RockDensityMode> O_ROCK_DENSITY_MODE = add(choice("rock_density_mode", RockDensityMode.DECREASED,
            () -> ROCK_DENSITY_MODE, v -> ROCK_DENSITY_MODE = v)
            .in(ConfigTab.WORLDGEN, "rocks").requires(O_PLACE_ROCKS).mods("conquest"));
    static {
        add(decimal("rock_density_factor", 4.0, 1.0, 10.0, 0.5,
                () -> toDouble(ROCK_DENSITY_FACTOR), v -> ROCK_DENSITY_FACTOR = v.floatValue())
                .in(ConfigTab.WORLDGEN, "rocks").requires(O_PLACE_ROCKS)
                .requires(O_ROCK_DENSITY_MODE, RockDensityMode.DECREASED).mods("conquest"));
    }
    private static final ConfigOption<Boolean> O_PLACE_EXTRA_FOLIAGE = add(bool("place_extra_foliage", true,
            () -> PLACE_EXTRA_FOLIAGE, v -> PLACE_EXTRA_FOLIAGE = v).in(ConfigTab.WORLDGEN, "foliage").mods("conquest"));
    static {
        add(bool("conquest_enhanced_extra_foliage", true,
                () -> CONQUEST_ENHANCED_EXTRA_FOLIAGE, v -> CONQUEST_ENHANCED_EXTRA_FOLIAGE = v)
                .in(ConfigTab.WORLDGEN, "foliage").requires(O_PLACE_EXTRA_FOLIAGE).mods("conquest"));
        add(decimal("chance_to_place_extra_foliage", 0.5, 0.0, 1.0, 0.01,
                () -> toDouble(CHANCE_TO_PLACE_EXTRA_FOLIAGE), v -> CHANCE_TO_PLACE_EXTRA_FOLIAGE = v.floatValue())
                .in(ConfigTab.WORLDGEN, "foliage").requires(O_PLACE_EXTRA_FOLIAGE).mods("conquest"));
    }

    // --- Structures ---
    private static final ConfigOption<Boolean> O_STRUCTURE_INJECTION = add(bool("structure_injection", true,
            () -> STRUCTURE_INJECTION, v -> STRUCTURE_INJECTION = v).in(ConfigTab.STRUCTURES, "detection"));
    static {
        add(bool("cross_chunk_structure_detection", false,
                () -> CROSS_CHUNK_STRUCTURE_DETECTION, v -> CROSS_CHUNK_STRUCTURE_DETECTION = v)
                .in(ConfigTab.STRUCTURES, "detection").requires(O_STRUCTURE_INJECTION));
    }
    private static final ConfigOption<Boolean> O_STRUCTURE_FOOTPRINT_CHECK = add(bool("structure_footprint_check", false,
            () -> STRUCTURE_FOOTPRINT_CHECK, v -> STRUCTURE_FOOTPRINT_CHECK = v)
            .in(ConfigTab.STRUCTURES, "detection").requires(O_STRUCTURE_INJECTION));
    static {
        add(integer("structure_footprint_below_margin", 8, 0, 32,
                () -> STRUCTURE_FOOTPRINT_BELOW_MARGIN, v -> STRUCTURE_FOOTPRINT_BELOW_MARGIN = v)
                .in(ConfigTab.STRUCTURES, "detection").requires(O_STRUCTURE_FOOTPRINT_CHECK).advanced());
        add(integer("structure_footprint_above_margin", 4, 0, 32,
                () -> STRUCTURE_FOOTPRINT_ABOVE_MARGIN, v -> STRUCTURE_FOOTPRINT_ABOVE_MARGIN = v)
                .in(ConfigTab.STRUCTURES, "detection").requires(O_STRUCTURE_FOOTPRINT_CHECK).advanced());
    }
    private static final ConfigOption<Boolean> O_STRUCTURE_INJECTION_EXTRA_BOUNDS = add(bool("structure_injection_extra_bounds", false,
            () -> STRUCTURE_INJECTION_EXTRA_BOUNDS, v -> STRUCTURE_INJECTION_EXTRA_BOUNDS = v)
            .in(ConfigTab.STRUCTURES, "detection").requires(O_STRUCTURE_INJECTION));
    static {
        add(integer("structure_injection_extra_bounds_distance", 4, 0, 16,
                () -> STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE, v -> STRUCTURE_INJECTION_EXTRA_BOUNDS_DISTANCE = v)
                .in(ConfigTab.STRUCTURES, "detection").requires(O_STRUCTURE_INJECTION_EXTRA_BOUNDS));
    }
    private static final ConfigOption<Boolean> O_ENCLOSED_SPACE_CHECK = add(bool("enclosed_space_check", true,
            () -> ENCLOSED_SPACE_CHECK, v -> ENCLOSED_SPACE_CHECK = v)
            .in(ConfigTab.STRUCTURES, "detection").requires(O_STRUCTURE_INJECTION));
    static {
        add(integer("enclosed_space_height", 7, 1, 16, () -> ENCLOSED_SPACE_HEIGHT, v -> ENCLOSED_SPACE_HEIGHT = v)
                .in(ConfigTab.STRUCTURES, "detection").requires(O_ENCLOSED_SPACE_CHECK));
        add(bool("structure_cleanup", false, () -> STRUCTURE_CLEANUP, v -> STRUCTURE_CLEANUP = v)
                .in(ConfigTab.STRUCTURES, "detection").requires(O_STRUCTURE_INJECTION));
        add(bool("structure_no_layers", false, () -> STRUCTURE_NO_LAYERS, v -> STRUCTURE_NO_LAYERS = v)
                .in(ConfigTab.STRUCTURES, "detection"));
        add(bool("second_pass_cleanup", false, () -> SECOND_PASS_CLEANUP, v -> SECOND_PASS_CLEANUP = v)
                .in(ConfigTab.STRUCTURES, "detection"));
    }
    private static final ConfigOption<Boolean> O_CONSERVATIVE_SURFACE_HEIGHTMAP = add(bool("conservative_surface_heightmap", true,
            () -> CONSERVATIVE_SURFACE_HEIGHTMAP, v -> CONSERVATIVE_SURFACE_HEIGHTMAP = v)
            .in(ConfigTab.STRUCTURES, "conservative").mods(TERRAFORGED));
    static {
        add(integer("conservative_surface_tolerance_up", 0, 0, 8,
                () -> CONSERVATIVE_SURFACE_TOLERANCE_UP, v -> CONSERVATIVE_SURFACE_TOLERANCE_UP = v)
                .in(ConfigTab.STRUCTURES, "conservative").requires(O_CONSERVATIVE_SURFACE_HEIGHTMAP).mods(TERRAFORGED).advanced());
        add(integer("conservative_surface_tolerance_down", 0, 0, 8,
                () -> CONSERVATIVE_SURFACE_TOLERANCE_DOWN, v -> CONSERVATIVE_SURFACE_TOLERANCE_DOWN = v)
                .in(ConfigTab.STRUCTURES, "conservative").requires(O_CONSERVATIVE_SURFACE_HEIGHTMAP).mods(TERRAFORGED).advanced());
    }
    private static final ConfigOption<Boolean> O_CONSERVATIVE_SURFACE_FALLBACK = add(bool("conservative_surface_fallback", true,
            () -> CONSERVATIVE_SURFACE_FALLBACK, v -> CONSERVATIVE_SURFACE_FALLBACK = v)
            .in(ConfigTab.STRUCTURES, "conservative").requires(O_CONSERVATIVE_SURFACE_HEIGHTMAP).mods(TERRAFORGED));
    static {
        add(integer("conservative_surface_fallback_value_up", 0, 0, 8,
                () -> CONSERVATIVE_SURFACE_FALLBACK_VALUE_UP, v -> CONSERVATIVE_SURFACE_FALLBACK_VALUE_UP = v)
                .in(ConfigTab.STRUCTURES, "conservative").requires(O_CONSERVATIVE_SURFACE_FALLBACK).mods(TERRAFORGED).advanced());
        add(integer("conservative_surface_fallback_value_down", 4, 0, 8,
                () -> CONSERVATIVE_SURFACE_FALLBACK_VALUE_DOWN, v -> CONSERVATIVE_SURFACE_FALLBACK_VALUE_DOWN = v)
                .in(ConfigTab.STRUCTURES, "conservative").requires(O_CONSERVATIVE_SURFACE_FALLBACK).mods(TERRAFORGED).advanced());
    }
    private static final ConfigOption<Boolean> O_STRUCTURE_SKIP_ELEVATED = add(bool("structure_skip_elevated", false,
            () -> STRUCTURE_SKIP_ELEVATED, v -> STRUCTURE_SKIP_ELEVATED = v)
            .in(ConfigTab.STRUCTURES, "elevated").requires(O_INJECTION_MODE, InjectionMode.POST_FEATURES).mods(TERRAFORGED).advanced());
    private static final ConfigOption<Boolean> O_STRUCTURE_REPLACE_ELEVATED = add(bool("structure_replace_elevated", false,
            () -> STRUCTURE_REPLACE_ELEVATED, v -> STRUCTURE_REPLACE_ELEVATED = v)
            .in(ConfigTab.STRUCTURES, "elevated").requires(O_STRUCTURE_SKIP_ELEVATED).mods(TERRAFORGED).advanced());
    private static final ConfigOption<Boolean> O_STRUCTURE_REPLACE_ELEVATED_HIGH = add(bool("structure_replace_elevated_high", false,
            () -> STRUCTURE_REPLACE_ELEVATED_HIGH, v -> STRUCTURE_REPLACE_ELEVATED_HIGH = v)
            .in(ConfigTab.STRUCTURES, "elevated").requires(O_STRUCTURE_REPLACE_ELEVATED).mods(TERRAFORGED).advanced());
    static {
        add(integer("structure_replace_elevated_high_max", 3, 1, 16,
                () -> STRUCTURE_REPLACE_ELEVATED_HIGH_MAX, v -> STRUCTURE_REPLACE_ELEVATED_HIGH_MAX = v)
                .in(ConfigTab.STRUCTURES, "elevated").requires(O_STRUCTURE_REPLACE_ELEVATED_HIGH).mods(TERRAFORGED).advanced());
    }
    private static final ConfigOption<Boolean> O_STRUCTURE_SKIP_EXTRA_CLEANUP = add(bool("structure_skip_extra_cleanup", false,
            () -> STRUCTURE_SKIP_EXTRA_CLEANUP, v -> STRUCTURE_SKIP_EXTRA_CLEANUP = v)
            .in(ConfigTab.STRUCTURES, "elevated").requires(O_STRUCTURE_SKIP_ELEVATED).mods(TERRAFORGED).advanced());
    static {
        add(integer("structure_skip_extra_cleanup_distance", 2, 0, 8,
                () -> STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE, v -> STRUCTURE_SKIP_EXTRA_CLEANUP_DISTANCE = v)
                .in(ConfigTab.STRUCTURES, "elevated").requires(O_STRUCTURE_SKIP_EXTRA_CLEANUP).mods(TERRAFORGED).advanced());
    }

    // --- Distant Horizons ---
    private static final ConfigOption<Boolean> O_DH_LAYER_ROUNDING = add(bool("dh_layer_rounding", true,
            () -> DH_LAYER_ROUNDING, v -> DH_LAYER_ROUNDING = v)
            .in(ConfigTab.VISUALS, "rounding").mods("distanthorizons").side(Side.CLIENT).apply(Apply.DH_LODS));
    static {
        add(integer("dh_layer_rounding_threshold", 4, 1, 8, () -> DH_LAYER_ROUNDING_THRESHOLD, v -> DH_LAYER_ROUNDING_THRESHOLD = v)
                .in(ConfigTab.VISUALS, "rounding").requires(O_DH_LAYER_ROUNDING).mods("distanthorizons")
                .side(Side.CLIENT).apply(Apply.DH_LODS));
    }
    private static final ConfigOption<Boolean> O_DH_EXACT_LAYERS = add(bool("dh_exact_layers", true,
            () -> DH_EXACT_LAYERS, v -> DH_EXACT_LAYERS = v)
            .in(ConfigTab.VISUALS, "exact").mods("distanthorizons").side(Side.CLIENT).apply(Apply.RESTART));
    static {
        add(bool("dh_exact_layers_color_bits", true, () -> DH_EXACT_LAYERS_COLOR_BITS, v -> DH_EXACT_LAYERS_COLOR_BITS = v)
                .in(ConfigTab.VISUALS, "exact").requires(O_DH_EXACT_LAYERS).mods("distanthorizons")
                .side(Side.CLIENT).apply(Apply.RESTART).advanced());
    }

    // --- Debug ---
    private static final ConfigOption<Boolean> O_DEBUG_LOGGING = add(bool("debug_logging", false,
            () -> DEBUG_LOGGING, v -> DEBUG_LOGGING = v).in(ConfigTab.DEBUG, "logging").side(Side.BOTH).apply(Apply.LIVE));

    private static void debugCategory(String key, Supplier<Boolean> get, Consumer<Boolean> set) {
        add(bool(key, true, get, set).in(ConfigTab.DEBUG, "categories").requires(O_DEBUG_LOGGING)
                .side(Side.BOTH).apply(Apply.LIVE));
    }

    static {
        debugCategory("debug_log_chunk_init", () -> DEBUG_LOG_CHUNK_INIT, v -> DEBUG_LOG_CHUNK_INIT = v);
        debugCategory("debug_log_rtf", () -> DEBUG_LOG_RTF, v -> DEBUG_LOG_RTF = v);
        debugCategory("debug_log_structure", () -> DEBUG_LOG_STRUCTURE, v -> DEBUG_LOG_STRUCTURE = v);
        debugCategory("debug_log_skip_elevated", () -> DEBUG_LOG_SKIP_ELEVATED, v -> DEBUG_LOG_SKIP_ELEVATED = v);
        debugCategory("debug_log_structure_no_layers", () -> DEBUG_LOG_STRUCTURE_NO_LAYERS, v -> DEBUG_LOG_STRUCTURE_NO_LAYERS = v);
        debugCategory("debug_log_second_pass", () -> DEBUG_LOG_SECOND_PASS, v -> DEBUG_LOG_SECOND_PASS = v);
        debugCategory("debug_log_foliage", () -> DEBUG_LOG_FOLIAGE, v -> DEBUG_LOG_FOLIAGE = v);
        debugCategory("debug_log_plants", () -> DEBUG_LOG_PLANTS, v -> DEBUG_LOG_PLANTS = v);
        debugCategory("debug_log_rocks", () -> DEBUG_LOG_ROCKS, v -> DEBUG_LOG_ROCKS = v);
        debugCategory("debug_log_correction", () -> DEBUG_LOG_CORRECTION, v -> DEBUG_LOG_CORRECTION = v);
        debugCategory("debug_log_snow", () -> DEBUG_LOG_SNOW, v -> DEBUG_LOG_SNOW = v);
        debugCategory("debug_log_tree_soil", () -> DEBUG_LOG_TREE_SOIL, v -> DEBUG_LOG_TREE_SOIL = v);
        debugCategory("debug_log_vanilla", () -> DEBUG_LOG_VANILLA, v -> DEBUG_LOG_VANILLA = v);
        debugCategory("debug_log_nbt_trees", () -> DEBUG_LOG_NBT_TREES, v -> DEBUG_LOG_NBT_TREES = v);
        debugCategory("debug_log_tellus", () -> DEBUG_LOG_TELLUS, v -> DEBUG_LOG_TELLUS = v);
    }

    static {
        loadConfig();
    }

    /** Every option, in file and screen order. */
    public static List<ConfigOption<?>> options() {
        return Collections.unmodifiableList(OPTIONS);
    }

    public static Path configDir() {
        return CONFIG_DIR;
    }

    /** A float field as the decimal it was written as, so 0.05f reads back as 0.05 rather than 0.0500000007. */
    private static double toDouble(float value) {
        return Double.parseDouble(Float.toString(value));
    }

    // ========== Loading and saving ==========

    /**
     * Resets every option to its default, then applies the file's values. The file is then rewritten
     * in the current layout — new options added with their default, comments refreshed — unless it
     * could not be read or held an invalid value, in which case it is left for the user to fix.
     */
    public static void loadConfig() {
        for (ConfigOption<?> option : OPTIONS) option.reset();

        Path file = CONFIG_DIR.resolve(CONFIG_FILE);
        JsonObject extra = new JsonObject();
        boolean rewrite = true;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                if (json != null) rewrite = applyJson(json, extra);
                AronaLayersGen.LOGGER.info("Loaded layer config from: {}", file);
            } catch (IOException | JsonParseException e) {
                AronaLayersGen.LOGGER.error("Could not read {}, using defaults until it is fixed: {}", file, e.getMessage());
                rewrite = false;
            }
        }
        if (rewrite) write(extra);
    }

    /** @return false when a value was invalid, so the file must not be overwritten */
    private static boolean applyJson(JsonObject json, JsonObject extra) {
        boolean valid = true;
        for (ConfigOption<?> option : OPTIONS) {
            if (json.has(option.key)) valid &= applyValue(option, json.get(option.key));
        }
        migrateBackendSwitches(json);
        // Keys this version does not know (from a newer version, or a typo) are kept at the end
        // rather than dropped, so a downgrade and upgrade does not lose them.
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_") || OBSOLETE_KEYS.contains(key) || OPTIONS.stream().anyMatch(o -> o.key.equals(key))) continue;
            AronaLayersGen.LOGGER.warn("[Config] Unknown key '{}' in {} is ignored", key, CONFIG_FILE);
            extra.add(key, entry.getValue());
        }
        return valid;
    }

    /**
     * layer_injection used to switch only the vanilla backend, next to one switch per mod
     * backend. It is now the master switch, so generation stays on if any of the three was on:
     * a file with layer_injection false and rtf_layer_injection true meant "TerraForged layers".
     */
    private static void migrateBackendSwitches(JsonObject json) {
        boolean anyOld = false;
        boolean anyOn = LAYER_INJECTION;
        for (String key : OBSOLETE_KEYS) {
            if (!json.has(key)) continue;
            anyOld = true;
            JsonElement value = json.get(key);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean()) anyOn = true;
        }
        if (!anyOld) return;
        if (anyOn != LAYER_INJECTION) {
            LAYER_INJECTION = anyOn;
            AronaLayersGen.LOGGER.info("[Config] layer_injection is now the master switch; turned on because a removed backend switch was on");
        }
        AronaLayersGen.LOGGER.info("[Config] Removed {}: each dimension's layer source is now detected", OBSOLETE_KEYS);
    }

    private static <T> boolean applyValue(ConfigOption<T> option, JsonElement element) {
        try {
            option.set(option.fromJson(element));
            return true;
        } catch (RuntimeException e) {
            AronaLayersGen.LOGGER.error("[Config] Invalid value {} for '{}' ({}), using the default {}",
                    element, option.key, e.getMessage(), option.format(option.defaultValue));
            return false;
        }
    }

    public static void reload() {
        loadConfig();
        onValuesChanged();
    }

    /** Drops what was derived from config values; called after a reload or a save from the screen. */
    public static void onValuesChanged() {
        io.arona74.aronalayersgen.grounding.PlantGrounding.invalidate();
    }

    /** Writes the current values. */
    public static void save() {
        write(new JsonObject());
    }

    private static void write(JsonObject extra) {
        Path file = CONFIG_DIR.resolve(CONFIG_FILE);
        String text = GSON.toJson(toJson(extra)) + "\n";
        try {
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return;
            Files.createDirectories(CONFIG_DIR);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            AronaLayersGen.LOGGER.info("Saved config to: {}", file);
        } catch (IOException e) {
            AronaLayersGen.LOGGER.error("Failed to save config to {}", file, e);
        }
    }

    private static JsonObject toJson(JsonObject extra) {
        Map<String, String> lang = englishText();
        JsonObject json = new JsonObject();
        json.addProperty("_comment", "Arona Layers Generator settings. Edit them here or in game (Mod Menu, or the"
                + " /algconfig command). The mod rewrites this file at startup and on save: your values are kept,"
                + " comments are refreshed and options added by an update appear with their default.");
        ConfigTab tab = null;
        for (ConfigOption<?> option : OPTIONS) {
            if (option.tab != tab) {
                tab = option.tab;
                json.addProperty("_section_" + tab.id(),
                        "=== " + lang.getOrDefault("config.aronalayersgen.section." + tab.id(), tab.id()).toUpperCase(Locale.ROOT) + " ===");
            }
            json.addProperty("_comment_" + option.key, comment(option, lang));
            json.add(option.key, valueJson(option));
        }
        for (Map.Entry<String, JsonElement> entry : extra.entrySet()) json.add(entry.getKey(), entry.getValue());
        return json;
    }

    private static <T> JsonElement valueJson(ConfigOption<T> option) {
        return option.toJson(option.get());
    }

    private static <T> String comment(ConfigOption<T> option, Map<String, String> lang) {
        StringBuilder text = new StringBuilder(lang.getOrDefault(option.descriptionKey(), ""));
        if (!option.mods.isEmpty()) {
            text.append(' ').append(String.format(lang.getOrDefault("config.aronalayersgen.file.mods", "Needs %s."),
                    String.join(" or ", option.mods)));
        }
        for (ConfigOption.Requirement requirement : option.requirements) {
            text.append(' ').append(String.format(lang.getOrDefault("config.aronalayersgen.file.requires", "Only used while %s is %s."),
                    requirement.option().key, requirement.value()));
        }
        if (option.kind == ConfigOption.Kind.INT || option.kind == ConfigOption.Kind.DOUBLE) {
            text.append(' ').append(String.format(lang.getOrDefault("config.aronalayersgen.file.range", "Range %s to %s."),
                    trim(option.min), trim(option.max)));
        } else if (option.kind == ConfigOption.Kind.ENUM) {
            text.append(' ').append(String.format(lang.getOrDefault("config.aronalayersgen.file.values", "One of %s."),
                    String.join(", ", Arrays.stream(option.enumValues()).map(v -> ((Enum<?>) v).name()).toList())));
        }
        text.append(' ').append(lang.getOrDefault(option.apply.langKey(), ""));
        text.append(' ').append(String.format(lang.getOrDefault("config.aronalayersgen.file.default", "Default: %s."),
                option.format(option.defaultValue)));
        return text.toString().trim();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /**
     * The English strings, read from the bundled language file so the dedicated server (which never
     * loads client language assets) can still write the same comments the screen shows as tooltips.
     */
    private static Map<String, String> englishText() {
        try (InputStream in = LayerConfig.class.getClassLoader().getResourceAsStream(LANG_FILE)) {
            if (in == null) return Map.of();
            JsonObject json = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            Map<String, String> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) map.put(entry.getKey(), entry.getValue().getAsString());
            return map;
        } catch (IOException | JsonParseException e) {
            AronaLayersGen.LOGGER.warn("[Config] Could not read {}: {}", LANG_FILE, e.getMessage());
            return Map.of();
        }
    }
}
