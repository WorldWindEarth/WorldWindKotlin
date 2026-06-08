package earth.worldwind.gesture

import earth.worldwind.BasicWorldWindowController
import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.gesture.GestureState.*
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Highlightable
import earth.worldwind.shape.Movable
import earth.worldwind.util.eventListener
import org.w3c.dom.TouchEvent
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
    protected var pickedUserObject: Any? = null
    protected val oldHighlighted = mutableSetOf<Highlightable>()
    protected val newHighlighted = mutableSetOf<Highlightable>()
    protected var isDragging = false
    protected var isDraggingArmed = false
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

    protected val handlePick = eventListener { event ->
        // Do not pick new items if dragging is in progress or detector is disabled
        if (isDragging || !isEnabled) return@eventListener

        // Skip re-pick mid-press (mousemove with held button, or any touchmove). Re-arming
        // against whatever's under the cursor *now* would silently turn a finger drift off
        // the shape during the pan threshold into a globe pan. Mouse hover still re-picks
        // for highlighting.
        if (event is MouseEvent && event.buttons.toInt() != 0) return@eventListener
        // Type-string check first: macOS Safari does not define a global `TouchEvent`, so
        // `event is TouchEvent` (compiled to `instanceof TouchEvent`) throws ReferenceError
        // and aborts the whole handler. String compare short-circuits the instanceof away.
        if (event.type == "touchmove") return@eventListener

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
                } ?: return@eventListener
            }
            else -> return@eventListener
        }

        // Reset previous pick result
        pickedPosition = null
        pickedUserObject = null

        // Get pick point in canvas coordinates
        val pickPoint = wwd.canvasCoordinates(clientX, clientY)

        // Pick objects in selected point
        val pickList = wwd.pick(pickPoint)

        val topPicked = pickList.topPickedObject
        val topPickedObject = topPicked?.userObject
        val terrainPos = pickList.terrainPickedObject?.terrainPosition
        // Prefer the depth-tested shape-surface point over the terrain behind the shape.
        pickedPosition = topPicked?.geographicPoint(wwd.engine.globe) ?: terrainPos

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

        pickedUserObject = topPickedObject

        // Take reference position as a backup, if user pressed outside the globe
        if (topPickedObject is Movable && pickedPosition == null) pickedPosition = topPickedObject.referencePosition

        // Determine whether the dragging flag should be "armed".
        isDraggingArmed = topPickedObject != null && callback?.canMoveObjects(topPickedObject) == true

        // Approach A applies only to ground-clamped point shapes; everything else takes Approach
        // B with anchor tracking. Mousedown is filtered out by the `buttons != 0` guard above, so
        // this captures the last hover before press (mouse) or the touchstart event itself (touch).
        // Point shapes render with a billboard depth offset so the depth-readback unprojection
        // is shifted toward the camera; use the reference position directly for them so the
        // rotation stays identity. Extended shapes use the reliable depth-reconstructed point
        // so the grabbed surface point tracks the finger.
        val movable = topPickedObject as? Movable
        val isGroundClampedPoint = movable != null
                && movable.isPointShape
                && movable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
        val grabAnchor = if (movable != null && movable.isPointShape) movable.referencePosition
        else pickedPosition ?: terrainPos
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

    protected val handlePrimaryClick: (GestureRecognizer) -> Unit = {
        callback?.let { callback ->
            pickedPosition?.let { position ->
                val userObject = pickedUserObject
                if (userObject != null && callback.canPickObjects(userObject))
                    callback.onObjectPicked(userObject, position) else callback.onTerrainPicked(position)
            } ?: callback.onNothingPicked()
            wwd.requestRedraw()
        }
    }

    protected val handleSecondaryClick: (GestureRecognizer) -> Unit = {
        callback?.let { callback ->
            pickedPosition?.let { position ->
                val userObject = pickedUserObject
                if (userObject != null && callback.canPickObjects(userObject))
                    callback.onObjectContext(userObject, position) else callback.onTerrainContext(position)
            } ?: callback.onNothingContext()
            wwd.requestRedraw()
        }
    }

    protected val handleDrag: (GestureRecognizer) -> Unit = { recognizer ->
        when (recognizer.state) {
            BEGAN, CHANGED -> {
                val callback = callback
                val userObject = pickedUserObject
                val movable = userObject as? Movable
                // Reference position is a priority during movement
                val fromPosition = movable?.referencePosition ?: pickedPosition
                if (fromPosition != null && userObject != null && callback != null) {
                    // Signal that dragging is in progress
                    isDragging = true

                    val toPosition = Position()
                    val cursor = wwd.canvasCoordinates(recognizer.clientX, recognizer.clientY)
                    val rotation = grabRotation
                    val moved = if (rotation == null) {
                        // Approach A — ground-clamped point shape: snap to cursor on terrain.
                        wwd.engine.pickTerrainPosition(cursor.x, cursor.y, toPosition)
                    } else {
                        // Approach B — anchor tracking: raycast the cursor onto the surface the
                        // grab anchor lives on, then rotate that point into the reference's frame.
                        val mode = movable?.altitudeMode
                        val elevated = mode != null && mode != AltitudeMode.CLAMP_TO_GROUND ||
                            grabAltitude > wwd.engine.globe.getElevation(
                                fromPosition.latitude, fromPosition.longitude
                            ) + ELEVATED_THRESHOLD
                        val hit = if (elevated) {
                            wwd.engine.screenPointToPositionAtAltitude(cursor.x, cursor.y, grabAltitude, toPosition)
                        } else {
                            wwd.engine.pickTerrainPosition(cursor.x, cursor.y, toPosition)
                        }
                        if (hit) { rotation.apply(toPosition); true } else false
                    }
                    if (moved) {
                        toPosition.altitude = fromPosition.altitude
                        callback.onObjectMoved(userObject, fromPosition, toPosition)
                        movable?.moveTo(wwd.engine.globe, toPosition)
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
                val userObject = pickedUserObject
                if (userObject != null && position != null && callback != null) {
                    callback.onObjectMovingFinished(userObject, position)
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

        /**
         * Meters above the terrain at the reference lat/lon above which the grabbed surface is
         * treated as elevated. Below it the drag adapts to terrain via [earth.worldwind.WorldWind.pickTerrainPosition];
         * above it the cursor is unprojected onto the offset ellipsoid at [grabAltitude] to keep
         * cursor-to-surface tracking consistent under perspective.
         */
        private const val ELEVATED_THRESHOLD = 1.0
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
