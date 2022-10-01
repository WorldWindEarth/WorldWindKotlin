package earth.worldwind.gesture

import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Position
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Placemark
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent

open class SelectDragListener(protected val wwd: WorldWindow) {
    /**
     * Enable/disable mouse events processing.
     * If disabled, highlighting of Renderables and all callbacks will be switched off.
     */
    var isEnabled = true
    /**
     * Main interface representing all interaction callbacks
     */
    var callback: SelectDragCallback? = null

    protected var pickedPosition: Position? = null
    protected var pickedRenderable: Renderable? = null
    protected val oldHighlighted = mutableSetOf<Highlightable>()
    protected val newHighlighted = mutableSetOf<Highlightable>()
    protected var isDragArmed = false

    protected val handlePick = EventListener {
        val evt = it as MouseEvent

        // Do not pick new items until drag in progress or listener is disabled
        if (isDragArmed || !isEnabled) return@EventListener

        // Reset previous pick result
        pickedPosition = null
        pickedRenderable = null

        // Get pick point in canvas coordinates
        val pickPoint = wwd.canvasCoordinates(evt.clientX, evt.clientY)

        // Pick objects in selected point
        val pickList = wwd.pick(pickPoint)

        // Get picked position from terrain object, if user pressed inside the globe
        pickedPosition = pickList.terrainPickedObject?.terrainPosition

//        // NOTE Region selection use bounding box intersection with specified rectangle. Use highlighter path thickness instead.
//        if (!pickList.hasNonTerrainObjects) {
//            pickList = wwd.pickShapesInRegion(
//                Viewport(pickPoint.x - SLOPE / 2 , pickPoint.y - SLOPE / 2, SLOPE, SLOPE)
//            )
//        }

        // Redraw canvas in case we de-highlight old or highlight new renderables
        var redrawRequired = false

        // Put picked renderables into new highlighted set
        for (obj in pickList.objects) if (!obj.isTerrain && obj.userObject is Highlightable) newHighlighted.add(obj.userObject)

        // De-highlight any previously highlighted renderables which is not selected or picked
        for (highlighted in oldHighlighted)
            if (!newHighlighted.contains(highlighted)
                && highlighted is Renderable && highlighted.getUserProperty(HIGHLIGHT_LOCKED_KEY) != true) {
                highlighted.isHighlighted = false
                oldHighlighted.remove(highlighted)
                redrawRequired = true
            }

        // Highlight picked objects which was not highlighted yet
        for (highlighted in newHighlighted) if (!oldHighlighted.contains(highlighted)) {
            highlighted.isHighlighted = true
            oldHighlighted.add(highlighted)
            redrawRequired = true
        }

        // Clear new highlighted buffer until next frame
        newHighlighted.clear()

        // Update the window if we changed anything
        if (redrawRequired) wwd.requestRedraw()

        // Get top picked renderable to use it in listener callbacks
        val topPickedObject = pickList.topPickedObject?.userObject
        if (topPickedObject is Renderable) pickedRenderable = topPickedObject

        // Resolve conflict between item movement and globe rotation
        if (topPickedObject is Placemark && callback?.canMoveRenderable(topPickedObject) == true) {
            val controller = wwd.controller
            if (controller is BasicWorldWindowController) {
                controller.primaryDragRecognizer.state = FAILED
                controller.panRecognizer.state = FAILED
            }
        } else {
            dragRecognizer.state = FAILED
            panRecognizer.state = FAILED
        }
    }

    protected val handlePrimaryClick: (GestureRecognizer) -> Unit = {
        val callback = callback
        val position = pickedPosition
        if (position != null && callback != null) {
            val renderable = pickedRenderable
            if (renderable != null && callback.canPickRenderable(renderable))
                callback.onRenderablePicked(renderable, position) else callback.onTerrainPicked(position)
            wwd.requestRedraw()
        }
    }

    protected val handleSecondaryClick: (GestureRecognizer) -> Unit = {
        val callback = callback
        val position = pickedPosition
        if (position != null && callback != null) {
            val renderable = pickedRenderable
            if (renderable != null && callback.canPickRenderable(renderable))
                callback.onRenderableContext(renderable, position) else callback.onTerrainContext(position)
            wwd.requestRedraw()
        }
    }

    protected val handleDrag: (GestureRecognizer) -> Unit = { recognizer ->
        when (recognizer.state) {
            BEGAN, CHANGED -> {
                isDragArmed = true
                val callback = callback
                val fromPosition = pickedPosition
                val renderable = pickedRenderable
                if (fromPosition != null && renderable != null && callback != null) {
                    val movePoint = wwd.canvasCoordinates(recognizer.clientX, recognizer.clientY)
                    // Backup original altitude
                    val altitude = fromPosition.altitude
                    // First we compute the screen coordinates of the position's "ground" point.  We'll apply the
                    // screen X and Y drag distances to this point, from which we'll compute a new position,
                    // wherein we restore the original position's altitude.
                    val toPosition = wwd.engine.pickTerrainPosition(movePoint.x, movePoint.y)
                    if (toPosition != null) {
                        // Restore original altitude
                        toPosition.altitude = altitude
                        // Callback event
                        callback.onRenderableMoved(renderable, fromPosition, toPosition)
                        // Remember new position
                        pickedPosition = toPosition
                        // Reflect the change in position on the globe.
                        wwd.requestRedraw()
                    } else {
                        // Probably clipped by near/far clipping plane or off the globe.
                        // The position was not updated. Stop the drag.
                        recognizer.state = CANCELLED
                    }
                }
            }
            ENDED -> {
                val callback = callback
                val position = pickedPosition
                val renderable = pickedRenderable
                if (renderable != null && position != null && callback != null) {
                    callback.onRenderableMovingFinished(renderable, position)
                    wwd.requestRedraw()
                }
                isDragArmed = false
            }
            CANCELLED -> isDragArmed = false
            else -> {}
        }
    }

    protected val primaryClickRecognizer = ClickRecognizer(wwd.canvas, handlePrimaryClick)
    protected val tapRecognizer = TapRecognizer(wwd.canvas, handlePrimaryClick)
    protected val secondaryClickRecognizer = ClickRecognizer(wwd.canvas, handleSecondaryClick).apply { button = 2 } // Secondary mouse button
    protected val doubleTapRecognizer = TapRecognizer(wwd.canvas, handleSecondaryClick).apply { numberOfTaps = 2 } // Double tap
    protected val dragRecognizer = DragRecognizer(wwd.canvas, handleDrag)
    protected val panRecognizer = PanRecognizer(wwd.canvas, handleDrag)

    companion object {
        //        const val SLOPE = 16
        const val HIGHLIGHT_LOCKED_KEY = "highlight_locked"
    }

    init {
        wwd.addEventListener("mousedown", handlePick)
        wwd.addEventListener("mousemove", handlePick)
        wwd.addEventListener("touchstart", handlePick)
        wwd.addEventListener("touchmove", handlePick)
    }
}
