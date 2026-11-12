package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.injection.ChunkGenerationHooks;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects layer generation after carving but before features, so layers are in
 * place before plants and trees generate (CARVERS mode), and captures the
 * heightmap snapshots used by structure-aware placement.
 *
 * <p>Per-version because applyCarvers lost its trailing GenerationStep.Carving
 * parameter in 1.21.11. The logic lives in ChunkGenerationHooks.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class ChunkGeneratorMixin {

    @Inject(
        method = "buildSurface",
        at = @At("RETURN")
    )
    private void onBuildSurfaceComplete(WorldGenRegion chunkRegion,
                                         StructureManager structureAccessor,
                                         RandomState noiseConfig,
                                         ChunkAccess chunk,
                                         CallbackInfo ci) {
        ChunkGenerationHooks.afterBuildSurface(chunk);
    }

    @Inject(
        method = "applyCarvers",
        at = @At("RETURN")
    )
    private void onCarveComplete(WorldGenRegion chunkRegion,
                                  long seed,
                                  RandomState noiseConfig,
                                  BiomeManager biomeAccess,
                                  StructureManager structureAccessor,
                                  ChunkAccess chunk,
                                  CallbackInfo ci) {
        ChunkGenerationHooks.afterCarve(chunkRegion, noiseConfig, chunk);
    }
}
