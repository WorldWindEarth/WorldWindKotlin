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
import earth.worldwind.geom.Vec2
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
    private val dragRefPt = Vec2()
    // Press-time rigid rotation taking the cursor's terrain pick to the renderable's reference.
    // Captured only for extended shapes (Polygon, Path, Mesh) that need the grabbed point pinned
    // to the cursor; null for point shapes (Placemark, Label, sightlines) which snap their anchor
    // directly to the cursor each event.
    private var grabRotation: SphericalRotation? = null

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
                val toGround = renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
                val moved = if (toGround) {
                    // Snap-to-cursor for point shapes (grabRotation == null), grab-anchor for
                    // extended shapes (grabRotation rotates the fresh terrain pick into the
                    // reference's frame, preserving the press-time offset).
                    wwd.engine.pickTerrainPosition(cursorX, cursorY, toPosition).also {
                        if (it) grabRotation?.apply(toPosition)
                    }
                } else {
                    // Screen-delta: project the reference at sea level, shift by the cursor's
                    // incremental delta, resolve back at sea level. Both ends must use altitude 0
                    // to keep projection symmetric and avoid per-event drift.
                    val refMappedToScreen = wwd.engine.geographicToScreenPoint(
                        fromPosition.latitude, fromPosition.longitude, 0.0, dragRefPt
                    )
                    refMappedToScreen && wwd.engine.screenPointToGroundPosition(
                        dragRefPt.x - distanceX, dragRefPt.y - distanceY, toPosition
                    )
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
            val userObject = pickList.topPickedObject?.userObject
            val movable = userObject as? Movable
            val terrainPos = pickList.terrainPickedObject?.terrainPosition
            isDraggingArmed = userObject is Renderable && callback?.canMoveRenderable(userObject) == true
            grabRotation = if (movable != null && !movable.isPointShape && terrainPos != null) {
                SphericalRotation(terrainPos, movable.referencePosition)
            } else null
        }
    }

    private suspend fun awaitPickResult(movement: Boolean): Pair<Any?, Position?> {
        val pickList = pickRequest.await()
        val userObject = pickList.topPickedObject?.userObject
        val referencePosition = (userObject as? Movable)?.referencePosition
        val terrainPosition = pickList.terrainPickedObject?.terrainPosition
        // Reference position is a priority during movement, but terrain position is a priority on pick
        val position = if (movement) referencePosition ?: terrainPosition else terrainPosition ?: referencePosition
        return userObject to position
    }

}