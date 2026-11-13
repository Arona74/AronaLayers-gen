package io.arona74.aronalayersgen.client;


import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Draws an upright log (or a huge mushroom's stem) standing on a layer block down to the layer's
 * top, so a tree that stands on a layer (generated one block higher, or grown from a sapling on the
 * layer) reads as rooted in it rather than floating over it. Posts (fences, walls, panes, bars) get
 * the same, so a fence on a layer reaches the ground.
 *
 * <p>The extension is the block's own side faces, moved into the layer's cell and cut off at the
 * layer's top with their texture cut to match, so the bark continues unbroken from the block above.
 * The layer itself is hidden inside it. Only faces reaching the model's lowest point are extended:
 * a fence's post but not its rails. Whether a block qualifies is PlantGrounding.reachesDown, which
 * the outline and collision use too.
 *
 * <p>Each block state has its own model here, so drawing a post as another state (with sides it
 * joins across a step, or stacked) takes that state's model: every wrapped model is kept by its
 * state in {@link #BASE} as the plugin wraps it.
 */
public class TrunkStemModel extends LayerModel {

    /** The unwrapped model of every state wrapped, filled again on each resource reload. */
    private static final Map<BlockState, BlockStateModel> BASE = new ConcurrentHashMap<>();

    public TrunkStemModel(BlockStateModel wrapped, BlockState state) {
        super(wrapped);
        BASE.put(state, wrapped);
    }

    /** The model of {@code state} as vanilla baked it; this model's own when it was not wrapped. */
    private BlockStateModel base(BlockState state) {
        return BASE.getOrDefault(state, wrapped);
    }

    @Override
    void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos, QuadEmitter emitter) {
        if (LayerConfig.postsLowered() && PlantGrounding.isPostBlock(state.getBlock())) {
            emitLowered(emitter, blockView, pos, state);
            return;
        }
        // Drawn with its level bridges, sides towards a standing post one level up that reaches
        // down beside this one: the post as it would look were that one on its level (a wall's
        // side tall under a wall, its post gone where the wall now runs straight).
        BlockState shown = PlantGrounding.bridged(state, blockView, pos, false);
        emitter.emitWrapped(base(shown), shown, null);

        if (!PlantGrounding.reachesDown(state, blockView, pos)) return;
        float top = PlantGrounding.layerTop(blockView, pos.below());
        if (top >= 1f) return;

        if (PlantGrounding.isPostBlock(state.getBlock())) {
            // A copy of the whole post one block down, cut at the layer's top: post, rails and
            // sides continue into the layer's cell as if a second post stood there. It has this
            // post's level sides and its step sides too (towards a post one level down,
            // PlantGrounding.stepBridges), which then meet that post at its own height.
            int step = PlantGrounding.stepBridges(state, blockView, pos);
            BlockState copy = PlantGrounding.stacked(PlantGrounding.joined(shown, step), step);
            for (BakedQuad quad : allQuads(emitter, copy)) {
                emitClipped(emitter, () -> emitter.fromVanilla(quad, null), -1f, top - 1f, 0f);
            }
            return;
        }

        List<BakedQuad> sides = sides(emitter);
        Float bottom = BOTTOMS.get(state);
        if (bottom == null) {
            bottom = lowestSide(emitter, sides);
            BOTTOMS.put(state, bottom);
        }
        for (BakedQuad quad : sides) {
            emitDown(emitter, () -> emitter.fromVanilla(quad, null), bottom, top - 1f);
        }
    }

    /**
     * A post in PostMode.LOWER: the whole model moved down onto the layer, like a grounded
     * decoration. Where a side joins a post at another height, its end face, culled against the
     * neighbour as if the two still met, is drawn after all, so the step shows a closed end rather
     * than the inside of the side. Posts not on a layer come through here too, for their ends
     * towards a lowered neighbour. A lowered top face is drawn even under a solid block, which no
     * longer reaches down to cover it.
     *
     * <p>A face that must show loses its cull face, so the renderer draws it whatever its neighbour.
     */
    private void emitLowered(QuadEmitter emitter, BlockAndTintGetter blockView, BlockPos pos, BlockState state) {
        float drop = GroundedPlantRender.postDrop(state, blockView, pos);
        // Sides joining a post across a step, drawn as part of the post itself: towards a post
        // lowered into this level, and (for a lowered post) towards one beside its layer.
        BlockState drawn = PlantGrounding.bridged(state, blockView, pos, true);
        // Reaching back up to a block on it (see below), a wall's low sides are drawn tall, as vanilla
        // draws them under a wall, so the whole wall meets that block; the outline follows
        // (PlantGrounding.loweredDrawn).
        boolean reachUp = PlantGrounding.reachesUp(state, blockView, pos, drop);
        if (reachUp) drawn = PlantGrounding.stacked(drawn, 0);
        int open = PlantGrounding.openSides(drawn, blockView, pos, drop);
        if (drop == 0f && open == 0 && drawn == state) {
            emitter.emitWrapped(wrapped, state, null);
            return;
        }
        Predicate<Direction> shown = face -> face == Direction.UP && drop != 0f
            || face != null && face.getAxis().isHorizontal() && (open & (1 << face.get2DDataValue())) != 0;
        emitter.emitWrapped(base(drawn), drawn, quad -> {
            if (drop != 0f) {
                for (int i = 0; i < 4; i++) quad.pos(i, quad.x(i), quad.y(i) + drop, quad.z(i));
            }
            if (shown.test(quad.cullFace())) quad.cullFace(null);
            return true;
        });

        if (!reachUp) return;
        // A copy of the whole post stacked on the lowered one, cut at the cell top, so the post meets
        // the block on it the way a second post would: a fence's rails continue too, and a wall (its
        // sides drawn tall) has no notch. The outline is cut the same way (PlantGrounding.lowered).
        List<BakedQuad> copy = allQuads(emitter, drawn);
        for (BakedQuad quad : copy) {
            emitClipped(emitter, () -> emitter.fromVanilla(quad, null), drop + 1f, 1f + drop, 1f);
        }
        // The cut goes through the copy's post and maybe its rails, whose top faces lie beyond it and
        // are dropped: they are drawn at the cut, so a post under a lantern or a torch is not open.
        List<float[]> crossing = new ArrayList<>();
        for (BakedQuad quad : copy) {
            collectCrossing(emitter, () -> emitter.fromVanilla(quad, null), drop + 1f, 1f, crossing);
        }
        for (BakedQuad quad : copy) {
            emitCap(emitter, () -> emitter.fromVanilla(quad, null), drop + 1f, 1f, crossing);
        }
    }

    /**
     * Records the sides of a quad that cross {@code cut} once moved up by {@code shift}, as
     * {x0, z0, x1, z1}: the parts of the copy the cut at the cell top goes through.
     */
    private static void collectCrossing(QuadEmitter emitter, Runnable load, float shift, float cut, List<float[]> crossing) {
        load.run();
        if (verticalEdges(emitter) == null) return;
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        float x0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float y = emitter.y(i) + shift;
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
            x0 = Math.min(x0, emitter.x(i));
            x1 = Math.max(x1, emitter.x(i));
            z0 = Math.min(z0, emitter.z(i));
            z1 = Math.max(z1, emitter.z(i));
        }
        if (lo < cut - 1.0E-4f && hi > cut + 1.0E-4f) crossing.add(new float[] {x0, z0, x1, z1});
    }

    /**
     * Closes a part the cut at the cell top goes through (a post, a fence rail): its top face, which
     * lies beyond the cut, is drawn at the cut instead, culled against the block above as vanilla's
     * top faces are. A face is a part's top when it lies flat beyond the cut and one of the crossing
     * sides runs along its edge.
     */
    private static void emitCap(QuadEmitter emitter, Runnable load, float shift, float cut, List<float[]> crossing) {
        load.run();
        float y = emitter.y(0) + shift;
        if (y <= cut + 1.0E-4f) return;
        float x0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(emitter.y(i) + shift - y) > 1.0E-4f) return;
            x0 = Math.min(x0, emitter.x(i));
            x1 = Math.max(x1, emitter.x(i));
            z0 = Math.min(z0, emitter.z(i));
            z1 = Math.max(z1, emitter.z(i));
        }
        float e = 1.0E-4f;
        boolean over = false;
        for (float[] side : crossing) {
            if (side[0] >= x0 - e && side[2] <= x1 + e && side[1] >= z0 - e && side[3] <= z1 + e) {
                over = true;
                break;
            }
        }
        if (!over) return;
        for (int i = 0; i < 4; i++) emitter.pos(i, emitter.x(i), cut, emitter.z(i));
        emitter.cullFace(cut >= 1f - e ? Direction.UP : null);
        emitter.emit();
    }

    /** Every quad of the model for {@code state}. */
    private List<BakedQuad> allQuads(QuadEmitter emitter, BlockState state) {
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction face : FACES) quads.addAll(emitter.quadsOf(base(state), face));
        return quads;
    }

    /**
     * Draws a quad moved up by {@code shift}, keeping only what lies between {@code min} and
     * {@code max}: a side face is cut at either bound with its texture cut to match (interpolated
     * along its vertical edges, so it is cut, not squashed); a flat face is kept only strictly
     * inside, so one lying on a bound (against the block it continues) is dropped.
     */
    private static void emitClipped(QuadEmitter emitter, Runnable load, float shift, float min, float max) {
        load.run();
        int[] other = verticalEdges(emitter);
        float[] y = new float[4];
        float[] u = new float[4];
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) {
            y[i] = emitter.y(i) + shift;
            u[i] = emitter.u(i);
            v[i] = emitter.v(i);
        }
        float lo = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
        float hi = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
        float eps = 1.0E-4f;
        if (hi <= min + eps || lo >= max - eps) return;
        if (other == null && (lo < min - eps || hi > max + eps)) return;
        for (int i = 0; i < 4; i++) {
            float yi = y[i];
            if (other != null && (yi < min || yi > max)) {
                int j = other[i];
                float bound = yi < min ? min : max;
                float t = (y[j] - bound) / (y[j] - yi);
                emitter.pos(i, emitter.x(i), bound, emitter.z(i));
                emitter.uv(i, u[j] + (u[i] - u[j]) * t, v[j] + (v[i] - v[j]) * t);
            } else {
                emitter.pos(i, emitter.x(i), yi, emitter.z(i));
            }
        }
        emitter.cullFace(null);
        emitter.emit();
    }

    private static final Direction[] FACES = QuadEmitter.FACES;

    /** Per state, the lowest point of the model's vertical faces (see verticalBottom). */
    private static final Map<BlockState, Float> BOTTOMS = new ConcurrentHashMap<>();

    /**
     * Every quad that can be a side: the culled horizontal faces of a full block (a log) and the
     * unculled faces of a smaller shape. Top and bottom faces are dropped by emitDown, which only
     * extends vertical faces.
     */
    private List<BakedQuad> sides(QuadEmitter emitter) {
        List<BakedQuad> quads = new ArrayList<>(emitter.quadsOf(wrapped, null));
        for (Direction face : Direction.Plane.HORIZONTAL) quads.addAll(emitter.quadsOf(wrapped, face));
        return quads;
    }

    private static float lowestSide(QuadEmitter emitter, List<BakedQuad> sides) {
        float lowest = Float.MAX_VALUE;
        for (BakedQuad quad : sides) {
            emitter.fromVanilla(quad, null);
            lowest = Math.min(lowest, verticalBottom(emitter));
        }
        return lowest;
    }

    /**
     * For each corner of a vertical rectangle (a side face), the corner on the same vertical edge,
     * or null when the quad is anything else (a top, a bottom, a slanted face).
     */
    private static int[] verticalEdges(QuadEmitter quad) {
        int[] other = new int[4];
        for (int i = 0; i < 4; i++) {
            other[i] = -1;
            for (int k = 0; k < 4; k++) {
                if (k != i && quad.x(k) == quad.x(i) && quad.z(k) == quad.z(i) && quad.y(k) != quad.y(i)) other[i] = k;
            }
            if (other[i] < 0) return null;
        }
        return other;
    }

    /** The bottom of a side face, or {@code Float.MAX_VALUE} for any other quad. */
    private static float verticalBottom(QuadEmitter quad) {
        if (verticalEdges(quad) == null) return Float.MAX_VALUE;
        return Math.min(Math.min(quad.y(0), quad.y(1)), Math.min(quad.y(2), quad.y(3)));
    }

    /**
     * Extends a side face whose bottom is the model's {@code bottom} down to {@code target}: copies
     * of it stacked under one another, the last cut off at {@code target} with its texture cut to
     * match (interpolated along each vertical edge, so it is cut, not squashed). A full-height side
     * takes a single copy; a shorter one as many as the gap needs.
     *
     * @param load puts the original quad into the emitter, which each emit clears
     */
    private static void emitDown(QuadEmitter emitter, Runnable load, float bottom, float target) {
        load.run();
        int[] other = verticalEdges(emitter);
        if (other == null) return;
        float[] y = new float[4];
        float[] u = new float[4];
        float[] v = new float[4];
        for (int i = 0; i < 4; i++) {
            y[i] = emitter.y(i);
            u[i] = emitter.u(i);
            v[i] = emitter.v(i);
        }
        float lo = Math.min(Math.min(y[0], y[1]), Math.min(y[2], y[3]));
        float hi = Math.max(Math.max(y[0], y[1]), Math.max(y[2], y[3]));
        float height = hi - lo;
        if (Math.abs(lo - bottom) > 1.0E-4f || height < 1.0E-4f) return;

        for (int copy = 1; copy <= 16; copy++) {
            if (copy > 1) load.run();
            float shift = copy * height;
            boolean last = lo - shift <= target + 1.0E-4f;
            for (int i = 0; i < 4; i++) {
                int j = other[i];
                if (last && y[i] < y[j]) {
                    float t = (y[j] - shift - target) / height;
                    emitter.pos(i, emitter.x(i), target, emitter.z(i));
                    emitter.uv(i, u[j] + (u[i] - u[j]) * t, v[j] + (v[i] - v[j]) * t);
                } else {
                    emitter.pos(i, emitter.x(i), y[i] - shift, emitter.z(i));
                }
            }
            emitter.cullFace(null);
            emitter.emit();
            if (last) return;
        }
    }

    /**
     * The wrapped geometry, plus the layer height the extension is cut at: it depends on the block
     * below. A post depends on its neighbours too (their heights, which sides join across a step),
     * which no key short of the neighbourhood would capture, so a post has none.
     */
    @Override
    public Object createGeometryKey(BlockAndTintGetter blockView, BlockPos pos,
                                    BlockState state, RandomSource random) {
        Object wrappedKey = wrapped.createGeometryKey(blockView, pos, state, random);
        if (wrappedKey == null) return null;
        if (PlantGrounding.isPostBlock(state.getBlock())) return null;
        if (!PlantGrounding.reachesDown(state, blockView, pos)) return wrappedKey;
        return List.of(wrappedKey, PlantGrounding.layerTop(blockView, pos.below()));
    }
}
