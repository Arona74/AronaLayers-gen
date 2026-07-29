package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Body of ChunkGeneratorMixin, kept out of the mixin itself.
 *
 * <p>1.21.11 dropped the trailing GenerationStep.Carving parameter from
 * applyCarvers, and a mixin handler's signature has to match its target exactly,
 * so that mixin is per-version. Only the signature differs though — the logic
 * below stays shared.
 */
public final class ChunkGenerationHooks {
    private ChunkGenerationHooks() {}

    /**
     * Surface-phase snapshot for STRUCTURE_SKIP_ELEVATED. Runs after buildSurface():
     * terrain has surface blocks but no caves and no structure features, which is
     * guaranteed clean even under C2ME-parallel worldgen.
     */
    public static void afterBuildSurface(ChunkAccess chunk) {
        if (LayerConfig.STRUCTURE_SKIP_ELEVATED) {
            PreStructureHeightmapStorage.captureSurfaceHeightmap(chunk);
        }
    }

    /** Carvers-phase snapshot plus the RTF layer injection. */
    public static void afterCarve(WorldGenRegion chunkRegion, RandomState noiseConfig, ChunkAccess chunk) {
        if (!LayerConfig.RTF_LAYER_INJECTION) {
            return;
        }

        // Carvers-phase snapshot for STRUCTURE_NO_LAYERS.
        // STRUCTURE_SKIP_ELEVATED uses the separate surface-phase snapshot above.
        if (LayerConfig.STRUCTURE_NO_LAYERS) {
            PreStructureHeightmapStorage.captureHeightmap(chunk);
        }

        // POST_FEATURES mode injects later, at WorldChunk creation.
        if (LayerConfig.INJECTION_MODE == LayerConfig.InjectionMode.POST_FEATURES) {
            return;
        }

        try {
            RTFLayerInjector.prepareStructureBounds(chunk, chunkRegion);

            if (RTFCompat.isRTFAvailable()) {
                if (noiseConfig != null) {
                    AronaLayersGen.LOGGER.debug("[ChunkGen] Injecting layers after carve for chunk {},{}",
                        Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()));
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
