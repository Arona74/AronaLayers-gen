package io.arona74.aronalayersgen.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockStateWrapperCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkProcessingEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.compat.dh.DhExactFlags;
import io.arona74.aronalayersgen.compat.dh.DhLayers;
import io.arona74.aronalayersgen.compat.dh.DhStats;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distant Horizons: layers in the LODs.
 *
 * <p>DH stores LOD columns in whole blocks and takes every layer for a full opaque block (layers
 * copy their full block's properties), so distant terrain stood a block too high wherever a layer
 * lay. Two levels of support:
 * <ul>
 *   <li><b>Exact heights</b> (dh_exact_layers, DH versions DhMixinPlugin knows): mixins rebuild
 *       DH's meshes in eighths of a block (see compat.dh.DhLayerMesh). Here, partial layers are only
 *       marked not fully opaque, so DH keeps the block under them.</li>
 *   <li><b>Rounding</b>, the fallback through the public API alone: while DH reads a chunk,
 *       {@link DhApiChunkProcessingEvent} swaps each layer for its full block
 *       (dh_layer_rounding_threshold layers or more) or for air / water, so the surface lands
 *       within half a block of the real one.</li>
 * </ul>
 * This class is loaded only when DH is installed (see {@link #register()}).
 */
public final class DistantHorizonsCompat {
    private DistantHorizonsCompat() {}

    /** Opacity DH gives a partial layer in exact mode: just short of LodUtil.BLOCK_FULLY_OPAQUE (16). */
    private static final int PARTIAL_LAYER_OPACITY = 15;

    /** Per layer state: the stand-in state, or empty to leave the block as it is. */
    private static final Map<BlockState, Optional<BlockState>> STAND_IN = new ConcurrentHashMap<>();
    /** DH wrapper of each stand-in, per DH level (wrappers are tied to their level). */
    private static final Map<IDhApiLevelWrapper, Map<BlockState, IDhApiBlockStateWrapper>> WRAPPERS = new ConcurrentHashMap<>();

    public static void register() {
        if (DhExactFlags.active) {
            report(DhApiEventRegister.on(DhApiBlockStateWrapperCreatedEvent.class, new DhApiBlockStateWrapperCreatedEvent() {
                @Override
                public void blockStateWrapperCreated(DhApiEventParam<EventParam> event) {
                    DhStats.WRAPPERS.count();
                    if (event.value.getBlockStateWrapper().getWrappedMcObject() instanceof BlockState state
                            && DhLayers.partialEighths(state) > 0 && !DhLayers.isWaterlogged(state)) {
                        event.value.setOpacity(PARTIAL_LAYER_OPACITY);
                    }
                }
            }), "exact layer heights");
            return;
        }
        report(DhApiEventRegister.on(DhApiChunkProcessingEvent.class, new DhApiChunkProcessingEvent() {
            @Override
            public void blockOrBiomeChangedDuringChunkProcessing(DhApiEventParam<EventParam> event) {
                roundLayer(event.value);
            }
        }), "layer rounding");
    }

    private static void report(DhApiResult<Void> result, String what) {
        if (result.success) {
            AronaLayersGen.LOGGER.info("[DistantHorizons] {} registered for LODs", what);
        } else {
            AronaLayersGen.LOGGER.warn("[DistantHorizons] could not register {}: {}", what, result.message);
        }
    }

    private static void roundLayer(DhApiChunkProcessingEvent.EventParam event) {
        if (!LayerConfig.DH_LAYER_ROUNDING || event.getBlockOverride() != null || event.currentBlock == null) return;
        if (!(event.currentBlock.getWrappedMcObject() instanceof BlockState state)) return;
        if (!LayerPlacementHelper.hasLayerProperty(state) || state.getBlock() == Blocks.SNOW) return;

        BlockState standIn = STAND_IN.computeIfAbsent(state, DistantHorizonsCompat::standInFor).orElse(null);
        if (standIn == null) return;
        IDhApiBlockStateWrapper wrapper = WRAPPERS
            .computeIfAbsent(event.levelWrapper, level -> new ConcurrentHashMap<>())
            .computeIfAbsent(standIn, s -> wrap(s, event.levelWrapper));
        if (wrapper != null) event.setBlockOverride(wrapper);
    }

    private static Optional<BlockState> standInFor(BlockState layer) {
        // Only real layer blocks. Conquest Reforged plants carry a layers property too, and must
        // not be rounded away.
        if (!DhLayers.isLayerBlock(layer.getBlock())) return Optional.empty();
        boolean wall = LayerPlacementHelper.isWallPiece(layer);
        int count = LayerPlacementHelper.readLayerCount(layer);
        if (wall || count < LayerConfig.DH_LAYER_ROUNDING_THRESHOLD) {
            return Optional.of(DhLayers.isWaterlogged(layer) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        Block full = DhLayers.fullBlockOf(layer.getBlock());
        return full == null ? Optional.empty() : Optional.of(full.defaultBlockState());
    }

    private static IDhApiBlockStateWrapper wrap(BlockState state, IDhApiLevelWrapper level) {
        try {
            return state.isAir()
                ? DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper()
                : DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{state}, level);
        } catch (RuntimeException e) {
            AronaLayersGen.LOGGER.debug("[DistantHorizons] no wrapper for {} ({})", state, e.toString());
            return null;
        }
    }
}
