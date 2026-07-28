package io.arona74.aronalayersgen.mixin;

import io.arona74.aronalayersgen.LayerConfig;
import io.arona74.aronalayersgen.injection.NbtTreeInjector;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the sapling block ID before SaplingBlock.generate() removes the block and
 * calls TreeFeature.generate(). Without this, NbtTreeInjector can't distinguish which
 * sapling type triggered the grow event (the position holds AIR by then).
 */
@Mixin(SaplingBlock.class)
public class SaplingBlockGrowMixin {

    @Inject(
        method = "advanceTree(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)V",
        at = @At("HEAD")
    )
    private void captureGrowSapling(ServerLevel world, BlockPos pos, BlockState state, RandomSource random, CallbackInfo ci) {
        if (!LayerConfig.CR_NBT_TREES) return;
        NbtTreeInjector.setSaplingGrowContext(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    @Inject(
        method = "advanceTree(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)V",
        at = @At("RETURN")
    )
    private void clearGrowSapling(ServerLevel world, BlockPos pos, BlockState state, RandomSource random, CallbackInfo ci) {
        NbtTreeInjector.clearSaplingGrowContext();
    }
}
