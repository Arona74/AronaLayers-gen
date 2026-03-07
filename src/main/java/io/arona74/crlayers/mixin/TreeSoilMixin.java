package io.arona74.crlayers.mixin;

import io.arona74.crlayers.CRLayers;
import io.arona74.crlayers.LayerConfig;
import io.arona74.crlayers.injection.RTFLayerInjector;
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
 * When a tree checks for valid soil (dirt/grass), it finds a layer block instead.
 * This mixin intercepts the tree generation and removes any layer block at the
 * trunk base position, allowing the tree to see the actual soil underneath.
 *
 * Combined with TreeFeatureMixin (which allows tree parts to replace layers),
 * this ensures trees generate correctly on layered terrain.
 */
@Mixin(TreeFeature.class)
public class TreeSoilMixin {

    /**
     * Before tree generation, check if there's a layer block at the trunk base.
     * If so, temporarily remove it so the soil check succeeds.
     * The tree will then generate and its trunk will occupy this position.
     *
     * Note: This modifies the world directly. The layer is removed permanently,
     * but since the tree trunk will occupy this position anyway, this is the
     * intended behavior.
     */
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

        // Debug: Log what we see at the tree position
        if (LayerConfig.DEBUG_LOGGING) {
            BlockState atOrigin = world.getBlockState(origin);
            BlockState below = world.getBlockState(origin.down());
            BlockState below2 = world.getBlockState(origin.down().down());
            CRLayers.LOGGER.info("[TreeSoil] Tree attempt at {}: origin={}, below={}, below2={}",
                origin,
                net.minecraft.registry.Registries.BLOCK.getId(atOrigin.getBlock()),
                net.minecraft.registry.Registries.BLOCK.getId(below.getBlock()),
                net.minecraft.registry.Registries.BLOCK.getId(below2.getBlock()));
        }

        // Clear layers in a 3x3 area around the tree origin to handle:
        // - Single trunk trees (1x1)
        // - Large trees like dark oak (2x2 trunk)
        // - Any offset between origin and actual trunk placement
        int layersCleared = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos checkOrigin = origin.add(dx, 0, dz);

                // Check soil position (one block below trunk)
                BlockPos soilPos = checkOrigin.down();
                BlockState soilState = world.getBlockState(soilPos);

                if (RTFLayerInjector.hasLayerProperty(soilState)) {
                    BlockPos actualSoilPos = soilPos.down();
                    BlockState actualSoilState = world.getBlockState(actualSoilPos);

                    if (actualSoilState.isIn(BlockTags.DIRT)) {
                        // Replace layer with actual soil so tree validation passes
                        world.setBlockState(soilPos, actualSoilState, 0);
                        layersCleared++;
                    }
                }

                // Also check trunk position itself
                BlockState originState = world.getBlockState(checkOrigin);
                if (RTFLayerInjector.hasLayerProperty(originState)) {
                    world.removeBlock(checkOrigin, false);
                    layersCleared++;
                }
            }
        }

        if (layersCleared > 0 && LayerConfig.DEBUG_LOGGING) {
            CRLayers.LOGGER.info("[TreeSoil] Cleared {} layers at origin {}", layersCleared, origin);
        }
    }
}
