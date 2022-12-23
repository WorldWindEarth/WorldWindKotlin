package earth.worldwind.gesture

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

open class SelectDragListener(protected val wwd: WorldWindow) : SimpleOnGestureListener() {

    protected val slope = ViewConfiguration.get(wwd.context).scaledTouchSlop
    protected lateinit var pickRequest: Deferred<PickedObjectList> // last picked objects from onDown event
    protected var isDraggingArmed = false
    var isDragging = false
        protected set
    /**
     * Enable/disable dragging of flying objects using their terrain projection position
     */
    var isDragTerrainPosition = false
    var callback: SelectDragCallback? = null
    private val dragRefPt = Vec2()

    override fun onDown(event: MotionEvent): Boolean {
        pick(event)
        return false
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        val callback = callback ?: return false
        wwd.mainScope.launch {
            val (renderable, position) = awaitPickResult()
            if (position != null) {
                if (renderable is Renderable && callback.canPickRenderable(renderable)) {
                    callback.onRenderablePicked(renderable, position)
                } else callback.onTerrainPicked(position)
            } else callback.onNothingPicked()
            wwd.requestRedraw()
        }
        return false
    }

    override fun onScroll(downEvent: MotionEvent, moveEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val callback = callback ?: return false
        val x = moveEvent.x.toDouble()
        val y = moveEvent.y.toDouble()
        wwd.mainScope.launch {
            val (renderable, toPosition) = awaitPickResult()
            if (isDraggingArmed && toPosition != null && renderable is Renderable) {
                // Signal that dragging is in progress
                isDragging = true

                // First we compute the screen coordinates of the position's "ground" point. We'll apply the
                // screen X and Y drag distances to this point, from which we'll compute a new position,
                // wherein we restore the original position's altitude.
                val fromPosition = Position(toPosition)
                val clapToGround = isDragTerrainPosition || renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
                if (clapToGround && wwd.engine.pickTerrainPosition(x, y, toPosition) != null
                    || !clapToGround && wwd.engine.geographicToScreenPoint(fromPosition.latitude, fromPosition.longitude, 0.0, dragRefPt)
                    && wwd.engine.screenPointToGroundPosition(dragRefPt.x - distanceX, dragRefPt.y - distanceY, toPosition)) {
                    // Restore original altitude
                    toPosition.altitude = fromPosition.altitude
                    // Notify callback
                    callback.onRenderableMoved(renderable, fromPosition, toPosition)
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
            val (renderable, position) = awaitPickResult()
            if (renderable is Renderable && position != null) {
                callback.onRenderableDoubleTap(renderable, position)
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
        val callback = callback ?: return
        wwd.mainScope.launch {
            val (renderable, position) = awaitPickResult()
            if (renderable is Renderable && position != null) {
                callback.onRenderableMovingFinished(renderable, position)
                wwd.requestRedraw()
            }
        }
    }

    private fun showContext() {
        val callback = callback ?: return
        wwd.mainScope.launch {
            val (renderable, position) = awaitPickResult()
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
        pickRequest = wwd.pickAsync(event.x - slope / 2f, event.y - slope / 2f, slope.toFloat(), slope.toFloat())
        wwd.mainScope.launch {
            // Get top picked object
            val userObject = pickRequest.await().topPickedObject?.userObject
            // Determine whether the dragging flag should be "armed".
            isDraggingArmed = userObject is Renderable && callback?.canMoveRenderable(userObject) == true
        }
    }

    private suspend fun awaitPickResult(): Pair<Any?, Position?> {
        val pickList = pickRequest.await()
        val userObject = pickList.topPickedObject?.userObject
        val position = if (userObject is Movable) userObject.referencePosition
        else pickList.terrainPickedObject?.terrainPosition
        return userObject to position
    }

}