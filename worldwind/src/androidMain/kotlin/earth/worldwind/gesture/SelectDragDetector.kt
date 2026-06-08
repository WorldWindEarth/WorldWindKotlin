package earth.worldwind.gesture

import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.ViewConfiguration
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.shape.Movable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

open class SelectDragDetector(protected val wwd: WorldWindow) : SimpleOnGestureListener() {

    /**
     * Main callback to process renederable selection and drag events.
     */
    var callback: SelectDragCallback? = null
    /**
     * Enable/disable renderables selection and drag processing.
     */
    var isEnabled = true
    /**
     * Issue pick callback after the gesture detector is confident that the user's first tap is not followed
     * by a second tap leading to a double-tap gesture.
     */
    var isSingleTapConfirmed = false
    protected val mainScope get() = wwd.engine.renderResourceCache.mainScope
    protected open val gestureDetector = GestureDetector(wwd.context, this)
    protected val slop = ViewConfiguration.get(wwd.context).scaledTouchSlop
    protected lateinit var pickRequest: Deferred<PickedObjectList> // last picked objects from onDown event
    protected var isDragging = false
    protected var isDraggingArmed = false
    protected var draggingJob: Job? = null
    // Two drag approaches sharing this detector:
    //   A) Snap-to-cursor for ground-clamped point shapes (Placemark/Label/sightline with
    //      CLAMP_TO_GROUND). [grabRotation] is null; the drag handler resolves the cursor onto
    //      the terrain and sets that as the new reference each event.
    //   B) Anchor tracking for everything else (absolute/relative point shapes, surface and 3D
    //      extended shapes, meshes). [grabRotation] captures a rigid rotation taking the
    //      depth-picked shape-surface point to the renderable's reference. Each drag event
    //      raycasts the cursor onto the surface the grab anchor lives on (terrain for
    //      ground-relative altitude modes, ellipsoid offset by [grabAltitude] for ABSOLUTE),
    //      then applies the rotation to get the new reference geographic position.
    private var grabRotation: SphericalRotation? = null
    private var grabAltitude = 0.0
    // Picked Movable cached at drag-arm time. Lets onScroll run synchronously on the touch
    // thread on every move after the first, skipping the coroutine dispatch hop and the
    // pickRequest.await round-trip. Null while pick is in flight or if the pick missed.
    private var draggedMovable: Movable? = null

    fun onTouchEvent(event: MotionEvent): Boolean {
        // Skip select and drag processing if the processor is disabled or callback is not assigned
        val handled = if (isEnabled && callback != null) gestureDetector.onTouchEvent(event) else false
        // Is a dragging operation started or in progress? Any ACTION_UP event cancels a drag operation.
        if (isDragging && event.action == MotionEvent.ACTION_UP) cancelDragging()
        // Allow select and drag detector to intercept event. It sets the state flags which will
        // either preempt or allow the event to be subsequently processed by other event handlers.
        return handled || isDragging
    }

    override fun onDown(event: MotionEvent): Boolean {
        pick(event)
        return false
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        if (!isSingleTapConfirmed) onSingleTap()
        return false
    }

