package earth.worldwind.gesture

import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.geom.Vec2
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Movable
import org.w3c.dom.TouchEvent
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.MouseEvent

open class SelectDragDetector(protected val wwd: WorldWindow) {
    /**
     * Main interface representing all interaction callbacks
     */
    var callback: SelectDragCallback? = null
    /**
     * Enable/disable mouse events processing.
     * If disabled, highlighting of Renderables and all callbacks will be switched off.
     */
    var isEnabled = true
    protected var pickedPosition: Position? = null
    protected var pickedRenderable: Renderable? = null
    protected val oldHighlighted = mutableSetOf<Highlightable>()
    protected val newHighlighted = mutableSetOf<Highlightable>()
    protected var isDragging = false
    protected var isDraggingArmed = false
    private val dragRefPt = Vec2()
    private val lastTranslation = Vec2()
    // Press-time rigid rotation taking the cursor's terrain pick to the renderable's reference.
    // Captured only for extended shapes (Polygon, Path, Mesh) that need the grabbed point pinned
    // to the cursor; null for point shapes (Placemark, Label, sightlines) which snap their anchor
    // directly to the cursor each event.
    private var grabRotation: SphericalRotation? = null

    protected val handlePick = EventListener { event ->
        // Do not pick new items if dragging is in progress or detector is disabled
        if (isDragging || !isEnabled) return@EventListener

        // Skip re-pick mid-press (mousemove with held button, or any touchmove). Re-arming
        // against whatever's under the cursor *now* would silently turn a finger drift off
        // the shape during the pan threshold into a globe pan. Mouse hover still re-picks
        // for highlighting.
        if (event is MouseEvent && event.buttons.toInt() != 0) return@EventListener
        if (event is TouchEvent && event.type == "touchmove") return@EventListener

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
                && highlighted is Renderable && highlighted.getUserProperty<Boolean>(HIGHLIGHT_LOCKED_KEY) != true) {
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

        // Determine whether the dragging flag should be "armed".
        isDraggingArmed = topPickedObject is Renderable && callback?.canMoveRenderable(topPickedObject) == true

        // Capture grab-anchor rotation for extended shapes only. Mousedown is filtered out by the
        // `buttons != 0` guard above, so this captures the last hover before press (mouse) or the
        // touchstart event itself (touch).
        val movable = topPickedObject as? Movable
        val terrainPos = pickList.terrainPickedObject?.terrainPosition
        grabRotation = if (movable != null && !movable.isPointShape && terrainPos != null) {
            SphericalRotation(terrainPos, movable.referencePosition)
        } else null
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
                val callback = callback
                val renderable = pickedRenderable
                // Reference position is a priority during movement
                val fromPosition = if (renderable is Movable) renderable.referencePosition else pickedPosition
                if (fromPosition != null && renderable != null && callback != null) {
                    // Signal that dragging is in progress
                    isDragging = true

                    val toPosition = Position()
                    val toGround = renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
                    val moved = if (toGround) {
                        // Snap-to-cursor for point shapes (grabRotation == null), grab-anchor for
                        // extended shapes (grabRotation rotates the fresh terrain pick into the
                        // reference's frame, preserving the press-time offset).
                        val cursor = wwd.canvasCoordinates(recognizer.clientX, recognizer.clientY)
                        wwd.engine.pickTerrainPosition(cursor.x, cursor.y, toPosition).also {
                            if (it) grabRotation?.apply(toPosition)
                        }
                    } else {
                        // Screen-delta: project the reference at sea level, shift by the cursor's
                        // incremental delta, resolve back at sea level. Both ends must use altitude
                        // 0 to keep projection symmetric and avoid per-event drift.
                        val refMappedToScreen = wwd.engine.geographicToScreenPoint(
                            fromPosition.latitude, fromPosition.longitude, 0.0, dragRefPt
                        )
                        val deltaX = (recognizer.translationX - lastTranslation.x) * wwd.engine.densityFactor
                        val deltaY = (recognizer.translationY - lastTranslation.y) * wwd.engine.densityFactor
                        refMappedToScreen && wwd.engine.screenPointToGroundPosition(
                            dragRefPt.x + deltaX, dragRefPt.y + deltaY, toPosition
                        ).also { if (it) lastTranslation.set(recognizer.translationX, recognizer.translationY) }
                    }
                    if (moved) {
                        toPosition.altitude = fromPosition.altitude
                        callback.onRenderableMoved(renderable, fromPosition, toPosition)
                        if (renderable is Movable) renderable.moveTo(wwd.engine.globe, toPosition)
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
                cancelDragging()
            }
            CANCELLED -> cancelDragging()
            else -> {}
        }
    }

    protected val primaryClickRecognizer = ClickRecognizer(wwd.canvas, handlePrimaryClick)
    protected val tapRecognizer = TapRecognizer(wwd.canvas, handlePrimaryClick)
    protected val secondaryClickRecognizer = ClickRecognizer(wwd.canvas, handleSecondaryClick).apply { button = 2 } // Secondary mouse button
    protected val doubleTapRecognizer = TapRecognizer(wwd.canvas, handleSecondaryClick).apply { numberOfTaps = 2 } // Double tap
    protected val dragRecognizer = object : DragRecognizer(wwd.canvas, handleDrag) {
        override fun shouldRecognize() = super.shouldRecognize() && isDraggingArmed
    }
    protected val panRecognizer = object : PanRecognizer(wwd.canvas, handleDrag) {
        override fun shouldRecognize() = super.shouldRecognize() && isDraggingArmed
    }

    companion object {
//        const val SLOPE = 16
        const val HIGHLIGHT_LOCKED_KEY = "highlight_locked"
    }

    init {
        wwd.addEventListener("mousedown", handlePick)
        wwd.addEventListener("mousemove", handlePick)
        wwd.addEventListener("touchstart", handlePick)
        wwd.addEventListener("touchmove", handlePick)

        // Resolve conflict between item movement and globe rotation
        val controller = wwd.controller
        if (controller is BasicWorldWindowController) {
            controller.primaryDragRecognizer.requireRecognizerToFail(dragRecognizer)
            controller.panRecognizer.requireRecognizerToFail(panRecognizer)
        }
    }

    protected fun cancelDragging() {
        isDragging = false
        isDraggingArmed = false
    }
}
