package io.arona74.aronalayersgen.client;


import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws an upright log (or a huge mushroom's stem) standing on a layer block down to the layer's top, so a tree that stands on
 * a layer (generated one block higher, or grown from a sapling on the layer) reads as rooted in it
 * rather than floating over it. Posts (fences, walls, panes, bars) get the same, so a fence
 * on a layer reaches the ground.
 *
 * <p>The extension is the block's own side faces, moved into the layer's cell and cut off at the
 * layer's top with their texture cut to match, so the bark continues unbroken from the block above.
 * The layer itself is hidden inside it. Only faces reaching the model's lowest point are extended:
 * a fence's post but not its rails. Whether a block qualifies is PlantGrounding.reachesDown, which
 * the outline and collision use too.
 */
public class TrunkStemModel extends LayerModel {

    public TrunkStemModel(BakedModel wrapped) {
        super(wrapped);
    }

    @Override
    void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos, QuadEmitter emitter) {
        if (LayerConfig.postsLowered() && PlantGrounding.isPostBlock(state.getBlock())) {
            emitLowered(blockView, state, pos, emitter);
            return;
        }
        // Drawn with its level bridges, sides towards a standing post one level up that reaches
        // down beside this one: the post as it would look were that one on its level (a wall's
        // side tall under a wall, its post gone where the wall now runs straight).
        BlockState shown = PlantGrounding.bridged(state, blockView, pos, false);
        emitter.emitWrapped(shown, null);

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

        List<BakedQuad> sides = sides(emitter, state);
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
     */
    private void emitLowered(BlockAndTintGetter blockView, BlockState state, BlockPos pos, QuadEmitter emitter) {
        float drop = GroundedPlantRender.postDrop(state, blockView, pos);
        // Sides joining a post across a step, drawn as part of the post itself: towards a post
        // lowered into this level, and (for a lowered post) towards one beside its layer.
        BlockState drawn = PlantGrounding.bridged(state, blockView, pos, true);
        int open = PlantGrounding.openSides(drawn, blockView, pos, drop);
        if (drop == 0f && open == 0 && drawn == state) {
            emitter.emitWrapped(state, null);
            return;
        }
        emitter.emitWrapped(drawn, quad -> {
            if (drop != 0f) {
                for (int i = 0; i < 4; i++) quad.pos(i, quad.x(i), quad.y(i) + drop, quad.z(i));
            }
            Direction cull = quad.cullFace();
            if (cull == Direction.UP && drop != 0f
                    || cull != null && cull.getAxis().isHorizontal() && (open & (1 << cull.get2DDataValue())) != 0) {
                quad.cullFace(null);
            }
            return true;
        });

        if (!PlantGrounding.reachesUp(state, blockView, pos, drop)) return;
        // A copy of the whole post stacked on the lowered one, cut at the cell top, so the post
        // reaches the block on it the way a second post would.
        for (BakedQuad quad : allQuads(emitter, PlantGrounding.stacked(drawn, 0))) {
            emitClipped(emitter, () -> emitter.fromVanilla(quad, null), drop + 1f, 1f + drop, 1f);
        }
    }

    /** Every quad of the model for {@code state}. */
    private static List<BakedQuad> allQuads(QuadEmitter emitter, BlockState state) {
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction face : FACES) quads.addAll(emitter.wrappedQuads(state, face));
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
     * unculled faces of a smaller shape (a fence post, a wall, a pane). Top and bottom faces are
     * dropped by emitDown, which only extends vertical faces.
     */
    private static List<BakedQuad> sides(QuadEmitter emitter, BlockState state) {
        List<BakedQuad> quads = new ArrayList<>(emitter.wrappedQuads(state, null));
        for (Direction face : Direction.Plane.HORIZONTAL) {
            quads.addAll(emitter.wrappedQuads(state, face));
        }
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
}
