package io.arona74.aronalayersgen.block;

import io.arona74.aronalayersgen.mixin.EntityTouchingPowderSnowAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

/**
 * A layered snow block (1–8 layers, like minecraft:snow) that also applies
 * powder snow mechanics:
 * - Can be placed on top of minecraft:powder_snow.
 * - Entities without leather boots have no collision (they sink through).
 * - All entities touching the block get the vanilla inPowderSnow flag set,
 *   triggering freeze-tick accumulation and eventual freeze damage.
 * - When the layer count reaches 8, the block converts to minecraft:powder_snow.
 */
public abstract class PowderSnowLayerBlockBase extends SnowLayerBlock {

    protected PowderSnowLayerBlockBase(Properties settings) {
        super(settings);
    }

    /** Also allows placement on top of minecraft:powder_snow. */
    @Override
    public boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.below());
        if (below.is(Blocks.POWDER_SNOW)) {
            return true;
        }
        return super.canSurvive(state, world, pos);
    }

    /**
     * When layers reach 8, immediately convert to a full minecraft:powder_snow block.
     */
    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, world, pos, oldState, notify);
        if (!world.isClientSide() && state.getValue(BlockStateProperties.LAYERS) == 8) {
            world.setBlock(pos, Blocks.POWDER_SNOW.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * Entities without leather boots receive an empty collision shape so they
     * sink through the block, mirroring vanilla powder snow behaviour.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext) {
            Entity entity = entityContext.getEntity();
            if (entity != null) {
                boolean wearingLeatherBoots = entity instanceof LivingEntity living &&
                        living.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS);
                if (wearingLeatherBoots) {
                    return super.getCollisionShape(state, world, pos, context);
                }
                return Shapes.empty();
            }
        }
        return super.getCollisionShape(state, world, pos, context);
    }

    /**
     * Effects applied while an entity is inside the block:
     * - movement slowdown (unless wearing leather boots);
     * - sets inPowderSnow so vanilla Entity.baseTick() accumulates frozenTicks.
     *
     * <p>Lives here rather than in entityInside because 1.21.11 changed that
     * override's signature; each version's subclass adapts and delegates here.
     */
    protected void applyInsideEffects(BlockState state, Entity entity) {
        boolean wearingLeatherBoots = entity instanceof LivingEntity living &&
                living.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS);

        if (!wearingLeatherBoots) {
            entity.makeStuckInBlock(state, new Vec3(0.9, 1.5, 0.9));
        }

        ((EntityTouchingPowderSnowAccessor) entity).aronalayersgen$setInPowderSnow(true);
    }
}
