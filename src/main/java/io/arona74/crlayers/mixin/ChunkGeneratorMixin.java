package io.arona74.crlayers.mixin;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import io.arona74.crlayers.injection.PreStructureHeightmapStorage;
import io.arona74.crlayers.injection.RTFCompat;
import io.arona74.crlayers.injection.RTFLayerInjector;
import io.arona74.crlayers.injection.RandomStateHolder;
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
 * This ensures layers are placed before plants/trees are generated.
 */
@Mixin(NoiseChunkGenerator.class)
public class ChunkGeneratorMixin {

    /**
     * Inject after carvers are applied but before features.
     * The carve method is called during the CARVERS chunk status.
     *
     * NoiseConfig in Yarn = RandomState in Mojang mappings
     */
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
        // Only process if RTF injection is enabled
        if (!LayerConfig.RTF_LAYER_INJECTION) {
            return;
        }

        // Capture heightmap snapshot before structures are placed (for structure_no_layers).
        // Must happen before the POST_FEATURES early return since both modes need this data.
        if (LayerConfig.STRUCTURE_NO_LAYERS) {
            PreStructureHeightmapStorage.captureHeightmap(chunk);
        }

        // Skip if using POST_FEATURES mode - injection happens later at WorldChunk creation
        if (LayerConfig.INJECTION_MODE == LayerConfig.InjectionMode.POST_FEATURES) {
            return;
        }

        try {
            // Pre-compute structure bounding boxes for this chunk (including cross-chunk refs)
            RTFLayerInjector.prepareStructureBounds(chunk, chunkRegion);

            // NoiseConfig is RandomState - check if it's RTFRandomState
            if (RTFCompat.isRTFAvailable()) {
                // Try using the noiseConfig directly (it's the RandomState)
                if (noiseConfig != null) {
                    CRLayers.LOGGER.debug("[ChunkGen] Injecting layers after carve for chunk {},{}",
                        chunk.getPos().x, chunk.getPos().z);
                    RTFCompat.injectLayersWithRTF(chunk, noiseConfig);
                } else if (RandomStateHolder.hasRTFRandomState()) {
                    // Fallback to cached RandomState
                    Object randomState = RandomStateHolder.getRandomState();
                    RTFCompat.injectLayersWithRTF(chunk, randomState);
                }
            }
        } catch (Exception e) {
            CRLayers.LOGGER.debug("[ChunkGen] Layer injection failed: {}", e.getMessage());
        } finally {
            RTFLayerInjector.clearStructureBounds();
        }
    }
}
