package earth.worldwind.gesture

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.shape.Movable
import kotlinx.coroutines.runBlocking
import java.awt.event.MouseEvent

open class SelectDragDetector(protected val wwd: WorldWindow) {
    var callback: SelectDragCallback? = null
    var isEnabled = true

    protected var pickedUserObject: Any? = null
    protected var pickedPosition: Position? = null
    protected var isDraggingArmed = false
    protected var isDragging = false
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

    open fun onMouseEvent(event: MouseEvent): Boolean {
        if (!isEnabled || callback == null) return false

        return when (event.id) {
            MouseEvent.MOUSE_PRESSED -> {
                pick(event)
                false
            }

            MouseEvent.MOUSE_CLICKED -> {
                if (event.clickCount >= 2) onDoubleClick() else if (event.button == MouseEvent.BUTTON1) onSingleClick()
                false
            }

            MouseEvent.MOUSE_DRAGGED -> {
                if (!isDraggingArmed) return false
                val cb = callback ?: return false
                val userObject = pickedUserObject ?: return false
                val movable = userObject as? Movable
                val fromPosition = movable?.referencePosition ?: pickedPosition ?: return false
                val toPosition = Position()

                val p = wwd.viewportCoordinates(event.x, event.y)
                val rotation = grabRotation
                val moved = if (rotation == null) {
                    // Approach A — ground-clamped point shape: snap to cursor on terrain.
                    wwd.engine.pickTerrainPosition(p.x, p.y, toPosition)
                } else {
                    // Approach B — anchor tracking: raycast the cursor onto the surface the grab
                    // anchor lives on, then rotate that point into the reference's frame.
                    val mode = movable?.altitudeMode
                    val elevated = mode != null && mode != AltitudeMode.CLAMP_TO_GROUND ||
                        grabAltitude > wwd.engine.globe.getElevation(
                            fromPosition.latitude, fromPosition.longitude
                        ) + ELEVATED_THRESHOLD
                    val hit = if (elevated) {
                        wwd.engine.screenPointToPositionAtAltitude(p.x, p.y, grabAltitude, toPosition)
                    } else {
                        wwd.engine.pickTerrainPosition(p.x, p.y, toPosition)
                    }
                    if (hit) { rotation.apply(toPosition); true } else false
                }

                if (moved) {
                    toPosition.altitude = fromPosition.altitude
                    cb.onObjectMoved(userObject, fromPosition, toPosition)
                    movable?.moveTo(wwd.engine.globe, toPosition)
                    isDragging = true
                    wwd.requestRedraw()
                    true
                } else false
            }

            MouseEvent.MOUSE_RELEASED -> {
                if (isDragging) {
                    val cb = callback
                    val userObject = pickedUserObject
                    val position = pickedPosition
                    if (cb != null && userObject != null && position != null) cb.onObjectMovingFinished(userObject, position)
                }
                isDragging = false
                isDraggingArmed = false
                false
            }

            else -> false
        }
    }

    protected open fun onSingleClick() {
        val cb = callback ?: return
        val position = pickedPosition
        val userObject = pickedUserObject
        when {
            position == null -> cb.onNothingPicked()
            userObject != null && cb.canPickObjects(userObject) -> cb.onObjectPicked(userObject, position)
            else -> cb.onTerrainPicked(position)
        }
        wwd.requestRedraw()
    }

    protected open fun onDoubleClick() {
        val cb = callback ?: return
        val position = pickedPosition
        val userObject = pickedUserObject
        when {
            position == null -> cb.onNothingContext()
            userObject != null && cb.canPickObjects(userObject) -> cb.onObjectDoubleTap(userObject, position)
            else -> cb.onTerrainDoubleTap(position)
        }
        wwd.requestRedraw()
    }

    protected open fun pick(event: MouseEvent) {
        val p = wwd.viewportCoordinates(event.x, event.y)
        val pickList = runBlocking { wwd.pickAsync(p.x, p.y, 4.0, 4.0).await() }
        val topPicked = pickList.topPickedObject
        val topObject = topPicked?.userObject
        val movable = topObject as? Movable
        val terrainPos = pickList.terrainPickedObject?.terrainPosition
        pickedUserObject = topObject
        // Prefer the depth-tested shape-surface point over the terrain behind the shape.
        val shapePickPos = topPicked?.geographicPoint(wwd.engine.globe)
        pickedPosition = shapePickPos ?: terrainPos ?: movable?.referencePosition
        isDraggingArmed = topObject != null && callback?.canMoveObjects(topObject) == true
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
            grabAltitude = if (movable.isPointShape)
                wwd.engine.globe.getAbsolutePosition(movable.referencePosition, movable.altitudeMode).altitude
            else grabAnchor.altitude
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
