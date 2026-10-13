package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts vanilla TreeFeature.generate() and places a CR NBT tree before vanilla gets a chance
 * to. This is the interception path for vanilla worldgen and sapling growth; Tellus's custom-tree
 * generator, which bypasses TreeFeature, is intercepted separately by TellusProceduralTreeMixin.
 *
 * <p>26.3: Feature became an interface and TreeFeature a record carrying its own config, so the
 * entry point is place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos) — there is no
 * FeaturePlaceContext/TreeConfiguration any more. Same behaviour as the shared version.
 */
@Mixin(TreeFeature.class)
public class NbtTreeFeatureMixin {

    @Inject(
        method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onGenerate(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                             CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.CR_NBT_TREES) return;
        if (LayerConfig.logNbtTrees())
            AronaLayersGen.LOGGER.info("[NbtTrees] vanilla-mixin fired pos={}", origin);

        if (level instanceof ServerLevel) {
            // Sapling growing — place immediately, world is fully loaded
            boolean placed = NbtTreeInjector.tryPlaceTree(
                level, origin, random);
            if (placed) {
                cir.setReturnValue(true);
            } else if (!LayerConfig.CR_NBT_TREES_VANILLA_FALLBACK) {
                // Fallback disabled: suppress vanilla; generate() returns false so the sapling is restored.
                cir.setReturnValue(false);
            }
            // else: fallback enabled, don't touch cir → vanilla tree runs normally
        } else {
            // Worldgen — cancel vanilla and defer, but only if this biome is configured
            // (or vanilla_fallback=false, in which case we always cancel).
            if (NbtTreeInjector.willHandleWorldgenTree(level, origin)) {
                NbtTreeInjector.queueWorldgenTree(origin);
                cir.setReturnValue(true);
            }
            // else: biome not configured + fallback enabled → vanilla tree runs normally
        }
    }
}
