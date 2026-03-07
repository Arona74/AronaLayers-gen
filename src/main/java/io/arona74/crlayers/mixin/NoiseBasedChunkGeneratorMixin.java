package io.arona74.crlayers.mixin;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import io.arona74.crlayers.injection.VanillaLayerInjector;
import io.arona74.crlayers.injection.PlantConversionHelper;
import io.arona74.crlayers.injection.PreStructureHeightmapStorage;
import io.arona74.crlayers.injection.RTFCompat;
import io.arona74.crlayers.injection.RTFLayerInjector;
import io.arona74.crlayers.injection.RandomStateHolder;
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

    /**
     * Inject when a WorldChunk is created, which happens after generation completes.
     * In POST_FEATURES mode, this is where primary layer injection happens.
     */
    @Inject(
        method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/chunk/ProtoChunk;Lnet/minecraft/world/chunk/WorldChunk$EntityLoader;)V",
        at = @At("RETURN")
    )
    private void onChunkCreate(ServerWorld world, net.minecraft.world.chunk.ProtoChunk protoChunk, WorldChunk.EntityLoader entityLoader, CallbackInfo ci) {
        if (!LayerConfig.LAYER_INJECTION && !LayerConfig.RTF_LAYER_INJECTION) {
            return;
        }

        try {
            WorldChunk chunk = (WorldChunk)(Object)this;

            if (LayerConfig.RTF_LAYER_INJECTION) {
                boolean rtfAvailable = RTFCompat.isRTFAvailable() && RandomStateHolder.hasRTFRandomState();
                boolean isPostFeaturesMode = LayerConfig.INJECTION_MODE == LayerConfig.InjectionMode.POST_FEATURES;

                if (rtfAvailable) {
                    // RTF is available: use RTF injection
                    if (isPostFeaturesMode) {
                        // POST_FEATURES mode: Do primary injection here, after structures are placed.
                        CRLayers.LOGGER.debug("[POST_FEATURES] Injecting layers for chunk {},{}",
                            chunk.getPos().x, chunk.getPos().z);
                        RTFCompat.injectLayersWithRTF(chunk, RandomStateHolder.getRandomState());
                    } else {
                        // CARVERS mode: Layers were placed earlier (after carvers).
                        // Run correction pass to fix any that became mismatched.
                        RTFLayerInjector.correctMismatchedLayers(chunk);

                        // Fallback: if carve-stage injection didn't run (e.g. first chunk),
                        // try a full RTF injection now. Already-placed layers won't be
                        // overwritten because injectLayerAt skips non-air positions.
                        RTFCompat.injectLayersWithRTF(chunk, RandomStateHolder.getRandomState());
                    }
                } else if (LayerConfig.LAYER_INJECTION) {
                    // RTF requested but not available: fall back to vanilla injection
                    VanillaLayerInjector.injectLayers(chunk, null);
                }
            } else {
                // Vanilla injection (heightmap-based fallback)
                VanillaLayerInjector.injectLayers(chunk, null);
            }

            // structure_no_layers: compare pre-structure heightmap with post-structure
            // heightmap and remove layers where structures changed the terrain.
            if (LayerConfig.RTF_LAYER_INJECTION && LayerConfig.STRUCTURE_NO_LAYERS) {
                Set<Integer> changedColumns = PreStructureHeightmapStorage.getChangedColumns(chunk);
                if (changedColumns != null && !changedColumns.isEmpty()) {
                    RTFLayerInjector.removeLayersAtColumns(chunk, changedColumns);
                }
            }

            // Convert vanilla plants above layers to conquest equivalents
            if (LayerConfig.PLANT_INJECTION) {
                PlantConversionHelper.convertPlantsAboveLayers(chunk);
            }
        } catch (Exception e) {
            CRLayers.LOGGER.error("Failed to process layers for chunk", e);
        }
    }
}
