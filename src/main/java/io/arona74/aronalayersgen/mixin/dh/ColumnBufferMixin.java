/*
 * Adapted from Geostep (verdantlayers) 1.3, MIT License, Copyright (c) 2026 Verdant Layers contributors.
 * The full license text is in compat/dh/DhLayerMesh.java and THIRD_PARTY_NOTICES.md.
 */
package io.arona74.aronalayersgen.mixin.dh;

import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.arona74.aronalayersgen.compat.dh.DhEighths;
import io.arona74.aronalayersgen.compat.dh.DhLayerMesh;
import io.arona74.aronalayersgen.compat.dh.DhStats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carries a section's eighth-unit flag to its mesh builder (vertex packing, LodVertexMixin) and to
 * the culling heights computed while the mesh is built (ColumnBoxMixin).
 *
 * <p>The thread flag is set at the start of every build, eighths or not, so one that ended in an
 * exception cannot leave a stale value for the next.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.ColumnRenderBufferBuilder", remap = false)
public abstract class ColumnBufferMixin {

    @Inject(method = "makeLodRenderData", at = @At("HEAD"), remap = false)
    private static void aronalayersgen$begin(LodQuadBuilder builder, ColumnRenderSource source, IClientLevelWrapper level,
                                             ColumnRenderSource[] adjacent, boolean[] sameDetail, CallbackInfo ci) {
        boolean eighths = ((DhEighths) source).aronalayersgen$eighths();
        ((DhEighths) builder).aronalayersgen$eighths(eighths);
        DhLayerMesh.BUILDING_EIGHTHS.set(eighths);
        TILE_CACHE.get().clear();
        DhStats.STARTS.get()[DhStats.SLOT_MESH] = System.nanoTime();
    }

    /**
     * Face tile ids per texture set, for the build running on this thread. DH asks its registry once
     * per data point, through a synchronized method: with several sections building at once the
     * threads queue on that lock (an eighth-unit section has more data points, which tips it over).
     * A set's tile ids do not change while a build runs, so one lookup per set and build is enough.
     */
    private static final ThreadLocal<Int2ObjectOpenHashMap<short[]>> TILE_CACHE = ThreadLocal.withInitial(Int2ObjectOpenHashMap::new);

    @WrapOperation(
        method = "makeLodRenderData",
        at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/dataObjects/render/textures/BlockTextureRegistry;getFaceTileIds(I)[S", remap = false),
        remap = false
    )
    private static short[] aronalayersgen$cachedTiles(BlockTextureRegistry registry, int setId, Operation<short[]> original) {
        Int2ObjectOpenHashMap<short[]> cache = TILE_CACHE.get();
        if (cache.containsKey(setId)) return cache.get(setId);
        long start = System.nanoTime();
        short[] tiles = original.call(registry, setId);
        DhStats.TEXTURES.add(start);
        cache.put(setId, tiles);
        return tiles;
    }

    /**
     * Each column view cut down to its last real data point. DH sizes a view to the section's slot
     * count and its adjacent-face pass walks every slot of the neighbour, empty ones included, for
     * every face of every data point. Refine widens a section's slots to fit its most crowded column,
     * which multiplied that pass across the whole section. Equivalent output: empty slots are skipped
     * by that pass, and an empty point "below" already falls back to the void point.
     */
    @WrapOperation(
        method = "makeLodRenderData",
        at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/dataObjects/render/ColumnRenderSource;populateColumnView(Lcom/seibel/distanthorizons/core/dataObjects/render/columnViews/ColumnRenderView;II)V", remap = false),
        remap = false
    )
    private static void aronalayersgen$trimView(ColumnRenderSource source, ColumnRenderView view, int x, int z, Operation<Void> original) {
        original.call(source, view, x, z);
        if (DhStats.skipTrim) return;
        int size = view.size;
        while (size > 0) {
            long point = view.get(size - 1);
            if (RenderDataPointUtil.doesDataPointExist(point) && !DhLayerMesh.meshZeroHeight(point)) break;
            size--;
        }
        view.size = size;
    }

    @WrapOperation(
        method = "makeLodRenderData",
        at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodQuadBuilder;mergeQuads()V", remap = false),
        remap = false
    )
    private static void aronalayersgen$timeMerge(LodQuadBuilder builder, Operation<Void> original) {
        long start = System.nanoTime();
        original.call(builder);
        DhStats.MERGE.add(start);
        DhStats.QUADS.add(builder.getCurrentOpaqueQuadsCount() + builder.getCurrentTransparentQuadsCount());
    }

    @Inject(method = "makeLodRenderData", at = @At("RETURN"), remap = false)
    private static void aronalayersgen$end(LodQuadBuilder builder, ColumnRenderSource source, IClientLevelWrapper level,
                                           ColumnRenderSource[] adjacent, boolean[] sameDetail, CallbackInfo ci) {
        DhLayerMesh.BUILDING_EIGHTHS.remove();
        DhStats.MESH.add(DhStats.STARTS.get()[DhStats.SLOT_MESH]);
    }
}
