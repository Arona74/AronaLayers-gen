package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import io.arona74.aronalayersgen.injection.PlantConversionHelper;
import io.arona74.aronalayersgen.injection.PreStructureHeightmapStorage;
import io.arona74.aronalayersgen.injection.RTFCompat;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import io.arona74.aronalayersgen.injection.VanillaLayerInjector;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Mixin on ChunkGenerator.generateFeatures() to inject layers in POST_FEATURES mode.
 *
 * This hook fires during both normal chunk generation AND when ResetChunksCommand
 * regenerates an existing chunk. The vanilla WorldChunk constructor hook in
 * NoiseBasedChunkGeneratorMixin does NOT fire when a chunk is reset, because
 * ResetChunksCommand reuses the existing WorldChunk object (it zeros all blocks and
 * re-runs the generation pipeline in-place). This mixin closes that gap.
 *
 * For normal first-time generation: both this hook and WorldChunk.<init> fire.
 * The WorldChunk.<init> injection becomes effectively a no-op because injectLayerAt()
 * skips positions that already have a non-air block (the layer block placed here).
 */
@Mixin(ChunkGenerator.class)
public class ChunkGeneratorFeaturesMixin {

    @Inject(
        method = "generateFeatures",
        at = @At("RETURN")
    )
    private void onGenerateFeaturesComplete(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor, CallbackInfo ci) {
        if (!LayerConfig.LAYER_INJECTION && !LayerConfig.RTF_LAYER_INJECTION) {
            return;
        }
        if (LayerConfig.INJECTION_MODE != LayerConfig.InjectionMode.POST_FEATURES) {
            return;
        }

        try {
            ChunkRegion chunkRegion = null;
            ServerWorld serverWorld = null;
            if (world instanceof ChunkRegion region) {
                chunkRegion = region;
                @SuppressWarnings("deprecation")
                ServerWorld regionWorld = region.toServerWorld();
                serverWorld = regionWorld;
            } else if (world instanceof ServerWorld sw) {
                serverWorld = sw;
            }

            if (serverWorld != null && RandomStateHolder.getWorldSeed() == Long.MIN_VALUE) {
                RandomStateHolder.setWorldSeed(serverWorld.getSeed());
            }

            if (LayerConfig.STRUCTURE_INJECTION) {
                // During normal generation world is a ChunkRegion.
                // region.getChunk() works for ProtoChunks (neighbouring chunks are not yet
                // WorldChunks at FEATURES time), so cross-chunk structure starts and footprints
                // are resolved correctly. Fall back to the ServerWorld path only for reset
                // chunks where world arrives as a plain ServerWorld.
                if (chunkRegion != null) {
                    LayerPlacementHelper.prepareStructureBounds(chunk, chunkRegion);
                } else {
                    LayerPlacementHelper.prepareStructureBoundsWithWorld(chunk, serverWorld);
                }
            }

            if (LayerConfig.RTF_LAYER_INJECTION) {
                boolean rtfAvailable = RTFCompat.isRTFAvailable() && RandomStateHolder.hasRTFRandomState();

                if (rtfAvailable) {
                    boolean rtfSuccess = RTFCompat.injectLayersWithRTF(chunk, RandomStateHolder.getRandomState());
                    if (!rtfSuccess && LayerConfig.LAYER_INJECTION) {
                        AronaLayersGen.LOGGER.warn("[GenerateFeatures] RTF tile miss at {},{} — falling back to vanilla injection",
                            chunk.getPos().x, chunk.getPos().z);
                        VanillaLayerInjector.injectLayers(chunk, null);
                    }
                } else if (LayerConfig.LAYER_INJECTION) {
                    VanillaLayerInjector.injectLayers(chunk, null);
                }
            } else {
                VanillaLayerInjector.injectLayers(chunk, null);
            }

            if (LayerConfig.STRUCTURE_SKIP_ELEVATED) {
                PreStructureHeightmapStorage.discardSurfaceSnapshot(chunk);
            }

            if (LayerConfig.RTF_LAYER_INJECTION && LayerConfig.STRUCTURE_NO_LAYERS) {
                Set<Integer> changedColumns = PreStructureHeightmapStorage.getChangedColumns(chunk);
                if (changedColumns != null && !changedColumns.isEmpty()) {
                    RTFLayerInjector.removeLayersAtColumns(chunk, changedColumns);
                }
            }

            LayerPlacementHelper.runSecondPassCleanup(chunk);

            if (LayerConfig.PLANT_INJECTION) {
                PlantConversionHelper.convertPlantsAboveLayers(chunk);
            }

        } catch (Exception e) {
            AronaLayersGen.LOGGER.error("Failed to process layers in generateFeatures for chunk {},{}", chunk.getPos().x, chunk.getPos().z, e);
        } finally {
            LayerPlacementHelper.clearStructureBounds();
        }
    }
}
