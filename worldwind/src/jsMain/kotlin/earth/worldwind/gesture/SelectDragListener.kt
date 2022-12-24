package earth.worldwind.gesture

import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec2
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Movable
import org.w3c.dom.TouchEvent
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent

open class SelectDragListener(protected val wwd: WorldWindow) {
    /**
     * Enable/disable mouse events processing.
     * If disabled, highlighting of Renderables and all callbacks will be switched off.
     */
    var isEnabled = true
    /**
     * Enable/disable dragging of flying objects using their terrain projection position
     */
    var isDragTerrainPosition = false
    /**
     * Main interface representing all interaction callbacks
     */
    var callback: SelectDragCallback? = null

    protected var pickedPosition: Position? = null
    protected var pickedRenderable: Renderable? = null
    protected val oldHighlighted = mutableSetOf<Highlightable>()
    protected val newHighlighted = mutableSetOf<Highlightable>()
    protected var isDragArmed = false
    private val dragRefPt = Vec2()
    private val lastTranslation = Vec2()

    protected val handlePick = EventListener { event ->
        // Determine pick point from event
        var clientX = 0
        var clientY = 0
        when (event) {
            is MouseEvent -> {
                clientX = event.clientX
                clientY = event.clientY
            }
            is TouchEvent -> {
                event.changedTouches.item(0)?.let { touch ->
                    clientX = touch.clientX
                    clientY = touch.clientY
                } ?: return@EventListener
            }
            else -> return@EventListener
        }

        // Do not pick new items until drag in progress or listener is disabled
        if (isDragArmed || !isEnabled) return@EventListener

        // Reset previous pick result
        pickedPosition = null
        pickedRenderable = null
        lastTranslation.set(0.0, 0.0)

        // Get pick point in canvas coordinates
        val pickPoint = wwd.canvasCoordinates(clientX, clientY)

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

        // Take reference position as a backup, if user pressed outside the globe
        if (topPickedObject is Movable && pickedPosition == null) pickedPosition = topPickedObject.referencePosition

        // Resolve conflict between item movement and globe rotation
        if (topPickedObject is Renderable && callback?.canMoveRenderable(topPickedObject) == true) {
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
        callback?.let { callback ->
            pickedPosition?.let { position ->
                val renderable = pickedRenderable
                if (renderable != null && callback.canPickRenderable(renderable))
                    callback.onRenderablePicked(renderable, position) else callback.onTerrainPicked(position)
            } ?: callback.onNothingPicked()
            wwd.requestRedraw()
        }
    }

    protected val handleSecondaryClick: (GestureRecognizer) -> Unit = {
        callback?.let { callback ->
            pickedPosition?.let { position ->
                val renderable = pickedRenderable
                if (renderable != null && callback.canPickRenderable(renderable))
                    callback.onRenderableContext(renderable, position) else callback.onTerrainContext(position)
            } ?: callback.onNothingContext()
            wwd.requestRedraw()
        }
    }

    protected val handleDrag: (GestureRecognizer) -> Unit = { recognizer ->
        when (recognizer.state) {
            BEGAN, CHANGED -> {
                isDragArmed = true
                val callback = callback
                val renderable = pickedRenderable
                // Reference position is a priority during movement
                val toPosition = if (renderable is Movable) renderable.referencePosition else pickedPosition
                if (toPosition != null && renderable != null && callback != null) {
                    // First we compute the screen coordinates of the position's "ground" point. We'll apply the
                    // screen X and Y drag distances to this point, from which we'll compute a new position,
                    // wherein we restore the original position's altitude.
                    val fromPosition = Position(toPosition)
                    val clapToGround = isDragTerrainPosition || renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
                    val movePoint = wwd.canvasCoordinates(recognizer.clientX, recognizer.clientY)
                    if (clapToGround && wwd.engine.pickTerrainPosition(movePoint.x, movePoint.y, toPosition) != null
                        || !clapToGround && wwd.engine.geographicToScreenPoint(fromPosition.latitude, fromPosition.longitude, 0.0, dragRefPt)
                        && wwd.engine.screenPointToGroundPosition(
                            dragRefPt.x + recognizer.translationX - lastTranslation.x,
                            dragRefPt.y + recognizer.translationY - lastTranslation.y,
                            toPosition
                        )) {
                        // Backup last translation
                        lastTranslation.set(recognizer.translationX, recognizer.translationY)
                        // Restore original altitude
                        toPosition.altitude = fromPosition.altitude
                        // Update movable position
                        if (renderable is Movable) renderable.moveTo(wwd.engine.globe, toPosition)
                        // Notify callback
                        callback.onRenderableMoved(renderable, fromPosition, toPosition)
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
