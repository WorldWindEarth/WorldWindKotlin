package earth.worldwind.gesture

import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.ViewConfiguration
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
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
     * Enable/disable dragging of flying objects using their terrain projection position.
     */
    var isDragTerrainPosition = false
    /**
     * Issue pick callback after the gesture detector is confident that the user's first tap is not followed
     * by a second tap leading to a double-tap gesture.
     */
    var isSingleTapConfirmed = false
    protected open val gestureDetector = GestureDetector(wwd.context, this)
    protected val slop = ViewConfiguration.get(wwd.context).scaledTouchSlop
    protected lateinit var pickRequest: Deferred<PickedObjectList> // last picked objects from onDown event
    protected var isDragging = false
    protected var isDraggingArmed = false
    protected var draggingJob: Job? = null
    private val dragRefPt = Vec2()

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
        wwd.mainScope.launch {
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
        val x = moveEvent.x.toDouble()
        val y = moveEvent.y.toDouble()
        draggingJob?.cancel()
        draggingJob = wwd.mainScope.launch {
            val (renderable, fromPosition) = awaitPickResult(true)
            if (isDraggingArmed && fromPosition != null && renderable is Renderable) {
                // Signal that dragging is in progress
                isDragging = true

                // First we compute the screen coordinates of the position's "ground" point. We'll apply the
                // screen X and Y drag distances to this point, from which we'll compute a new position,
                // wherein we restore the original position's altitude.
                val toPosition = Position()
                val clapToGround = isDragTerrainPosition || renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
                if (clapToGround && wwd.engine.pickTerrainPosition(x, y, toPosition)
                    || !clapToGround && wwd.engine.geographicToScreenPoint(fromPosition.latitude, fromPosition.longitude, 0.0, dragRefPt)
                    && wwd.engine.screenPointToGroundPosition(dragRefPt.x - distanceX, dragRefPt.y - distanceY, toPosition)) {
                    // Restore original altitude
                    toPosition.altitude = fromPosition.altitude
                    // Notify callback
                    callback.onRenderableMoved(renderable, fromPosition, toPosition)
                    // Update movable position
                    if (renderable is Movable) renderable.moveTo(wwd.engine.globe, toPosition)
                    // Reflect the change in position on the globe.
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
        wwd.mainScope.launch {
            val (renderable, position) = awaitPickResult(true)
            if (renderable is Renderable && position != null) {
                callback.onRenderableMovingFinished(renderable, position)
                wwd.requestRedraw()
            }
        }
    }

    private fun showContext() {
        val callback = callback ?: return
        wwd.mainScope.launch {
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
        // Perform the pick at the screen x, y
        pickRequest = wwd.pickAsync(event.x - slop / 2f, event.y - slop / 2f, slop.toFloat(), slop.toFloat())
        wwd.mainScope.launch {
            // Get top picked object
            val userObject = pickRequest.await().topPickedObject?.userObject
            // Determine whether the dragging flag should be "armed".
            isDraggingArmed = userObject is Renderable && callback?.canMoveRenderable(userObject) == true
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