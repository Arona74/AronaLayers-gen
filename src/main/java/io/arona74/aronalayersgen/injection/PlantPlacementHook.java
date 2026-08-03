package io.arona74.aronalayersgen.injection;

import io.arona74.aronalayersgen.LayerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    /**
     * The same look-through for blocks that demand a sturdy top face instead of a floor material.
     *
     * <p>{@code leaf_litter} is the case. It extends the plant base but overrides
     * {@code canSurvive} without calling super, so the {@code mayPlaceOn} hook above never runs for
     * it, and what it actually asks is {@code isFaceSturdy(UP)} — which a layer block below
     * answers false for at any count under 8. Worldgen writes the litter without consulting
     * {@code canSurvive}, so it sits there until the first neighbour update pops it, exactly the
     * way snow on packed ice does.
     *
     * <p>Deliberately checks the surface under the layer rather than returning a blanket true: a
     * layer over something that could not hold leaf litter anyway should still fail.
     *
     * @return the value canSurvive should return, or null to leave vanilla alone
     */
    public static Boolean lookThroughLayersForSturdyFace(LevelReader world, BlockPos pos) {
        if (!LayerConfig.PLANT_INJECTION) {
            return null;
        }

        BlockPos belowPos = pos.below();
        if (!RTFLayerInjector.hasLayerProperty(world.getBlockState(belowPos))) {
            return null;
        }

        BlockPos surfacePos = belowPos.below();
        return world.getBlockState(surfacePos).isFaceSturdy(world, surfacePos, Direction.UP);
    }
}
