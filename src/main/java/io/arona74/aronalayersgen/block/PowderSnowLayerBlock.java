package io.arona74.aronalayersgen.block;

import io.arona74.aronalayersgen.mixin.EntityTouchingPowderSnowAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SnowBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * A layered snow block (1–8 layers, like minecraft:snow) that also applies
 * powder snow mechanics:
 * - Can be placed on top of minecraft:powder_snow.
 * - Entities without leather boots have no collision (they sink through).
 * - All entities touching the block get the vanilla inPowderSnow flag set,
 *   triggering freeze-tick accumulation and eventual freeze damage.
 * - When the layer count reaches 8, the block converts to minecraft:powder_snow.
 */
public class PowderSnowLayerBlock extends SnowBlock {

    public PowderSnowLayerBlock(Settings settings) {
        super(settings);
    }

    /** Also allows placement on top of minecraft:powder_snow. */
    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.down());
        if (below.isOf(Blocks.POWDER_SNOW)) {
            return true;
        }
        return super.canPlaceAt(state, world, pos);
    }

    /**
     * When layers reach 8, immediately convert to a full minecraft:powder_snow block.
     */
    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (!world.isClient() && state.get(Properties.LAYERS) == 8) {
            world.setBlockState(pos, Blocks.POWDER_SNOW.getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    /**
     * Entities without leather boots receive an empty collision shape so they
     * sink through the block, mirroring vanilla powder snow behaviour.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (context instanceof EntityShapeContext entityContext) {
            Entity entity = entityContext.getEntity();
            if (entity != null) {
                boolean wearingLeatherBoots = entity instanceof LivingEntity living &&
                        living.getEquippedStack(EquipmentSlot.FEET).isOf(Items.LEATHER_BOOTS);
                if (wearingLeatherBoots) {
                    return super.getCollisionShape(state, world, pos, context);
                }
                return VoxelShapes.empty();
            }
        }
        return super.getCollisionShape(state, world, pos, context);
    }

    /**
     * While inside the block:
     * - Applies movement slowdown (unless wearing leather boots).
     * - Sets inPowderSnow so vanilla Entity.baseTick() accumulates frozenTicks.
     */
    @Override
    public void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
        boolean wearingLeatherBoots = entity instanceof LivingEntity living &&
                living.getEquippedStack(EquipmentSlot.FEET).isOf(Items.LEATHER_BOOTS);

        if (!wearingLeatherBoots) {
            entity.slowMovement(state, new Vec3d(0.9, 1.5, 0.9));
        }

        ((EntityTouchingPowderSnowAccessor) entity).aronalayersgen$setInPowderSnow(true);
    }
}
