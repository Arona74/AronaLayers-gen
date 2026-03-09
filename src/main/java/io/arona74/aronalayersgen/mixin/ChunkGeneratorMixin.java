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
 */
@Mixin(NoiseChunkGenerator.class)
public class ChunkGeneratorMixin {

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

        // Capture heightmap snapshot before structures (for structure_no_layers)
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
