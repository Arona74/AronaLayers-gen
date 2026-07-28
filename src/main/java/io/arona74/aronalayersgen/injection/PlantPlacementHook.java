package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Body of PlantBlockMixin, kept out of the mixin itself.
 *
 * <p>The mixin is per-version because the plant base class differs: it is
 * BushBlock up to 1.21.1, but 1.21.11 renamed that to VegetationBlock and
 * introduced a *new* narrower BushBlock extending it. Targeting BushBlock there
 * still compiles and applies cleanly while hooking far fewer blocks, so the
 * target has to be chosen per version rather than shared.
 */
public final class PlantPlacementHook {
    private PlantPlacementHook() {}

    /** Mirrors the shadowed mayPlaceOn so the shared logic can call back into it. */
    public interface FloorCheck {
        boolean mayPlaceOn(BlockState floor, BlockGetter world, BlockPos pos);
    }

    /**
     * Makes layer blocks transparent to plant placement checks: when a layer block
     * sits directly below, the check is re-run against the real surface underneath.
     *
     * @return the value canSurvive should return, or null to leave vanilla alone
     */
    public static Boolean lookThroughLayers(LevelReader world, BlockPos pos, FloorCheck check) {
        if (!LayerConfig.PLANT_INJECTION) {
            return null;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = world.getBlockState(belowPos);

        if (RTFLayerInjector.hasLayerProperty(belowState)) {
            BlockPos surfacePos = belowPos.below();
            return check.mayPlaceOn(world.getBlockState(surfacePos), world, surfacePos);
        }
        return null;
    }
}
