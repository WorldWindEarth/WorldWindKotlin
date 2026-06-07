package earth.worldwind.gesture

import earth.worldwind.geom.Position

/**
 * User-input callbacks dispatched by `SelectDragDetector` for taps, drags and context gestures
 * on the globe.
 *
 * The `userObject` parameter every recognised-object callback receives is the
 * [earth.worldwind.PickedObject.userObject] of the top picked object. It is typically a
 * [earth.worldwind.render.Renderable] (Polygon/Path/Placemark) — but may be any payload
 * registered via [earth.worldwind.PickedObject.fromUserObject] (for example an
 * [earth.worldwind.layer.buildings.OsmBuilding] surfaced from a batched buildings tile).
 *
 * `position` carries the depth-tested geographic point on the picked surface, so it includes
 * altitude for non-terrain picks. Consumers that propagate the click as a cursor placemark or
 * hit-location should respect that altitude rather than clamping back to ground.
 *
 * Every method has a default no-op implementation; implementers only override the gestures
 * they care about.
 */
interface SelectDragCallback {
    /**
     * Tap landed off the globe (no terrain or shape under the pick point).
     */
    fun onNothingPicked() {}

    /**
     * Context-click landed off the globe.
     */
    fun onNothingContext() {}

    /**
     * Tap on bare terrain — no shape under the pixel.
     *
     * @param position picked terrain position (altitude = terrain elevation at lat/lon).
     */
    fun onTerrainPicked(position: Position) {}

    /**
     * Long-press / right-click on bare terrain.
     *
     * @param position picked terrain position.
     */
    fun onTerrainContext(position: Position) {}

    /**
     * Filter for which picked objects this callback wants. The detector calls
     * [onObjectPicked] / [onObjectContext] / [onObjectDoubleTap] only when this returns true.
     *
     * @param userObject the top picked object's [earth.worldwind.PickedObject.userObject].
     * @return true if this callback handles picks for [userObject].
     */
    fun canPickObjects(userObject: Any) = false

    /**
     * Tap on a recognised object.
     *
     * @param userObject the picked object payload (see interface docs).
     * @param position depth-tested surface point on the picked geometry (carries altitude).
     */
    fun onObjectPicked(userObject: Any, position: Position) {}

    /**
     * Long-press / right-click on a recognised object.
     *
     * @param userObject the picked object payload.
     * @param position depth-tested surface point on the picked geometry.
     */
    fun onObjectContext(userObject: Any, position: Position) {}

    /**
     * Filter for which picked objects this callback wants to drag. The detector arms the drag
     * gesture only when this returns true. The drag plumbing later casts [userObject] to
     * [earth.worldwind.shape.Movable] internally before invoking `moveTo`, so returning true
     * for a non-Movable simply yields a no-op drag (no error).
     *
     * @param userObject the top picked object's [earth.worldwind.PickedObject.userObject].
     * @return true if drag events should be reported for [userObject].
     */
    fun canMoveObjects(userObject: Any) = false

    /**
     * One step of an in-progress drag gesture on a recognised object.
     *
     * @param userObject the object being dragged.
     * @param fromPosition the object's geographic position at the previous drag step.
     * @param toPosition the object's geographic position at this drag step.
     */
    fun onObjectMoved(userObject: Any, fromPosition: Position, toPosition: Position) {}

    /**
     * The drag gesture on a recognised object has finished (finger lifted or pointer released).
     *
     * @param userObject the object that was dragged.
     * @param position the object's geographic position at the end of the drag.
     */
    fun onObjectMovingFinished(userObject: Any, position: Position) {}

    /**
     * Double-tap / double-click on a recognised object.
     *
     * @param userObject the picked object payload.
     * @param position depth-tested surface point on the picked geometry.
     */
    fun onObjectDoubleTap(userObject: Any, position: Position) {}

    /**
     * Double-tap / double-click on bare terrain.
     *
     * @param position picked terrain position.
     */
    fun onTerrainDoubleTap(position: Position) {}
}
