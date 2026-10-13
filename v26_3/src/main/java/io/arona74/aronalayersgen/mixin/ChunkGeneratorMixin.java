package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.injection.ChunkGenerationHooks;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Injects layer generation after carving but before features, so layers are in
 * place before plants and trees generate (CARVERS mode), and captures the
 * heightmap snapshots used by structure-aware placement.
 *
 * <p>26.3 version. 26.3 merged noise fill, surface and carving into one
 * {@code buildTerrain(...)} call: {@code applyCarvers} no longer exists and
 * {@code buildSurface} became a private step with a new parameter list. The hooks
 * are the same — {@code buildSurface} RETURN for the surface snapshot and the new
 * private {@code generateCarvers} RETURN for the post-carve pass, which runs after
 * the surface inside buildTerrain. The logic lives in ChunkGenerationHooks.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class ChunkGeneratorMixin {

    @Inject(
        method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V",
        at = @At("RETURN")
    )
    private void onBuildSurfaceComplete(ChunkAccess chunk,
                                         NoiseChunk noiseChunk,
                                         RandomState randomState,
                                         BiomeManager biomeManager,
                                         Set<Holder<Biome>> biomes,
                                         MaterialRule materialRule,
                                         CallbackInfo ci) {
        ChunkGenerationHooks.afterBuildSurface(chunk);
    }

    @Inject(
        method = "generateCarvers(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V",
        at = @At("RETURN")
    )
    private void onCarveComplete(ChunkAccess chunk,
                                  Blender blender,
                                  NoiseChunk noiseChunk,
                                  RandomState randomState,
                                  BiomeManager biomeManager,
                                  WorldGenRegion region,
                                  MaterialRule materialRule,
                                  CallbackInfo ci) {
        ChunkGenerationHooks.afterCarve(region, randomState, chunk);
    }
}
