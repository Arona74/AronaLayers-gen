package io.arona74.aronalayersgen;

import io.arona74.aronalayersgen.block.LayerBlock;
import io.arona74.aronalayersgen.block.PowderSnowLayerBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * This mod's own layer blocks, for surfaces whose backend has no matching layer block.
 *
 * <p>Created in {@link #register()}, not when the class loads: a Block's constructor takes a
 * holder from the block registry, which NeoForge only allows while its registration event runs.
 * Fabric calls register() from its initializer, NeoForge from the block RegisterEvent. Null until then.
 */
public final class ModBlocks {
    private ModBlocks() {}

    public static Block POWDER_SNOW_LAYER;
    public static Block DEEPSLATE_LAYER;
    public static Block MOSS_LAYER;
    public static Block ZINC_ORE_LAYER;
    public static Block DEEPSLATE_ZINC_ORE_LAYER;

    public static void register() {
        POWDER_SNOW_LAYER = register("powder_snow_layer", new PowderSnowLayerBlock(
                Compat.blockSettings(Blocks.SNOW, "powder_snow_layer").speedFactor(0.9f)));

        // At layers=8 these convert to the given full block (like powder_snow_layer -> powder_snow).
        DEEPSLATE_LAYER = register("deepslate_layer",
                new LayerBlock(Compat.blockSettings(Blocks.DEEPSLATE, "deepslate_layer"), Blocks.DEEPSLATE));
        MOSS_LAYER = register("moss_layer",
                new LayerBlock(Compat.blockSettings(Blocks.MOSS_BLOCK, "moss_layer"), Blocks.MOSS_BLOCK));

        // Create's zinc ores. Registered whether or not Create is installed (block registries are
        // synced, and the mappings simply skip create:* ids when it is absent). Properties come from
        // the iron ores, which zinc ore matches in hardness; Create's own blocks may not exist yet at
        // this point.
        ZINC_ORE_LAYER = register("zinc_ore_layer",
                new LayerBlock(Compat.blockSettings(Blocks.IRON_ORE, "zinc_ore_layer"),
                        () -> Compat.blockFromId("create:zinc_ore")));
        DEEPSLATE_ZINC_ORE_LAYER = register("deepslate_zinc_ore_layer",
                new LayerBlock(Compat.blockSettings(Blocks.DEEPSLATE_IRON_ORE, "deepslate_zinc_ore_layer"),
                        () -> Compat.blockFromId("create:deepslate_zinc_ore")));
    }

    private static Block register(String path, Block block) {
        Registry.register(BuiltInRegistries.BLOCK, Compat.id(AronaLayersGen.MOD_ID, path), block);
        Registry.register(BuiltInRegistries.ITEM, Compat.id(AronaLayersGen.MOD_ID, path),
                new BlockItem(block, Compat.itemSettings(path)));
        return block;
    }
}
