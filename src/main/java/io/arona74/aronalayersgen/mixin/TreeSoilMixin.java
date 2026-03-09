package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.AronaLayersGen;
import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.RTFLayerInjector;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.TreeFeature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import net.minecraft.world.gen.feature.util.FeatureContext;
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
 */
@Mixin(TreeFeature.class)
public class TreeSoilMixin {

    @Inject(
        method = "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
        at = @At("HEAD")
    )
    private void onGenerateHead(FeatureContext<TreeFeatureConfig> context,
                                 CallbackInfoReturnable<Boolean> cir) {
        if (!LayerConfig.TREE_INJECTION) {
            return;
        }

        StructureWorldAccess world = context.getWorld();
        BlockPos origin = context.getOrigin();

        if (LayerConfig.DEBUG_LOGGING) {
            BlockState atOrigin = world.getBlockState(origin);
            BlockState below = world.getBlockState(origin.down());
            BlockState below2 = world.getBlockState(origin.down().down());
            AronaLayersGen.LOGGER.info("[TreeSoil] Tree attempt at {}: origin={}, below={}, below2={}",
                origin,
                net.minecraft.registry.Registries.BLOCK.getId(atOrigin.getBlock()),
                net.minecraft.registry.Registries.BLOCK.getId(below.getBlock()),
                net.minecraft.registry.Registries.BLOCK.getId(below2.getBlock()));
        }

        int layersCleared = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos checkOrigin = origin.add(dx, 0, dz);

                BlockPos soilPos = checkOrigin.down();
                BlockState soilState = world.getBlockState(soilPos);

                if (RTFLayerInjector.hasLayerProperty(soilState)) {
                    BlockPos actualSoilPos = soilPos.down();
                    BlockState actualSoilState = world.getBlockState(actualSoilPos);

                    if (actualSoilState.isIn(BlockTags.DIRT)) {
                        world.setBlockState(soilPos, actualSoilState, 0);
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

        if (layersCleared > 0 && LayerConfig.DEBUG_LOGGING) {
            AronaLayersGen.LOGGER.info("[TreeSoil] Cleared {} layers at origin {}", layersCleared, origin);
        }
    }
}
