package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.Compat;
import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes tree generation "see through" layer blocks when validating soil.
 *
 * In CARVERS mode, layers are placed BEFORE tree features generate.
 * This mixin intercepts tree generation and removes any layer block at the
 * trunk base position, allowing the tree to see the actual soil underneath.
 * Works with both CR and VP layer blocks.
 *
 * <p>26.3: Feature became an interface and TreeFeature a record carrying its own config, so the
 * entry point is place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos) — there is no
 * FeaturePlaceContext/TreeConfiguration any more. Same behaviour as the shared version.
 */
@Mixin(TreeFeature.class)
public class TreeSoilMixin {

    @Inject(
        method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
        at = @At("HEAD")
    )
    private void onGenerateHead(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                 CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.TREE_INJECTION) {
            return;
        }

        WorldGenLevel world = level;

        if (LayerConfig.logTreeSoil()) {
            BlockState atOrigin = world.getBlockState(origin);
            BlockState below = world.getBlockState(origin.below());
            BlockState below2 = world.getBlockState(origin.below().below());
            AronaLayersGen.LOGGER.info("[TreeSoil] Tree attempt at {}: origin={}, below={}, below2={}",
                origin,
                Compat.blockId(atOrigin.getBlock()),
                Compat.blockId(below.getBlock()),
                Compat.blockId(below2.getBlock()));
        }

        int layersCleared = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos checkOrigin = origin.offset(dx, 0, dz);

                BlockPos soilPos = checkOrigin.below();
                BlockState soilState = world.getBlockState(soilPos);

                if (RTFLayerInjector.hasLayerProperty(soilState)) {
                    BlockPos actualSoilPos = soilPos.below();
                    BlockState actualSoilState = world.getBlockState(actualSoilPos);

                    if (actualSoilState.is(BlockTags.DIRT)) {
                        world.setBlock(soilPos, actualSoilState, 0);
                        layersCleared++;
                    }
                }

                BlockState originState = world.getBlockState(checkOrigin);
                if (RTFLayerInjector.hasLayerProperty(originState)) {
                    world.removeBlock(checkOrigin, false);
                    layersCleared++;
                }
            }
        }

        if (layersCleared > 0 && LayerConfig.logTreeSoil()) {
            AronaLayersGen.LOGGER.info("[TreeSoil] Cleared {} layers at origin {}", layersCleared, origin);
        }
    }
}
