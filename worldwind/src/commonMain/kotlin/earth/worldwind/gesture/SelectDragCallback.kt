package earth.worldwind.gesture

import earth.worldwind.geom.Position
import earth.worldwind.render.Renderable

/**
 * Interface for processing user input to interact with renderables.
 */
interface SelectDragCallback {
    /**
     * Nothing was picked due to picking point is outside the terrain.
     */
    fun onNothingPicked() {}

    /**
     * Nothing context was requested due to selected point is outside the terrain.
     */
    fun onNothingContext() {}

    /**
     * Terrain position was picked.
     *
     * @param position picked terrain position
     */
    fun onTerrainPicked(position: Position) {}

    /**
     * Terrain context at some position was requested.
     *
     * @param position picked terrain position
     */
    fun onTerrainContext(position: Position) {}

    /**
     * Check if renderable is pick-able.
     *
     * @param renderable some renderable intended to be picked
     * @return renderable is pick-able
     */
    fun canPickRenderable(renderable: Renderable) = false

    /**
     * Some renderable was picked.
     *
     * @param renderable picked renderable
     * @param position picked terrain or renderable center position
     */
    fun onRenderablePicked(renderable: Renderable, position: Position) {}

    /**
     * Some renderables context was requested.
     *
     * @param renderable picked renderable
     * @param position picked terrain or renderable center position
     */
    fun onRenderableContext(renderable: Renderable, position: Position) {}

    /**
     * Check if picked renderable is movable.
     *
     * @param renderable picked renderable
     * @return picked renderable is movable
     */
    fun canMoveRenderable(renderable: Renderable) = false

    /**
     * Renderable was moved from ane position to another.
     *
     * @param renderable picked renderable which is moving
     * @param fromPosition previous position
     * @param toPosition current position
     */
    fun onRenderableMoved(renderable: Renderable, fromPosition: Position, toPosition: Position) {}

    /**
     * Renderable movement was finished
     *
     * @param renderable renderable which was moved
     * @param position last position during movement
     */
    fun onRenderableMovingFinished(renderable: Renderable, position: Position) {}

    /**
     * Renderable was double-tapped or double-clicked
     *
     * @param renderable renderable which was double-tapped or double-clicked
     * @param position picked terrain or renderable center position
     */
    fun onRenderableDoubleTap(renderable: Renderable, position: Position) {}

    /**
     * Terrain position was double-tapped or double-clicked
     *
     * @param position picked terrain position
     */
    fun onTerrainDoubleTap(position: Position) {}
}