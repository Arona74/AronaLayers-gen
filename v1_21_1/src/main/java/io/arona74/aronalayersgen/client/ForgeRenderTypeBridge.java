package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.AronaLayersGen;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;

/**
 * Keeps the render type a (Neo)Forge model asked for when GroundedPlantModel wraps it.
 *
 * <p>On Forge and NeoForge a model JSON can pick its own render type ({@code "render_type":
 * "minecraft:cutout"}); the chunk renderer asks the baked model through getRenderTypes, an
 * extension method Fabric does not have. Mods run on Fabric through Sinytra Connector keep doing
 * that (Project Vibrant Journeys sets it on its grasses and never registers the block as cutout).
 * Our wrapper does not override getRenderTypes, so it answers with the extension's default, the
 * block's registered type, which for those blocks is solid: the grass lost its transparency.
 *
 * <p>Fabric code cannot name the extension's types, so this asks the original model by reflection
 * and, if it wants a non-solid type the block does not already have, registers the block for that
 * type. The wrapper's inherited default then gives the same answer. Without Forge or NeoForge on the
 * classpath (plain Fabric) the lookup finds nothing and this does nothing.
 */
final class ForgeRenderTypeBridge {
    private ForgeRenderTypeBridge() {}

    private static final String[] MODEL_DATA_CLASSES = {
        "net.neoforged.neoforge.client.model.data.ModelData",   // NeoForge (1.21.1)
        "net.minecraftforge.client.model.data.ModelData"         // Forge (1.20.1)
    };

    private static boolean resolved;
    private static Class<?> modelDataClass;
    private static Object emptyModelData;

    static void carryOver(BakedModel original, BlockState state) {
        if (!resolve()) return;
        try {
            if (ItemBlockRenderTypes.getChunkRenderType(state) != RenderType.solid()) return;
            Method getRenderTypes = original.getClass().getMethod("getRenderTypes",
                BlockState.class, RandomSource.class, modelDataClass);
            Object types = getRenderTypes.invoke(original, state, RandomSource.create(42L), emptyModelData);
            if (!(types instanceof Iterable<?> iterable)) return;
            for (Object type : iterable) {
                if (type instanceof RenderType renderType && renderType != RenderType.solid()) {
                    BlockRenderLayerMap.INSTANCE.putBlock(state.getBlock(), renderType);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            AronaLayersGen.LOGGER.debug("Plant grounding: no render type for {} ({})", state, e.toString());
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            for (String name : MODEL_DATA_CLASSES) {
                try {
                    modelDataClass = Class.forName(name);
                    emptyModelData = modelDataClass.getField("EMPTY").get(null);
                    break;
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    modelDataClass = null;
                }
            }
        }
        return modelDataClass != null;
    }
}
