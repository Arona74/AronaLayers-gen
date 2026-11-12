package io.arona74.aronalayersgen.client;

import com.mojang.blaze3d.platform.Transparency;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.TriState;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * NeoForge, Minecraft 26.1.2: the part of the Fabric Rendering API's QuadEmitter the layer models
 * use, so their geometry code reads as it does on Fabric. Holds one quad being edited; emit() bakes
 * it into the current output part, in the list for its cull face.
 *
 * <p>Output is a list of model parts, one per part of each wrapped model drawn ({@link #emitWrapped}),
 * each keeping its source's ambient occlusion. Since 26.1 the render layer is per quad (its
 * MaterialInfo), so quads copied from the wrapped model keep theirs; quads made from scratch
 * ({@link #square}) take the material of the first wrapped quad seen, with their own sprite. Quads
 * emitted on their own go into the part drawn last. The renderer culls a part's per-face lists
 * against the neighbours and always draws its unculled list, as FRAPI does with a quad's cull face.
 */
final class QuadEmitter {
    private static final float CULL_FACE_EPSILON = 0.00001f;
    static final Direction[] FACES = {null, Direction.NORTH, Direction.SOUTH, Direction.WEST,
        Direction.EAST, Direction.UP, Direction.DOWN};

    private final BlockAndTintGetter level;
    private final BlockPos pos;
    private final RandomSource random;
    private final long seed;

    private final List<BlockStateModelPart> parts = new ArrayList<>();
    private Part current;
    /** The material of the first wrapped quad seen, for quads made from scratch. */
    private BakedQuad.MaterialInfo template;

    private final float[] xyz = new float[12];
    private final float[] uvs = new float[8];
    private Direction nominalFace;
    private Direction cullFace;
    private BakedQuad.MaterialInfo material;
    private BakedNormals normals;
    private BakedColors colors;

    QuadEmitter(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
        this.level = level;
        this.pos = pos;
        this.random = random;
        this.seed = state.getSeed(pos);
        clear();
    }

    /** The block's random, reseeded as the chunk renderer does before each model query. */
    RandomSource random() {
        random.setSeed(seed);
        return random;
    }

    /** What was drawn, as model parts for the renderer. */
    List<BlockStateModelPart> parts() {
        return parts;
    }

    /**
     * Draws {@code model} as {@code shown} here, each quad passed through {@code transform} (which
     * edits this emitter's quad and returns false to drop it), as FRAPI's pushTransform around
     * emitQuads. Without a transform the parts are kept as they are.
     */
    void emitWrapped(BlockStateModel model, BlockState shown, Predicate<QuadEmitter> transform) {
        List<BlockStateModelPart> source = new ArrayList<>();
        model.collectParts(level, pos, shown, random(), source);
        for (BlockStateModelPart part : source) {
            if (template == null) {
                for (Direction side : FACES) {
                    List<BakedQuad> quads = part.getQuads(side);
                    if (!quads.isEmpty()) {
                        template = quads.get(0).materialInfo();
                        break;
                    }
                }
            }
            if (transform == null) {
                parts.add(part);
                current = null;
                continue;
            }
            current = new Part(part);
            parts.add(current);
            for (Direction side : FACES) {
                for (BakedQuad quad : part.getQuads(side)) {
                    fromVanilla(quad, side);
                    if (transform.test(this)) emit();
                    else clear();
                }
            }
        }
    }

    /** Every quad of {@code model} for one side, without a level (as FRAPI's collectParts). */
    List<BakedQuad> quadsOf(BlockStateModel model, Direction side) {
        List<BlockStateModelPart> source = new ArrayList<>();
        model.collectParts(random(), source);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : source) quads.addAll(part.getQuads(side));
        return quads;
    }

    // ---- the edited quad ----

    QuadEmitter fromVanilla(BakedQuad quad, Direction cull) {
        for (int i = 0; i < 4; i++) {
            var p = quad.position(i);
            xyz[i * 3] = p.x();
            xyz[i * 3 + 1] = p.y();
            xyz[i * 3 + 2] = p.z();
            long uv = quad.packedUV(i);
            uvs[i * 2] = UVPair.unpackU(uv);
            uvs[i * 2 + 1] = UVPair.unpackV(uv);
        }
        nominalFace = quad.direction();
        cullFace = cull;
        material = quad.materialInfo();
        normals = quad.bakedNormals();
        colors = quad.bakedColors();
        return this;
    }

    float x(int i) { return xyz[i * 3]; }
    float y(int i) { return xyz[i * 3 + 1]; }
    float z(int i) { return xyz[i * 3 + 2]; }
    float u(int i) { return uvs[i * 2]; }
    float v(int i) { return uvs[i * 2 + 1]; }

    QuadEmitter pos(int i, float x, float y, float z) {
        xyz[i * 3] = x;
        xyz[i * 3 + 1] = y;
        xyz[i * 3 + 2] = z;
        return this;
    }

    QuadEmitter uv(int i, float u, float v) {
        uvs[i * 2] = u;
        uvs[i * 2 + 1] = v;
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
        normals = BakedNormals.UNSPECIFIED;
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

    /**
     * As FRAPI's materialBake with BAKE_NORMALIZED: maps 0-1 UVs onto the material's sprite and
     * gives the quad that sprite, drawn the way the wrapped model's quads are (untinted).
     */
    QuadEmitter materialBakeNormalized(Material.Baked baked) {
        TextureAtlasSprite sprite = baked.sprite();
        for (int i = 0; i < 4; i++) uv(i, sprite.getU(u(i)), sprite.getV(v(i)));
        material = template != null
            ? new BakedQuad.MaterialInfo(sprite, template.layer(), template.itemRenderType(), -1,
                template.shade(), template.lightEmission(), template.ambientOcclusion())
            : BakedQuad.MaterialInfo.of(baked, Transparency.NONE, -1, true, 0, true);
        return this;
    }

    /** Vertex colours, ARGB (-1 for white). */
    QuadEmitter color(int c0, int c1, int c2, int c3) {
        colors = c0 == -1 && c1 == -1 && c2 == -1 && c3 == -1 ? BakedColors.DEFAULT : BakedColors.of(c0, c1, c2, c3);
        return this;
    }

    /** Bakes the quad into the current part, in the list for its cull face, and starts a new one. */
    void emit() {
        if (material == null) {
            clear();
            return;
        }
        if (current == null) {
            current = new Part();
            parts.add(current);
        }
        Direction face = nominalFace != null ? nominalFace : Direction.UP;
        BakedQuad quad = new BakedQuad(
            vertex(0), vertex(1), vertex(2), vertex(3),
            UVPair.pack(uvs[0], uvs[1]), UVPair.pack(uvs[2], uvs[3]),
            UVPair.pack(uvs[4], uvs[5]), UVPair.pack(uvs[6], uvs[7]),
            face, material, normals, colors);
        current.add(index(cullFace), quad);
        clear();
    }

    private Vector3f vertex(int i) {
        return new Vector3f(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
    }

    private void clear() {
        java.util.Arrays.fill(xyz, 0f);
        java.util.Arrays.fill(uvs, 0f);
        nominalFace = null;
        cullFace = null;
        material = null;
        normals = BakedNormals.UNSPECIFIED;
        colors = BakedColors.DEFAULT;
    }

    private static int index(Direction side) {
        return side == null ? 6 : side.ordinal();
    }

    /** A drawn part: quads per cull face, with the ambient occlusion of the part it came from. */
    private static final class Part implements BlockStateModelPart {
        @SuppressWarnings("unchecked")
        private final List<BakedQuad>[] quads = new List[7];
        private final boolean useAmbientOcclusion;
        private final TriState ambientOcclusion;
        private Material.Baked particle;
        private int flags;

        Part(BlockStateModelPart source) {
            for (int i = 0; i < quads.length; i++) quads[i] = new ArrayList<>();
            useAmbientOcclusion = source.useAmbientOcclusion();
            ambientOcclusion = source.ambientOcclusion();
            particle = source.particleMaterial();
        }

        /** For quads emitted after an unchanged part: default lighting. */
        Part() {
            for (int i = 0; i < quads.length; i++) quads[i] = new ArrayList<>();
            useAmbientOcclusion = true;
            ambientOcclusion = TriState.DEFAULT;
        }

        void add(int index, BakedQuad quad) {
            quads[index].add(quad);
            flags |= quad.materialInfo().flags();
            if (particle == null) particle = new Material.Baked(quad.materialInfo().sprite(), false);
        }

        @Override
        public List<BakedQuad> getQuads(Direction side) {
            return quads[index(side)];
        }

        @Override
        public boolean useAmbientOcclusion() {
            return useAmbientOcclusion;
        }

        @Override
        public TriState ambientOcclusion() {
            return ambientOcclusion;
        }

        @Override
        public Material.Baked particleMaterial() {
            return particle;
        }

        @Override
        public int materialFlags() {
            return flags;
        }
    }
}
