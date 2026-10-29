package io.arona74.aronalayersgen.client;

import io.arona74.aronalayersgen.grounding.PlantGrounding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The offset as GroundedPlantModel draws it, shared by every version's model wrapper.
 *
 * <p>The mesher's view ends two blocks outside the section being built, so column plants walk down
 * to their base through the client level instead (see PlantGrounding.offset). That read happens on
 * a mesh worker thread, off the client thread that owns the level; block reads there are what
 * Sodium copies sections to avoid, and they can race a chunk update. A failed read falls back to
 * the mesher's view, which at worst draws the upper part of a tall column unlowered for a frame.
 * Changing the layer under a column taller than its section does not re-mesh the sections above,
 * so they pick the new height up at their next rebuild.
 */
public final class GroundedPlantRender {
    private GroundedPlantRender() {}

    public static float offset(BlockState state, BlockGetter view, BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return PlantGrounding.offset(state, view, pos);
        try {
            return PlantGrounding.offset(state, view, level, pos);
        } catch (RuntimeException e) {
            return PlantGrounding.offset(state, view, pos);
        }
    }

    /** A lowered post's drop, walking a stacked column the same way. */
    public static float postDrop(BlockState state, BlockGetter view, BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return PlantGrounding.postDrop(state, view, pos);
        try {
            return PlantGrounding.postDrop(state, view, level, pos);
        } catch (RuntimeException e) {
            return PlantGrounding.postDrop(state, view, pos);
        }
    }
}
