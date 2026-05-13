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
import earth.worldwind.render.Renderable
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
            val (renderable, position) = awaitPickResult(false)
            if (position != null) {
                if (renderable is Renderable && callback.canPickRenderable(renderable)) {
                    callback.onRenderablePicked(renderable, position)
                } else callback.onTerrainPicked(position)
            } else callback.onNothingPicked()
            wwd.requestRedraw()
        }
    }

    override fun onScroll(downEvent: MotionEvent?, moveEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val callback = callback ?: return false
        // Capture cursor coords up-front: MotionEvent is recycled by the framework once this
        // listener returns, so reading moveEvent.x/y inside the launch below would race with the
        // recycle and pick up garbage screen coords.
        val cursorX = moveEvent.x.toDouble()
        val cursorY = moveEvent.y.toDouble()
        draggingJob?.cancel()
        draggingJob = mainScope.launch {
            val (renderable, fromPosition) = awaitPickResult(true)
            if (isDraggingArmed && fromPosition != null && renderable is Renderable) {
                // Signal that dragging is in progress
                isDragging = true

                val toPosition = Position()
                val rotation = grabRotation
                val moved = if (rotation == null) {
                    // Approach A — ground-clamped point shape: snap to cursor on terrain.
                    wwd.engine.pickTerrainPosition(cursorX, cursorY, toPosition)
                } else {
                    // Approach B — anchor tracking: raycast the cursor onto the surface the grab
                    // anchor lives on, then rotate that point into the reference's frame.
                    // ABSOLUTE shapes are pinned to a specific altitude regardless of terrain,
                    // so they always use the altitude-aware unproject. Everything else classifies
                    // as elevated when the grabbed surface sits meaningfully above the terrain at
                    // the reference's lat/lon — catches RELATIVE_TO_GROUND with altitude > 0 and
                    // the top face of extruded shapes. Ground-anchored picks fall back to
                    // terrain so the drag adapts to varying terrain.
                    val mode = (renderable as? Movable)?.altitudeMode
                    val elevated = mode == AltitudeMode.ABSOLUTE ||
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
                    callback.onRenderableMoved(renderable, fromPosition, toPosition)
                    if (renderable is Movable) renderable.moveTo(wwd.engine.globe, toPosition)
                    wwd.requestRedraw()
                } else {
                    // Probably clipped by near/far clipping plane or off the globe. The position was not updated. Stop the drag.
                    isDraggingArmed = false
                }
            }
        }
        return isDraggingArmed // We consumed this event, even if dragging has been stopped.
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        val callback = callback ?: return false
        return runBlocking {
            val (renderable, position) = awaitPickResult(false)
            if (position != null) {
                if (renderable is Renderable) callback.onRenderableDoubleTap(renderable, position)
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
        draggingJob?.cancel()
        draggingJob = null
        val callback = callback ?: return
        mainScope.launch {
            val (renderable, position) = awaitPickResult(true)
            if (renderable is Renderable && position != null) {
                callback.onRenderableMovingFinished(renderable, position)
                wwd.requestRedraw()
            }
        }
    }

    private fun showContext() {
        val callback = callback ?: return
        mainScope.launch {
            val (renderable, position) = awaitPickResult(false)
            if (position != null) {
                if (renderable is Renderable) callback.onRenderableContext(renderable, position)
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
        pickRequest = wwd.pickAsync(event.x - slop / 2f, event.y - slop / 2f, slop.toFloat(), slop.toFloat())
        mainScope.launch {
            val pickList = pickRequest.await()
            val topPicked = pickList.topPickedObject
            val userObject = topPicked?.userObject
            val movable = userObject as? Movable
            val terrainPos = pickList.terrainPickedObject?.terrainPosition
            isDraggingArmed = userObject is Renderable && callback?.canMoveRenderable(userObject) == true
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
                grabAltitude = grabAnchor.altitude
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
         * treated as elevated. Below it the drag adapts to terrain via [WorldWindow.pickTerrainPosition];
         * above it the cursor is unprojected onto the offset ellipsoid at [grabAltitude] to keep
         * cursor-to-surface tracking consistent under perspective.
         */
        private const val ELEVATED_THRESHOLD = 1.0
    }
}