package io.arona74.aronalayersgen.client;


import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Predicate;

/**
 * Shifts a plant's quads down so it sits on the surface of a partial-height
 * layer block below it.
 *
 * 1.21.11 replaced BakedModel with BlockStateModel and dropped the item side of
 * the interface entirely, so this wrapper cannot be shared with the older
 * modules. The offset maths itself is version-independent and lives in
 * PlantGrounding.
 */
public class GroundedPlantModel implements BlockStateModel {

    private final BlockStateModel wrapped;

    public GroundedPlantModel(BlockStateModel wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos,
                          BlockState state, RandomSource random, Predicate<Direction> cullTest) {
        float yOffset = GroundedPlantRender.offset(state, blockView, pos);
        if (yOffset != 0f) {
            emitter.pushTransform(quad -> {
                for (int v = 0; v < 4; v++) {
                    quad.pos(v, quad.x(v), quad.y(v) + yOffset, quad.z(v));
                }
                return true;
            });
        }
        wrapped.emitQuads(emitter, blockView, pos, state, random, cullTest);
        if (yOffset != 0f) emitter.popTransform();
    }

    /**
     * Geometry key that accounts for the offset.
     *
     * The key lets the renderer reuse baked geometry between positions that
     * share it. Forwarding the wrapped model's key unchanged would be wrong,
     * because this offset depends on the block *below*: a plant on a layer
     * block would reuse the geometry of the same plant on full ground. But
     * simply returning null -- "never cacheable" -- is expensive now that every
     * grounded plant is wrapped, and silently losing a cache is exactly the
     * kind of regression that shows up as a stall rather than as a wrong
     * picture.
     *
     * So compose the two: same wrapped geometry AND same offset means the same
     * result, which is safe to share. A null from the wrapped model still means
     * not cacheable and has to propagate.
     */
    @Override
    public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos,
                                    BlockState state, RandomSource random) {
        Object wrappedKey = wrapped.createGeometryKey(blockView, pos, state, random);
        if (wrappedKey == null) return null;
        float yOffset = GroundedPlantRender.offset(state, blockView, pos);
        return yOffset == 0f ? wrappedKey : List.of(wrappedKey, yOffset);
    }

    @Override
    public void collectParts(RandomSource random, List<BlockModelPart> parts) {
        wrapped.collectParts(random, parts);
    }

    @Override
    public TextureAtlasSprite particleIcon() {
        return wrapped.particleIcon();
    }

    @Override
    public TextureAtlasSprite particleSprite(BlockAndTintGetter blockView, BlockPos pos, BlockState state) {
        return wrapped.particleSprite(blockView, pos, state);
    }
}
