package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import io.arona74.aronalayersgen.injection.VanillaLayerInjector;
import io.arona74.aronalayersgen.injection.PlantConversionHelper;
import io.arona74.aronalayersgen.injection.PreStructureHeightmapStorage;
import io.arona74.aronalayersgen.injection.RTFCompat;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import io.arona74.aronalayersgen.injection.TellusCompat;
import java.util.Set;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to inject layer generation when a WorldChunk is created.
 *
 * Handles two injection modes:
 * - CARVERS: Layers were placed earlier in ChunkGeneratorMixin. This mixin only
 *   runs correction passes and fallback injection.
 * - POST_FEATURES: Primary injection happens here, AFTER all structures/features
 *   are placed. This ensures layers don't appear inside buildings.
 */
@Mixin(WorldChunk.class)
public class NoiseBasedChunkGeneratorMixin {

    @Inject(
        method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V",
        at = @At("RETURN")
    )
    private void onChunkCreate(ServerWorld world, net.minecraft.world.chunk.ProtoChunk protoChunk, WorldChunk.EntityLoader entityLoader, CallbackInfo ci) {
        if (!LayerConfig.LAYER_INJECTION && !LayerConfig.RTF_LAYER_INJECTION && !LayerConfig.TELLUS_LAYER_INJECTION) {
            return;
        }

        try {
            WorldChunk chunk = (WorldChunk)(Object)this;

            long stepStart = 0L;
            if (LayerConfig.logChunkInit()) {
                stepStart = System.nanoTime();
                AronaLayersGen.LOGGER.info("[ChunkInit] ENTER {},{} thread={}",
                    chunk.getPos().x, chunk.getPos().z, Thread.currentThread().getName());
            }

            if (LayerConfig.STRUCTURE_INJECTION) {
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] prepareStructureBounds START {},{}", chunk.getPos().x, chunk.getPos().z);
                LayerPlacementHelper.prepareStructureBoundsWithWorld(chunk, world);
                if (LayerConfig.logChunkInit()) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] prepareStructureBounds DONE {},{} ms={}",
                        chunk.getPos().x, chunk.getPos().z, (System.nanoTime() - stepStart) / 1_000_000L);
                    stepStart = System.nanoTime();
                }
            }

            if (LayerConfig.TELLUS_LAYER_INJECTION && TellusCompat.isAvailable()) {
                // ChunkGeneratorFeaturesMixin is the primary injector for Tellus worlds, but
                // re-run Tellus here as a safety net: for some chunks the WG heightmap is still
                // empty at generateFeatures() time (every column reports noSurface and nothing
                // gets placed). By WorldChunk.<init> the heightmaps are rebuilt, so those chunks
                // are recovered. Chunks already done are effectively a no-op because
                // injectLayerAt skips positions that already hold a non-air block.
                //
                // Deliberately NOT VanillaLayerInjector: that one is slope/edge based and would
                // stack a second set of layers on every riser, which is the double injection that
                // caused the "edges getting extra layers" artifact.
                if (TellusCompat.needsRerun(chunk)) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] Tellus re-run at {},{} — features pass found no surface (empty heightmap)",
                        chunk.getPos().x, chunk.getPos().z);
                    TellusCompat.injectLayersWithTellus(chunk, world.getChunkManager().getChunkGenerator());
                } else if (LayerConfig.logChunkInit()) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] Tellus already handled {},{} at features time",
                        chunk.getPos().x, chunk.getPos().z);
                }
            } else if (LayerConfig.RTF_LAYER_INJECTION) {
                boolean rtfAvailable = RTFCompat.isRTFAvailable() && RandomStateHolder.hasRTFRandomState();
                boolean isPostFeaturesMode = LayerConfig.INJECTION_MODE == LayerConfig.InjectionMode.POST_FEATURES;

                if (rtfAvailable) {
                    if (isPostFeaturesMode) {
                        // In POST_FEATURES mode, ChunkGeneratorFeaturesMixin is the primary injector
                        // (fires at generateFeatures() RETURN, while the tile is still in cache).
                        // On NeoForge+ETcomehome the tile is dropped before WorldChunk.<init> fires,
                        // so the RTF attempt here will always miss. Do not run the vanilla fallback:
                        // ChunkGeneratorFeaturesMixin already placed RTF layers (or its own vanilla
                        // fallback). Calling vanilla here would overwrite correct RTF layers.
                        RTFCompat.injectLayersWithRTF(chunk, RandomStateHolder.getRandomState());
                    } else {
                        // CARVERS mode: layers placed earlier, run correction pass
                        RTFLayerInjector.correctMismatchedLayers(chunk);
                        // Fallback injection for chunks that missed the carvers phase
                        boolean rtfSuccess = RTFCompat.injectLayersWithRTF(chunk, RandomStateHolder.getRandomState());
                        if (!rtfSuccess && LayerConfig.LAYER_INJECTION) {
                            AronaLayersGen.LOGGER.warn("[ChunkInit] RTF tile miss at {},{} (CARVERS mode) — falling back to vanilla injection",
                                chunk.getPos().x, chunk.getPos().z);
                            VanillaLayerInjector.injectLayers(chunk, null);
                        }
                    }
                } else if (LayerConfig.LAYER_INJECTION) {
                    // RTF requested but not available: fall back to vanilla injection
                    if (LayerConfig.logChunkInit())
                        AronaLayersGen.LOGGER.info("[ChunkInit] vanilla inject START {},{}", chunk.getPos().x, chunk.getPos().z);
                    VanillaLayerInjector.injectLayers(chunk, null);
                    if (LayerConfig.logChunkInit()) {
                        AronaLayersGen.LOGGER.info("[ChunkInit] vanilla inject DONE {},{} ms={}",
                            chunk.getPos().x, chunk.getPos().z, (System.nanoTime() - stepStart) / 1_000_000L);
                        stepStart = System.nanoTime();
                    }
                }
            } else if (LayerConfig.LAYER_INJECTION) {
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] vanilla inject START {},{}", chunk.getPos().x, chunk.getPos().z);
                VanillaLayerInjector.injectLayers(chunk, null);
                if (LayerConfig.logChunkInit()) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] vanilla inject DONE {},{} ms={}",
                        chunk.getPos().x, chunk.getPos().z, (System.nanoTime() - stepStart) / 1_000_000L);
                    stepStart = System.nanoTime();
                }
            }

            // structure_skip_elevated uses the surface-phase snapshot; discard it now that
            // injection is complete. This is separate from the carvers-phase snapshot used
            // by structure_no_layers, so cleanup is unconditional when the feature is active.
            if (LayerConfig.STRUCTURE_SKIP_ELEVATED) {
                PreStructureHeightmapStorage.discardSurfaceSnapshot(chunk);
            }

            // structure_no_layers: remove layers where structures changed the terrain
            if (LayerConfig.RTF_LAYER_INJECTION && LayerConfig.STRUCTURE_NO_LAYERS) {
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] structure_no_layers START {},{}", chunk.getPos().x, chunk.getPos().z);
                Set<Integer> changedColumns = PreStructureHeightmapStorage.getChangedColumns(chunk);
                if (changedColumns != null && !changedColumns.isEmpty()) {
                    RTFLayerInjector.removeLayersAtColumns(chunk, changedColumns);
                }
                if (LayerConfig.logChunkInit()) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] structure_no_layers DONE {},{} ms={}",
                        chunk.getPos().x, chunk.getPos().z, (System.nanoTime() - stepStart) / 1_000_000L);
                    stepStart = System.nanoTime();
                }
            }

            // second_pass_cleanup: taper layers around structure gaps.
            // Always called to drain the ThreadLocal; no-op when feature is disabled.
            if (LayerConfig.logSecondPass() && LayerConfig.SECOND_PASS_CLEANUP)
                AronaLayersGen.LOGGER.info("[ChunkInit] second_pass START {},{}", chunk.getPos().x, chunk.getPos().z);
            LayerPlacementHelper.runSecondPassCleanup(chunk);
            if (LayerConfig.logSecondPass() && LayerConfig.SECOND_PASS_CLEANUP) {
                AronaLayersGen.LOGGER.info("[ChunkInit] second_pass DONE {},{} ms={}",
                    chunk.getPos().x, chunk.getPos().z, (System.nanoTime() - stepStart) / 1_000_000L);
                stepStart = System.nanoTime();
            }

            // Convert vanilla plants above layers to conquest equivalents (CR only)
            if (LayerConfig.PLANT_INJECTION) {
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] plant convert START {},{}", chunk.getPos().x, chunk.getPos().z);
                PlantConversionHelper.convertPlantsAboveLayers(chunk);
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] plant convert DONE {},{} ms={}",
                        chunk.getPos().x, chunk.getPos().z, (System.nanoTime() - stepStart) / 1_000_000L);
            }

            if (LayerConfig.logChunkInit())
                AronaLayersGen.LOGGER.info("[ChunkInit] EXIT {},{}", chunk.getPos().x, chunk.getPos().z);
        } catch (Exception e) {
            AronaLayersGen.LOGGER.error("Failed to process layers for chunk", e);
        } finally {
            LayerPlacementHelper.clearStructureBounds();
        }
    }
}
