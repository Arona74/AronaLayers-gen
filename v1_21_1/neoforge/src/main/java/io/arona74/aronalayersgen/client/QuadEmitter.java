package io.arona74.aronalayersgen.client;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * NeoForge, Minecraft 1.21.1: the part of the Fabric Rendering API's QuadEmitter the layer models
 * use, so their geometry code reads as it does on Fabric. Holds one quad being edited; emit() bakes
 * it into the list for its cull face, which {@link LayerModel} hands out per side.
 *
 * <p>Vertex data is vanilla's block format: 8 ints per vertex, x y z as float bits at 0-2, colour at
 * 3, u v as float bits at 4-5, light and normal (unused for baked quads, left 0) at 6-7.
 */
final class QuadEmitter {
    private static final int STRIDE = 8;
    private static final float CULL_FACE_EPSILON = 0.00001f;
    static final Direction[] FACES = {null, Direction.NORTH, Direction.SOUTH, Direction.WEST,
        Direction.EAST, Direction.UP, Direction.DOWN};

    private final BakedModel wrapped;
    private final RandomSource random;
    private final long seed;
    private final ModelData data;
    private final RenderType renderType;

    /** Output per cull face: index 0-5 by Direction ordinal, 6 for none. */
    @SuppressWarnings("unchecked")
    private final List<BakedQuad>[] out = new List[7];

    private final int[] vertices = new int[STRIDE * 4];
    private int tintIndex;
    private Direction nominalFace;
    private Direction cullFace;
    private TextureAtlasSprite sprite;
    private boolean shade;
    private boolean ambientOcclusion;

    QuadEmitter(BakedModel wrapped, RandomSource random, long seed, ModelData data, RenderType renderType) {
        this.wrapped = wrapped;
        this.random = random;
        this.seed = seed;
        this.data = data;
        this.renderType = renderType;
        for (int i = 0; i < out.length; i++) out[i] = new ArrayList<>();
        clear();
    }

    /** The block's random, reseeded as vanilla does before each model query. */
    RandomSource random() {
        random.setSeed(seed);
        return random;
    }

    /** The wrapped model's quads for {@code state} on one side, in the pass being built. */
    List<BakedQuad> wrappedQuads(BlockState state, Direction side) {
        return wrapped.getQuads(state, side, random(), data, renderType);
    }

    /**
     * The wrapped model's quads for {@code state}, each passed through {@code transform} (which edits
     * this emitter's quad and returns false to drop it), as FRAPI's pushTransform around
     * emitBlockQuads. Without a transform the quads are kept as they are.
     */
    void emitWrapped(BlockState state, Predicate<QuadEmitter> transform) {
        for (Direction side : FACES) {
            for (BakedQuad quad : wrappedQuads(state, side)) {
                if (transform == null) {
                    out[index(side)].add(quad);
                    continue;
                }
                fromVanilla(quad, side);
                if (transform.test(this)) emit();
                else clear();
            }
        }
    }

    List<BakedQuad> quads(Direction side) {
        return out[index(side)];
    }

    private static int index(Direction side) {
        return side == null ? 6 : side.ordinal();
    }

    // ---- the edited quad ----

    QuadEmitter fromVanilla(BakedQuad quad, Direction cull) {
        System.arraycopy(quad.getVertices(), 0, vertices, 0, vertices.length);
        tintIndex = quad.getTintIndex();
        nominalFace = quad.getDirection();
        cullFace = cull;
        sprite = quad.getSprite();
        shade = quad.isShade();
        ambientOcclusion = quad.hasAmbientOcclusion();
        return this;
    }

    float x(int i) { return Float.intBitsToFloat(vertices[i * STRIDE]); }
    float y(int i) { return Float.intBitsToFloat(vertices[i * STRIDE + 1]); }
    float z(int i) { return Float.intBitsToFloat(vertices[i * STRIDE + 2]); }
    float u(int i) { return Float.intBitsToFloat(vertices[i * STRIDE + 4]); }
    float v(int i) { return Float.intBitsToFloat(vertices[i * STRIDE + 5]); }

    QuadEmitter pos(int i, float x, float y, float z) {
        vertices[i * STRIDE] = Float.floatToRawIntBits(x);
        vertices[i * STRIDE + 1] = Float.floatToRawIntBits(y);
        vertices[i * STRIDE + 2] = Float.floatToRawIntBits(z);
        return this;
    }

    QuadEmitter uv(int i, float u, float v) {
        vertices[i * STRIDE + 4] = Float.floatToRawIntBits(u);
        vertices[i * STRIDE + 5] = Float.floatToRawIntBits(v);
        return this;
    }

    Direction cullFace() { return cullFace; }

    QuadEmitter cullFace(Direction face) {
        cullFace = face;
        return this;
    }

    /** As FRAPI's square: a rectangle on {@code face}, {@code depth} in from it. */
    QuadEmitter square(Direction face, float left, float bottom, float right, float top, float depth) {
        if (Math.abs(depth) < CULL_FACE_EPSILON) {
            cullFace = face;
            depth = 0;
        } else {
            cullFace = null;
        }
        nominalFace = face;
        switch (face) {
            case UP:
                depth = 1 - depth;
                top = 1 - top;
                bottom = 1 - bottom;
            case DOWN:
                pos(0, left, depth, top);
                pos(1, left, depth, bottom);
                pos(2, right, depth, bottom);
                pos(3, right, depth, top);
                break;
            case EAST:
                depth = 1 - depth;
                left = 1 - left;
                right = 1 - right;
            case WEST:
                pos(0, depth, top, left);
                pos(1, depth, bottom, left);
                pos(2, depth, bottom, right);
                pos(3, depth, top, right);
                break;
            case SOUTH:
                depth = 1 - depth;
                left = 1 - left;
                right = 1 - right;
            case NORTH:
                pos(0, 1 - left, top, depth);
                pos(1, 1 - left, bottom, depth);
                pos(2, 1 - right, bottom, depth);
                pos(3, 1 - right, top, depth);
                break;
        }
        return this;
    }

    /** As FRAPI's spriteBake with BAKE_NORMALIZED: maps 0-1 UVs onto {@code sprite}. */
    QuadEmitter spriteBakeNormalized(TextureAtlasSprite sprite) {
        this.sprite = sprite;
        for (int i = 0; i < 4; i++) uv(i, sprite.getU(u(i)), sprite.getV(v(i)));
        return this;
    }

    /** Vertex colours, packed as vanilla's quads store them (-1 for white). */
    QuadEmitter color(int c0, int c1, int c2, int c3) {
        vertices[3] = c0;
        vertices[STRIDE + 3] = c1;
        vertices[2 * STRIDE + 3] = c2;
        vertices[3 * STRIDE + 3] = c3;
        return this;
    }

    /** Bakes the quad into the output for its cull face and starts a new one. */
    void emit() {
        Direction face = nominalFace != null ? nominalFace : Direction.UP;
        out[index(cullFace)].add(new BakedQuad(vertices.clone(), tintIndex, face, sprite, shade, ambientOcclusion));
        clear();
    }

    private void clear() {
        java.util.Arrays.fill(vertices, 0);
        color(-1, -1, -1, -1);
        tintIndex = -1;
        nominalFace = null;
        cullFace = null;
        sprite = null;
        shade = true;
        ambientOcclusion = true;
    }
}