    override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
        if (isSingleTapConfirmed) onSingleTap()
        return false
    }

    private fun onSingleTap() {
        val callback = callback ?: return
        mainScope.launch {
            val (userObject, position) = awaitPickResult(false)
            if (position != null) {
                if (userObject != null && callback.canPickObjects(userObject)) {
                    callback.onObjectPicked(userObject, position)
                } else callback.onTerrainPicked(position)
            } else callback.onNothingPicked()
            wwd.requestRedraw()
        }
    }

    override fun onScroll(downEvent: MotionEvent?, moveEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val callback = callback ?: return false
        // Capture cursor coords up-front: MotionEvent is recycled by the framework once this
        // listener returns, so reading moveEvent.x/y from a deferred slow-path coroutine below
        // would race with the recycle and pick up garbage screen coords.
        val cursorX = moveEvent.x.toDouble()
        val cursorY = moveEvent.y.toDouble()
        draggingJob?.cancel()

        // Fast path — pick already resolved into a Movable: run the move synchronously on the
        // touch thread. Reading referencePosition fresh each call picks up the prior moveTo,
        // so this stays equivalent to awaitPickResult(true)'s referencePosition-first result.
        val movable = draggedMovable
        if (isDraggingArmed && movable != null) {
            doDragMove(movable, movable.referencePosition, cursorX, cursorY, callback)
            return true
        }

        // Slow path — pick still in flight (first move after touch-down) or pick didn't yield a
        // Movable. Defer to a coroutine and await the result.
        draggingJob = mainScope.launch {
            val (userObject, fromPosition) = awaitPickResult(true)
            if (isDraggingArmed && fromPosition != null && userObject != null) {
                doDragMove(userObject, fromPosition, cursorX, cursorY, callback)
            }
        }
        return isDraggingArmed // We consumed this event, even if dragging has been stopped.
    }

    private fun doDragMove(
        userObject: Any, fromPosition: Position, cursorX: Double, cursorY: Double, callback: SelectDragCallback
    ) {
        isDragging = true

        val toPosition = Position()
        val rotation = grabRotation
        val movable = userObject as? Movable
        val moved = if (rotation == null) {
            // Approach A — ground-clamped point shape: snap to cursor on terrain.
            wwd.engine.pickTerrainPosition(cursorX, cursorY, toPosition)
        } else {
            // Approach B — anchor tracking: raycast the cursor onto the surface the grab
            // anchor lives on, then rotate that point into the reference's frame.
            val mode = movable?.altitudeMode
            val elevated = mode != null && mode != AltitudeMode.CLAMP_TO_GROUND ||
                grabAltitude > wwd.engine.globe.getElevation(
                    fromPosition.latitude, fromPosition.longitude
                ) + ELEVATED_THRESHOLD
            val hit = if (elevated) {
                wwd.engine.screenPointToPositionAtAltitude(cursorX, cursorY, grabAltitude, toPosition)
            } else {
                wwd.engine.pickTerrainPosition(cursorX, cursorY, toPosition)
            }
            if (hit) { rotation.apply(toPosition); true } else false
        }
        if (moved) {
            toPosition.altitude = fromPosition.altitude
            callback.onObjectMoved(userObject, fromPosition, toPosition)
            movable?.moveTo(wwd.engine.globe, toPosition)
            wwd.requestRedraw()
        } else {
            // Probably clipped by near/far clipping plane or off the globe. The position was not updated. Stop the drag.
            isDraggingArmed = false
        }
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        val callback = callback ?: return false
        return runBlocking {
            val (userObject, position) = awaitPickResult(false)
            if (position != null) {
                if (userObject != null) callback.onObjectDoubleTap(userObject, position)
                else callback.onTerrainDoubleTap(position)
                wwd.requestRedraw()
                true
            } else false
        }
    }

    override fun onLongPress(event: MotionEvent) {
        showContext()
        pick(event) // Select possible drag point
    }

    override fun onContextClick(event: MotionEvent): Boolean {
        showContext()
        pick(event) // Select possible drag point
        return true
    }

    fun cancelDragging() {
        isDragging = false
        isDraggingArmed = false
        draggedMovable = null
        draggingJob?.cancel()
        draggingJob = null
        val callback = callback ?: return
        mainScope.launch {
            val (userObject, position) = awaitPickResult(true)
            if (userObject != null && position != null) {
                callback.onObjectMovingFinished(userObject, position)
                wwd.requestRedraw()
            }
        }
    }

    private fun showContext() {
        val callback = callback ?: return
        mainScope.launch {
            val (userObject, position) = awaitPickResult(false)
            if (position != null) {
                if (userObject != null) callback.onObjectContext(userObject, position)
                else callback.onTerrainContext(position)
            } else callback.onNothingContext()
            wwd.requestRedraw()
        }
    }

    /**
     * Performs a pick at the tap location and conditionally arms the dragging flag, so that dragging can occur if
     * the next event is an onScroll event.
     */
    private fun pick(event: MotionEvent) {
        // Clear stale cache eagerly so a fresh ACTION_DOWN can't reuse the prior drag's
        // Movable while this pick is still in flight.
        draggedMovable = null
        pickRequest = wwd.pickAsync(event.x - slop / 2f, event.y - slop / 2f, slop.toFloat(), slop.toFloat())
        mainScope.launch {
            val pickList = pickRequest.await()
            val topPicked = pickList.topPickedObject
            val userObject = topPicked?.userObject
            val movable = userObject as? Movable
            val terrainPos = pickList.terrainPickedObject?.terrainPosition
            isDraggingArmed = userObject != null && callback?.canMoveObjects(userObject) == true
            // Publish the picked Movable for onScroll's fast path. Only Movables qualify because
            // the fast path reads fromPosition from referencePosition; non-Movable draggables
            // still take the slow path and fall back to terrainPosition via awaitPickResult.
            draggedMovable = if (isDraggingArmed) movable else null
            // Approach A applies only to ground-clamped point shapes (Placemark/Label/sightline
            // pinned to terrain); everything else takes Approach B with anchor tracking.
            val isGroundClampedPoint = movable != null
                    && movable.isPointShape
                    && movable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
            // Point shapes (Placemark/Label/sightline) render with a billboard depth offset, so
            // the depth-readback unprojection is shifted toward the camera and would yield a
            // large non-identity rotation that flings the drag. Use the reference position
            // directly for point shapes so the rotation collapses to identity. Extended shapes
            // (Polygon/Path/Mesh/Ellipsoid) render their true geometry — the depth point is
            // reliable there and we want it so the grabbed surface point tracks the finger.
            val grabAnchor = if (movable != null && movable.isPointShape) movable.referencePosition
            else topPicked?.geographicPoint(wwd.engine.globe) ?: terrainPos
            if (movable != null && !isGroundClampedPoint && grabAnchor != null) {
                grabRotation = SphericalRotation(grabAnchor, movable.referencePosition)
                grabAltitude = if (movable.isPointShape)
                    wwd.engine.globe.getAbsolutePosition(movable.referencePosition, movable.altitudeMode).altitude
                else grabAnchor.altitude
            } else {
                grabRotation = null
                grabAltitude = 0.0
            }
        }
    }

    private suspend fun awaitPickResult(movement: Boolean): Pair<Any?, Position?> {
        val pickList = pickRequest.await()
        val topPicked = pickList.topPickedObject
        val userObject = topPicked?.userObject
        val referencePosition = (userObject as? Movable)?.referencePosition
        val terrainPosition = pickList.terrainPickedObject?.terrainPosition
        // Movement keeps reference-first priority; pick/context/double-tap prefers the depth-tested
        // shape-surface point over the terrain behind the shape.
        val position = if (movement) {
            referencePosition ?: terrainPosition
        } else {
            topPicked?.geographicPoint(wwd.engine.globe) ?: terrainPosition ?: referencePosition
        }
        return userObject to position
    }

    companion object {
        /**
         * Meters above the terrain at the reference lat/lon above which the grabbed surface is
         * treated as elevated. Below it the drag adapts to terrain via [earth.worldwind.WorldWind.pickTerrainPosition];
         * above it the cursor is unprojected onto the offset ellipsoid at [grabAltitude] to keep
         * cursor-to-surface tracking consistent under perspective.
         */
        private const val ELEVATED_THRESHOLD = 1.0
    }
}