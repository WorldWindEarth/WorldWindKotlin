package earth.worldwind.gesture

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_CANCEL
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_DOWN
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_MOVE
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_POINTER_DOWN
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_UP
import earth.worldwind.shape.Movable
import kotlin.math.abs
import platform.QuartzCore.CACurrentMediaTime

/**
 * iOS port of the JVM/Android [SelectDragDetector]. Mirrors the JVM pattern: pick is driven
 * synchronously on DOWN (via [WorldWindow.pick] which drains the pick frame inline), so by
 * the time we return to the dispatcher we already know whether a draggable renderable is
 * under the finger. Subsequent MOVE events drive the drag directly when armed; otherwise
 * they fall through to [earth.worldwind.BasicWorldWindowController] for camera panning.
 */
open class SelectDragDetector(protected val wwd: WorldWindow) {
    var callback: SelectDragCallback? = null
    var isEnabled = true
    var tapTimeoutMs: Long = 250
    var doubleTapTimeoutMs: Long = 350
    var tapSlopPx: Double = 10.0

    protected var pickedUserObject: Any? = null
    protected var pickedPosition: Position? = null
    protected var isDraggingArmed = false
    protected var isDragging = false

    private var downX = 0.0
    private var downY = 0.0
    private var lastDragX = 0.0
    private var lastDragY = 0.0
    private var downTimeMs = 0L
    private var lastTapTimeMs = 0L
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

    open fun onTouchEvent(event: TouchEvent): Boolean {
        if (!isEnabled || callback == null) return false
        val x = event.getX(event.actionIndex).toDouble()
        val y = event.getY(event.actionIndex).toDouble()
        return when (event.actionMasked) {
            ACTION_DOWN -> {
                downX = x; downY = y; lastDragX = x; lastDragY = y
                downTimeMs = nowMs()
                pick(x, y)
                false
            }
            ACTION_POINTER_DOWN -> { cancelDragging(); false }
            ACTION_MOVE -> handleDrag(x, y)
            ACTION_UP -> {
                if (isDragging) finishDragging()
                else if (isTap(x, y)) onTap()
                isDragging = false
                isDraggingArmed = false
                false
            }
            ACTION_CANCEL -> { cancelDragging(); false }
            else -> false
        }
    }

    private fun handleDrag(x: Double, y: Double): Boolean {
        if (!isDraggingArmed) return false
        val cb = callback ?: return false
        val userObject = pickedUserObject ?: return false
        val movable = userObject as? Movable
        val fromPosition = movable?.referencePosition ?: pickedPosition ?: return false
        val toPosition = Position()
        val rotation = grabRotation
        val moved = if (rotation == null) {
            // Approach A — ground-clamped point shape: snap to cursor on terrain.
            wwd.engine.pickTerrainPosition(x, y, toPosition)
        } else {
            // Approach B — anchor tracking: raycast the cursor onto the surface the grab anchor
            // lives on, then rotate that point into the reference's frame. ABSOLUTE shapes are
            // pinned to a specific altitude regardless of terrain, so they always use the
            // altitude-aware unproject. Everything else classifies as elevated when the grabbed
            // surface sits meaningfully above the terrain at the reference's lat/lon — catches
            // RELATIVE_TO_GROUND with altitude > 0 and the top face of extruded shapes. Ground-
            // anchored picks fall back to terrain so the drag adapts to varying terrain.
            val mode = movable?.altitudeMode
            val elevated = mode == AltitudeMode.ABSOLUTE ||
                grabAltitude > wwd.engine.globe.getElevation(
                    fromPosition.latitude, fromPosition.longitude
                ) + ELEVATED_THRESHOLD
            val hit = if (elevated) {
                wwd.engine.screenPointToPositionAtAltitude(x, y, grabAltitude, toPosition)
            } else {
                wwd.engine.pickTerrainPosition(x, y, toPosition)
            }
            if (hit) { rotation.apply(toPosition); true } else false
        }
        lastDragX = x; lastDragY = y
        return if (moved) {
            toPosition.altitude = fromPosition.altitude
            cb.onObjectMoved(userObject, fromPosition, toPosition)
            movable?.moveTo(wwd.engine.globe, toPosition)
            isDragging = true
            wwd.requestRedraw()
            true
        } else false
    }

    private fun finishDragging() {
        val cb = callback; val userObject = pickedUserObject; val position = pickedPosition
        if (cb != null && userObject != null && position != null) {
            cb.onObjectMovingFinished(userObject, position)
        }
    }

    private fun cancelDragging() {
        if (isDragging) finishDragging()
        isDragging = false
        isDraggingArmed = false
    }

    private fun nowMs(): Long = (CACurrentMediaTime() * 1000.0).toLong()

    private fun isTap(upX: Double, upY: Double) =
        abs(upX - downX) <= tapSlopPx && abs(upY - downY) <= tapSlopPx &&
            (nowMs() - downTimeMs) <= tapTimeoutMs

    private fun onTap() {
        val cb = callback ?: return
        val now = nowMs()
        val isDouble = (now - lastTapTimeMs) <= doubleTapTimeoutMs
        lastTapTimeMs = if (isDouble) 0L else now
        val position = pickedPosition
        val userObject = pickedUserObject
        when {
            position == null -> if (isDouble) cb.onNothingContext() else cb.onNothingPicked()
            userObject != null && cb.canPickObjects(userObject) ->
                if (isDouble) cb.onObjectDoubleTap(userObject, position)
                else cb.onObjectPicked(userObject, position)
            else -> if (isDouble) cb.onTerrainDoubleTap(position) else cb.onTerrainPicked(position)
        }
        wwd.requestRedraw()
    }

    protected open fun pick(x: Double, y: Double) {
        val pickList = wwd.pick(x.toFloat(), y.toFloat(), 8f, 8f)
        val topPicked = pickList.topPickedObject
        val userObject = topPicked?.userObject
        val movable = userObject as? Movable
        val terrainPos = pickList.terrainPickedObject?.terrainPosition
        pickedUserObject = userObject
        // Prefer the depth-tested shape-surface point over the terrain behind the shape.
        val shapePickPos = topPicked?.geographicPoint(wwd.engine.globe)
        pickedPosition = shapePickPos ?: terrainPos ?: movable?.referencePosition
        isDraggingArmed = userObject != null && callback?.canMoveObjects(userObject) == true
        // Approach A applies only to ground-clamped point shapes; everything else takes Approach
        // B with anchor tracking. Point shapes render with a billboard depth offset so the
        // depth-readback unprojection is shifted toward the camera; use the reference position
        // directly for them so the rotation stays identity. Extended shapes use the reliable
        // depth-reconstructed point so the grabbed surface point tracks the finger.
        val isGroundClampedPoint = movable != null
                && movable.isPointShape
                && movable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
        val grabAnchor = if (movable != null && movable.isPointShape) movable.referencePosition
        else shapePickPos ?: terrainPos
        if (movable != null && !isGroundClampedPoint && grabAnchor != null) {
            grabRotation = SphericalRotation(grabAnchor, movable.referencePosition)
            grabAltitude = grabAnchor.altitude
        } else {
            grabRotation = null
            grabAltitude = 0.0
        }
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
