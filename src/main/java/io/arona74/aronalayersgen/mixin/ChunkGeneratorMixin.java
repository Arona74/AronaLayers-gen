package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.PreStructureHeightmapStorage;
import io.arona74.aronalayersgen.injection.RTFCompat;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to inject layer generation after carving but before features.
 * Ensures layers are placed before plants/trees are generated (CARVERS mode).
 *
 * Also captures heightmap snapshots used by structure-aware layer placement:
 *
 * buildSurface() → surface snapshot (for STRUCTURE_SKIP_ELEVATED)
 *   Captured BEFORE carvers and before any C2ME-parallel village features can
 *   run. Minecraft's dependency chain guarantees that no neighboring chunk's
 *   FEATURES phase can have started when this chunk is still at SURFACE status,
 *   so this snapshot is always uncontaminated by village beard-fill terrain
 *   adaptation, even under aggressive parallel worldgen (C2ME).
 *
 * carve() → carvers snapshot (for STRUCTURE_NO_LAYERS)
 *   Captured after caves are carved but before features. May be contaminated
 *   under C2ME for structures that span chunk boundaries, but still usefully
 *   detects most structure-placed block patterns (threshold >= 2 blocks).
 */
@Mixin(NoiseChunkGenerator.class)
public class ChunkGeneratorMixin {

    /**
     * Capture the surface-phase snapshot for STRUCTURE_SKIP_ELEVATED.
     * This runs after buildSurface() — the terrain has surface blocks but no
     * caves and no structure features. Guaranteed clean under C2ME.
     */
    @Inject(
        method = "buildSurface",
        at = @At("RETURN")
    )
    private void onBuildSurfaceComplete(ChunkRegion chunkRegion,
                                         StructureAccessor structureAccessor,
                                         NoiseConfig noiseConfig,
                                         Chunk chunk,
                                         CallbackInfo ci) {
        if (LayerConfig.STRUCTURE_SKIP_ELEVATED) {
            PreStructureHeightmapStorage.captureSurfaceHeightmap(chunk);
        }
    }

    @Inject(
        method = "carve",
        at = @At("RETURN")
    )
    private void onCarveComplete(ChunkRegion chunkRegion,
                                  long seed,
                                  NoiseConfig noiseConfig,
                                  BiomeAccess biomeAccess,
                                  StructureAccessor structureAccessor,
                                  Chunk chunk,
                                  GenerationStep.Carver carverStep,
                                  CallbackInfo ci) {
        if (!LayerConfig.RTF_LAYER_INJECTION) {
            return;
        }

        // Capture carvers-phase snapshot for STRUCTURE_NO_LAYERS.
        // Note: STRUCTURE_SKIP_ELEVATED uses a separate surface-phase snapshot
        // captured in onBuildSurfaceComplete() above.
        if (LayerConfig.STRUCTURE_NO_LAYERS) {
            PreStructureHeightmapStorage.captureHeightmap(chunk);
        }

        // Skip if using POST_FEATURES mode - injection happens later at WorldChunk creation
        if (LayerConfig.INJECTION_MODE == LayerConfig.InjectionMode.POST_FEATURES) {
            return;
        }

        try {
            RTFLayerInjector.prepareStructureBounds(chunk, chunkRegion);

            if (RTFCompat.isRTFAvailable()) {
                if (noiseConfig != null) {
                    AronaLayersGen.LOGGER.debug("[ChunkGen] Injecting layers after carve for chunk {},{}",
                        chunk.getPos().x, chunk.getPos().z);
                    RTFCompat.injectLayersWithRTF(chunk, noiseConfig);
                } else if (RandomStateHolder.hasRTFRandomState()) {
                    Object randomState = RandomStateHolder.getRandomState();
                    RTFCompat.injectLayersWithRTF(chunk, randomState);
                }
            }
        } catch (Exception e) {
            AronaLayersGen.LOGGER.debug("[ChunkGen] Layer injection failed: {}", e.getMessage());
        } finally {
            RTFLayerInjector.clearStructureBounds();
        }
    }
}
