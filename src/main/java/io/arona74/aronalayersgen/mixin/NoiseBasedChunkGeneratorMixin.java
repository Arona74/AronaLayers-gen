package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.LayerBackend;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import io.arona74.aronalayersgen.injection.VanillaLayerInjector;
import io.arona74.aronalayersgen.injection.PlantConversionHelper;
import io.arona74.aronalayersgen.injection.PreStructureHeightmapStorage;
import io.arona74.aronalayersgen.injection.RTFCompat;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import io.arona74.aronalayersgen.injection.TellusCompat;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to inject layer generation when a LevelChunk is created.
 *
 * Handles two injection modes:
 * - CARVERS: Layers were placed earlier in ChunkGeneratorMixin. This mixin only
 *   runs correction passes and fallback injection.
 * - POST_FEATURES: Primary injection happens here, AFTER all structures/features
 *   are placed. This ensures layers don't appear inside buildings.
 */
@Mixin(LevelChunk.class)
public class NoiseBasedChunkGeneratorMixin {

    @Inject(
        method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
        at = @At("RETURN")
    )
    private void onChunkCreate(ServerLevel world, net.minecraft.world.level.chunk.ProtoChunk protoChunk, LevelChunk.PostLoadProcessor entityLoader, CallbackInfo ci) {
        if (!LayerConfig.LAYER_INJECTION) {
            return;
        }

        try {
            LevelChunk chunk = (LevelChunk)(Object)this;
            ChunkGenerator generator = world.getChunkSource().getGenerator();
            RandomState randomState = RandomStateHolder.noiseConfigFor(world);
            LayerBackend backend = LayerBackend.of(world, generator, randomState);
            if (backend == LayerBackend.NONE) return;

            long stepStart = 0L;
            if (LayerConfig.logChunkInit()) {
                stepStart = System.nanoTime();
                AronaLayersGen.LOGGER.info("[ChunkInit] ENTER {},{} thread={}",
                    Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), Thread.currentThread().getName());
            }

            if (LayerConfig.STRUCTURE_INJECTION) {
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] prepareStructureBounds START {},{}", Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
                LayerPlacementHelper.prepareStructureBoundsWithWorld(chunk, world);
                if (LayerConfig.logChunkInit()) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] prepareStructureBounds DONE {},{} ms={}",
                        Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), (System.nanoTime() - stepStart) / 1_000_000L);
                    stepStart = System.nanoTime();
                }
            }

            if (backend == LayerBackend.TELLUS) {
                // ChunkGeneratorFeaturesMixin is the primary injector for Tellus worlds, but
                // re-run Tellus here as a safety net: for some chunks the WG heightmap is still
                // empty at generateFeatures() time (every column reports noSurface and nothing
                // gets placed). By LevelChunk.<init> the heightmaps are rebuilt, so those chunks
                // are recovered. Chunks already done are effectively a no-op because
                // injectLayerAt skips positions that already hold a non-air block.
                //
                // Deliberately NOT VanillaLayerInjector: it reads vanilla's density field, which
                // does not describe Tellus terrain, so its counts would be fiction stacked on top
                // of the ones Tellus already placed.
                if (TellusCompat.needsRerun(chunk)) {
                    if (LayerConfig.logTellus()) {
                        AronaLayersGen.LOGGER.info("[ChunkInit] Tellus re-run at {},{} — features pass found no surface (empty heightmap)",
                            Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
                    }
                    TellusCompat.injectLayersWithTellus(chunk, generator);
                } else if (LayerConfig.logChunkInit()) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] Tellus already handled {},{} at features time",
                        Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
                }
            } else if (backend == LayerBackend.TERRAFORGED) {
                if (LayerConfig.INJECTION_MODE == LayerConfig.InjectionMode.POST_FEATURES) {
                    // In POST_FEATURES mode, ChunkGeneratorFeaturesMixin is the primary injector
                    // (fires at generateFeatures() RETURN, while the cells are still on the chunk).
                    // This re-run only fills what that pass missed. Never the vanilla injector:
                    // the density field does not describe TerraForged terrain.
                    RTFCompat.injectLayersWithRTF(chunk, randomState);
                    // Re-map only: by LevelChunk.<init> the chunk is fully generated, so any
                    // surface RTF swapped after our POST_FEATURES injection (grass → beach/river
                    // sand) is now final. Re-point each layer's block at the current surface below
                    // it (grass_block_layer on sand → sand_layer) without running the removal
                    // steps, which have never applied in this mode.
                    RTFLayerInjector.correctMismatchedLayers(chunk, true);
                } else {
                    // CARVERS mode: layers placed earlier, run correction pass, then fill chunks
                    // that missed the carvers phase.
                    RTFLayerInjector.correctMismatchedLayers(chunk);
                    RTFCompat.injectLayersWithRTF(chunk, randomState);
                }
            } else if (backend == LayerBackend.VANILLA) {
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] vanilla inject START {},{}", Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
                VanillaLayerInjector.injectLayers(chunk, randomState, generator);
                if (LayerConfig.logChunkInit()) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] vanilla inject DONE {},{} ms={}",
                        Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), (System.nanoTime() - stepStart) / 1_000_000L);
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
            if (backend == LayerBackend.TERRAFORGED && LayerConfig.STRUCTURE_NO_LAYERS) {
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] structure_no_layers START {},{}", Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
                Set<Integer> changedColumns = PreStructureHeightmapStorage.getChangedColumns(chunk);
                if (changedColumns != null && !changedColumns.isEmpty()) {
                    RTFLayerInjector.removeLayersAtColumns(chunk, changedColumns);
                }
                if (LayerConfig.logChunkInit()) {
                    AronaLayersGen.LOGGER.info("[ChunkInit] structure_no_layers DONE {},{} ms={}",
                        Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), (System.nanoTime() - stepStart) / 1_000_000L);
                    stepStart = System.nanoTime();
                }
            }

            // second_pass_cleanup: taper layers around structure gaps.
            // Always called to drain the ThreadLocal; no-op when feature is disabled.
            if (LayerConfig.logSecondPass() && LayerConfig.SECOND_PASS_CLEANUP)
                AronaLayersGen.LOGGER.info("[ChunkInit] second_pass START {},{}", Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
            LayerPlacementHelper.runSecondPassCleanup(chunk);
            if (LayerConfig.logSecondPass() && LayerConfig.SECOND_PASS_CLEANUP) {
                AronaLayersGen.LOGGER.info("[ChunkInit] second_pass DONE {},{} ms={}",
                    Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), (System.nanoTime() - stepStart) / 1_000_000L);
                stepStart = System.nanoTime();
            }

            // Convert vanilla plants above layers to conquest equivalents (CR only)
            if (LayerConfig.PLANT_INJECTION) {
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] plant convert START {},{}", Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
                PlantConversionHelper.convertPlantsAboveLayers(chunk);
                if (LayerConfig.logChunkInit())
                    AronaLayersGen.LOGGER.info("[ChunkInit] plant convert DONE {},{} ms={}",
                        Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), (System.nanoTime() - stepStart) / 1_000_000L);
            }

            if (LayerConfig.logChunkInit())
                AronaLayersGen.LOGGER.info("[ChunkInit] EXIT {},{}", Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
        } catch (Exception e) {
            AronaLayersGen.LOGGER.error("Failed to process layers for chunk", e);
        } finally {
            LayerPlacementHelper.clearStructureBounds();
        }
    }
}
