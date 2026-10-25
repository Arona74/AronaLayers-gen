package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * VanillaLayer+ 3D layers ({@code <name>_3d_layer}, VLP 5.0.0+): a floor layer and a wall layer of
 * the same material merged into one block. {@code vertical_layers} (0-8) is the floor's height
 * across the whole block, 0 for a wall alone; {@code horizontal_layers} the wall's thickness
 * against the side named by {@code horizontal_facing} (the side it is attached to); at 4/4 it is a
 * stair. Either value at 8 would be a full cube, so VLP turns such a state into the full base layer.
 *
 * <p>Every wall piece this mod places is one: a wall alone up the face of a step, and at its foot
 * the floor layer merged with the wall. A floor alone stays the normal layer. VLP gives 3D layers
 * to hard materials only (stone-like and ores).
 *
 * <p>Found by reflection, so VLP stays an optional dependency: every block of VLP's
 * {@code Layer3DBlock} class is linked to the normal layer it returns from {@code getBaseLayer()}.
 * The 3D blocks are named after the material ({@code grass_3d_layer} for {@code grass_layer}), not
 * after the layer id, so the name alone cannot be used.
 */
public final class Layer3D {
    private Layer3D() {}

    private static final String LAYER_3D_CLASS = "net.fellter.vanillalayerplus.block.Layer3DBlock";

    /** Normal layer -> its 3D variant, and the reverse. Null until built. */
    private static volatile Map<Block, Block> byBaseLayer;
    private static volatile Map<Block, Block> baseLayerOf;

    private static void ensureBuilt() {
        if (byBaseLayer != null) return;
        synchronized (Layer3D.class) {
            if (byBaseLayer != null) return;
            Map<Block, Block> forward = new HashMap<>();
            Map<Block, Block> reverse = new HashMap<>();
            boolean classPresent;
            try {
                Class<?> cls = Class.forName(LAYER_3D_CLASS);
                classPresent = true;
                Method getBaseLayer = cls.getMethod("getBaseLayer");
                for (Block block : BuiltInRegistries.BLOCK) {
                    if (!cls.isInstance(block)) continue;
                    if (getBaseLayer.invoke(block) instanceof Block base) {
                        forward.put(base, block);
                        reverse.put(block, base);
                    }
                }
            } catch (ClassNotFoundException e) {
                classPresent = false;
            } catch (ReflectiveOperationException e) {
                AronaLayersGen.LOGGER.warn("[Layer3D] Could not read VanillaLayer+ 3D layers: {}", e.toString());
                classPresent = false;
            }
            // VLP present but nothing registered yet: too early, try again on the next call.
            if (classPresent && forward.isEmpty()) return;
            baseLayerOf = reverse;
            byBaseLayer = forward;
            if (classPresent) {
                AronaLayersGen.LOGGER.info("[Layer3D] Found {} VanillaLayer+ 3D layers", forward.size());
            }
        }
    }

    /** True when 3D layer injection is enabled and VanillaLayer+ provides 3D layers. */
    public static boolean isActive() {
        if (!LayerConfig.THREE_D_LAYER_INJECTION) return false;
        ensureBuilt();
        Map<Block, Block> map = byBaseLayer;
        return map != null && !map.isEmpty();
    }

    /** The 3D variant of a normal layer block, or null if it has none. */
    public static Block forLayer(Block layer) {
        ensureBuilt();
        Map<Block, Block> map = byBaseLayer;
        return map == null ? null : map.get(layer);
    }

    /** The normal layer a 3D layer is the variant of, or null if {@code block} is not a 3D layer. */
    public static Block baseLayerOf(Block block) {
        ensureBuilt();
        Map<Block, Block> map = baseLayerOf;
        return map == null ? null : map.get(block);
    }

    public static boolean is(Block block) {
        return baseLayerOf(block) != null;
    }

    /** The floor height of a 3D layer ({@code vertical_layers}, 0 for a wall alone), or 0. */
    public static int verticalLayers(BlockState state) {
        IntegerProperty vertical = verticalProperty(state.getBlock());
        return vertical == null ? 0 : state.getValue(vertical);
    }

    /**
     * A 3D layer state: floor {@code verticalLayers} high (0 = a wall alone), wall
     * {@code horizontalLayers} thick against {@code facing}. Kept to 0-7 and 1-7: at 8 the block
     * would be a full cube, which is not a piece this mod places.
     *
     * @param facing the side the wall part is attached to
     * @return null if the block lacks the expected properties
     */
    public static BlockState state(Block block, int verticalLayers, int horizontalLayers, Direction facing, boolean waterlogged) {
        BlockState state = block.defaultBlockState();
        IntegerProperty vertical = verticalProperty(block);
        IntegerProperty width = widthProperty(block);
        Property<?> facingProp = facingProperty(block);
        if (vertical == null || width == null || facingProp == null) return null;
        state = state.setValue(vertical, Math.max(0, Math.min(7, verticalLayers)))
            .setValue(width, Math.max(1, Math.min(7, horizontalLayers)));
        state = withValue(state, facingProp, facing);
        if (state != null && state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, waterlogged);
        }
        return state;
    }

    private static final Map<Block, java.util.Optional<IntegerProperty>> verticalCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Block, java.util.Optional<IntegerProperty>> widthCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Block, java.util.Optional<Property<?>>> facingCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static IntegerProperty verticalProperty(Block block) {
        return verticalCache.computeIfAbsent(block, b -> intProperty(b, "vertical_layers")).orElse(null);
    }

    private static IntegerProperty widthProperty(Block block) {
        return widthCache.computeIfAbsent(block, b -> intProperty(b, "horizontal_layers")).orElse(null);
    }

    private static java.util.Optional<IntegerProperty> intProperty(Block block, String name) {
        for (Property<?> p : block.getStateDefinition().getProperties()) {
            if (p.getName().equals(name) && p instanceof IntegerProperty ip) return java.util.Optional.of(ip);
        }
        return java.util.Optional.empty();
    }

    private static Property<?> facingProperty(Block block) {
        return facingCache.computeIfAbsent(block, b -> {
            for (Property<?> p : b.getStateDefinition().getProperties()) {
                if (p.getName().equals("horizontal_facing")) return java.util.Optional.of(p);
            }
            return java.util.Optional.empty();
        }).orElse(null);
    }

    /** Sets {@code value} if the property accepts it, by matching it among the possible values. */
    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, Object value) {
        for (T possible : property.getPossibleValues()) {
            if (possible.equals(value)) return state.setValue(property, possible);
        }
        return null;
    }
}
