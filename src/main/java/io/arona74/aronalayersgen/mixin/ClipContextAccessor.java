package io.arona74.aronalayersgen.mixin;

import net.minecraft.world.level.ClipContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which shape a ray tests against; plant grounding only re-traces outline (picking) rays. */
@Mixin(ClipContext.class)
public interface ClipContextAccessor {
    @Accessor("block")
    ClipContext.Block aronalayersgen$block();
}
