package earth.worldwind.gesture

import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.ViewConfiguration
import earth.worldwind.PickedObjectList
import earth.worldwind.WorldWindow
import earth.worldwind.render.Renderable
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

open class SelectDragListener(protected val wwd: WorldWindow) : SimpleOnGestureListener() {

    protected val slope = ViewConfiguration.get(wwd.context).scaledTouchSlop
    protected lateinit var pickRequest: Deferred<PickedObjectList> // last picked objects from onDown event
    protected var isDraggingArmed = false
    var isDragging = false
        private set
    var callback: SelectDragCallback? = null

    override fun onDown(event: MotionEvent): Boolean {
        pick(event)
        return false
    }

    override fun onSingleTapUp(event: MotionEvent): Boolean {
        val callback = callback ?: return false
        wwd.mainScope.launch {
            val pickList = pickRequest.await()
            val position = pickList.terrainPickedObject?.terrainPosition
            if (position != null) {
                val renderable = pickList.topPickedObject?.userObject
                if (renderable is Renderable && callback.canPickRenderable(renderable))
                    callback.onRenderablePicked(renderable, position)
                else callback.onTerrainPicked(position)
                wwd.requestRedraw()
            }
        }
        return false
    }

    override fun onScroll(downEvent: MotionEvent, moveEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        val callback = callback ?: return false
        wwd.mainScope.launch {
            val pickList = pickRequest.await()
            val fromPosition = pickList.terrainPickedObject?.terrainPosition
            val renderable = pickList.topPickedObject?.userObject
            if (isDraggingArmed && fromPosition != null && renderable is Renderable) {
                // Signal that dragging is in progress
                isDragging = true

                // Backup original altitude
                val altitude = fromPosition.altitude
                // First we compute the screen coordinates of the position's "ground" point.  We'll apply the
                // screen X and Y drag distances to this point, from which we'll compute a new position,
                // wherein we restore the original position's altitude.
                val toPosition = wwd.engine.pickTerrainPosition(moveEvent.x.toDouble(), moveEvent.y.toDouble())
                if (toPosition != null) {
                    // Restore original altitude
                    toPosition.altitude = altitude
                    // Callback event
                    callback.onRenderableMoved(renderable, fromPosition, toPosition)
                    // Remember new position
                    fromPosition.copy(toPosition)
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
            val pickList = pickRequest.await()
            val position = pickList.terrainPickedObject?.terrainPosition
            val renderable = pickList.topPickedObject?.userObject
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
        // Callback event
        val callback = callback ?: return
        wwd.mainScope.launch {
            val pickList = pickRequest.await()
            val position = pickList.terrainPickedObject?.terrainPosition
            val renderable = pickList.topPickedObject?.userObject
            if (renderable is Renderable && position != null) {
                callback.onRenderableMovingFinished(renderable, position)
                wwd.requestRedraw()
            }
        }
    }

    private fun showContext() {
        val callback = callback ?: return
        wwd.mainScope.launch {
            val pickList = pickRequest.await()
            val position = pickList.terrainPickedObject?.terrainPosition
            if (position != null) {
                val renderable = pickList.topPickedObject?.userObject
                if (renderable is Renderable) callback.onRenderableContext(renderable, position)
                else callback.onTerrainContext(position)
                wwd.requestRedraw()
            }
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

}