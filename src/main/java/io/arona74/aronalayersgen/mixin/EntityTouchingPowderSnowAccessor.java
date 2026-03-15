package io.arona74.aronalayersgen.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes Entity's {@code inPowderSnow} field (Yarn 1.20.1: field_27857) so that
 * PowderSnowLayerBlock can mark entities as touching powder snow, delegating all
 * freeze-tick accumulation and freeze damage to vanilla's Entity.baseTick().
 */
@Mixin(Entity.class)
public interface EntityTouchingPowderSnowAccessor {

    @Accessor("inPowderSnow")
    void aronalayersgen$setInPowderSnow(boolean value);
}
