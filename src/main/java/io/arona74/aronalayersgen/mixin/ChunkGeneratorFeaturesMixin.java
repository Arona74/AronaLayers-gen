package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.LayerBackend;
import io.arona74.aronalayersgen.injection.LayerPlacementHelper;
import io.arona74.aronalayersgen.injection.PlantConversionHelper;
import io.arona74.aronalayersgen.injection.PreStructureHeightmapStorage;
import io.arona74.aronalayersgen.injection.RTFCompat;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import io.arona74.aronalayersgen.injection.RandomStateHolder;
import io.arona74.aronalayersgen.injection.TellusCompat;
import io.arona74.aronalayersgen.injection.VanillaLayerInjector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
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

    /** Trees started one block higher are recorded per chunk; drop anything a previous chunk left. */
    @Inject(
        method = "applyBiomeDecoration",
        at = @At("HEAD")
    )
    private void onGenerateFeaturesStart(WorldGenLevel world, ChunkAccess chunk, StructureManager structureAccessor, CallbackInfo ci) {
        LayerPlacementHelper.clearShiftedTrunks();
    }

    @Inject(
        method = "applyBiomeDecoration",
        at = @At("RETURN")
    )
    private void onGenerateFeaturesComplete(WorldGenLevel world, ChunkAccess chunk, StructureManager structureAccessor, CallbackInfo ci) {
        if (!LayerConfig.LAYER_INJECTION) {
            return;
        }
        if (LayerConfig.INJECTION_MODE != LayerConfig.InjectionMode.POST_FEATURES) {
            return;
        }

        try {
            WorldGenRegion chunkRegion = null;
            ServerLevel serverWorld = null;
            if (world instanceof WorldGenRegion region) {
                chunkRegion = region;
                @SuppressWarnings("deprecation")
                ServerLevel regionWorld = region.getLevel();
                serverWorld = regionWorld;
            } else if (world instanceof ServerLevel sw) {
                serverWorld = sw;
            }

            if (serverWorld != null && RandomStateHolder.getWorldSeed() == Long.MIN_VALUE) {
                RandomStateHolder.setWorldSeed(serverWorld.getSeed());
            }

            ChunkGenerator self = (ChunkGenerator) (Object) this;
            RandomState randomState = RandomStateHolder.noiseConfigFor(serverWorld);
            LayerBackend backend = LayerBackend.of(serverWorld, self, randomState);
            if (backend == LayerBackend.NONE) return;

            if (LayerConfig.STRUCTURE_INJECTION) {
                // During normal generation world is a WorldGenRegion.
                // region.getChunk() works for ProtoChunks (neighbouring chunks are not yet
                // WorldChunks at FEATURES time), so cross-chunk structure starts and footprints
                // are resolved correctly. Fall back to the ServerLevel path only for reset
                // chunks where world arrives as a plain ServerLevel.
                if (chunkRegion != null) {
                    LayerPlacementHelper.prepareStructureBounds(chunk, chunkRegion);
                } else {
                    LayerPlacementHelper.prepareStructureBoundsWithWorld(chunk, serverWorld);
                }
            }

            // `this` is the ChunkGenerator instance (mixin target). No backend falls back to
            // another: each reads a different source, and on a Tellus or TerraForged world the
            // vanilla density field does not describe the ground that was placed.
            if (chunkRegion != null) {
                LayerPlacementHelper.setLiftRegion(chunkRegion);
            }
            LayerPlacementHelper.clearTrunkGaps(chunk);

            switch (backend) {
                case TELLUS -> TellusCompat.injectLayersWithTellus(chunk, self);
                case TERRAFORGED -> RTFCompat.injectLayersWithRTF(chunk, randomState);
                case VANILLA -> VanillaLayerInjector.injectLayers(chunk, randomState, self);
                case NONE -> { }
            }

            if (LayerConfig.STRUCTURE_SKIP_ELEVATED) {
                PreStructureHeightmapStorage.discardSurfaceSnapshot(chunk);
            }

            if ((backend == LayerBackend.TERRAFORGED || backend == LayerBackend.TELLUS) && LayerConfig.STRUCTURE_NO_LAYERS) {
                Set<Integer> changedColumns = PreStructureHeightmapStorage.getChangedColumns(chunk);
                if (changedColumns != null && !changedColumns.isEmpty()) {
                    RTFLayerInjector.removeLayersAtColumns(chunk, changedColumns);
                }
            }

            LayerPlacementHelper.runSecondPassCleanup(chunk);

            if (LayerConfig.PLANT_INJECTION) {
                PlantConversionHelper.convertPlantsAboveLayers(chunk);
            }

            // Last, after every pass that can remove a layer.
            LayerPlacementHelper.fillTrunkGaps(chunk);

        } catch (Exception e) {
            AronaLayersGen.LOGGER.error("Failed to process layers in generateFeatures for chunk {},{}", Compat.chunkX(chunk.getPos()), Compat.chunkZ(chunk.getPos()), e);
        } finally {
            LayerPlacementHelper.clearStructureBounds();
            LayerPlacementHelper.clearLiftRegion();
            LayerPlacementHelper.clearShiftedTrunks();
        }
    }
}
