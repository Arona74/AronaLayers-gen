package io.arona74.aronalayersgen.grounding;

/**
 * Implemented on every BlockState by PlantGroundingStateMixin, which caches the plant-grounding
 * classification on the state itself. Shape queries run for every block, constantly; this keeps
 * the "is it a grounded plant at all" answer to a field read.
 */
public interface GroundedState {
    boolean aronalayersgen$isGrounded();
}
