package earth.worldwind.gesture

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.geom.Vec2
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Movable
import kotlinx.coroutines.runBlocking
import java.awt.event.MouseEvent

open class SelectDragDetector(protected val wwd: WorldWindow) {
    var callback: SelectDragCallback? = null
    var isEnabled = true

    protected var pickedRenderable: Renderable? = null
    protected var pickedPosition: Position? = null
    protected var isDraggingArmed = false
    protected var isDragging = false
    // Cursor screen position from the previous drag event (screen-delta path only).
    private var lastDragX = 0.0
    private var lastDragY = 0.0
    private val dragRefPt = Vec2()
    // Press-time rigid rotation taking the cursor's terrain pick to the renderable's reference.
    // Captured only for extended shapes (Polygon, Path, Mesh) that need the grabbed point pinned
    // to the cursor; null for point shapes (Placemark, Label, sightlines) which snap their anchor
    // directly to the cursor each event.
    private var grabRotation: SphericalRotation? = null

    open fun onMouseEvent(event: MouseEvent): Boolean {
        if (!isEnabled || callback == null) return false

        return when (event.id) {
            MouseEvent.MOUSE_PRESSED -> {
                pick(event)
                val p = wwd.viewportCoordinates(event.x, event.y)
                lastDragX = p.x
                lastDragY = p.y
                false
            }

            MouseEvent.MOUSE_CLICKED -> {
                if (event.clickCount >= 2) onDoubleClick() else if (event.button == MouseEvent.BUTTON1) onSingleClick()
                false
            }

            MouseEvent.MOUSE_DRAGGED -> {
                if (!isDraggingArmed) return false
                val cb = callback ?: return false
                val renderable = pickedRenderable ?: return false
                val fromPosition = if (renderable is Movable) renderable.referencePosition else pickedPosition ?: return false
                val toPosition = Position()

                val toGround = renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
                val p = wwd.viewportCoordinates(event.x, event.y)
                val moved = if (toGround) {
                    // Snap-to-cursor for point shapes (grabRotation == null), grab-anchor for
                    // extended shapes (grabRotation rotates the fresh terrain pick into the
                    // reference's frame, preserving the press-time offset).
                    wwd.engine.pickTerrainPosition(p.x, p.y, toPosition).also {
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
                        dragRefPt.x + (p.x - lastDragX), dragRefPt.y + (p.y - lastDragY), toPosition
                    )
                }
                lastDragX = p.x
                lastDragY = p.y

                if (moved) {
                    toPosition.altitude = fromPosition.altitude
                    cb.onRenderableMoved(renderable, fromPosition, toPosition)
                    if (renderable is Movable) renderable.moveTo(wwd.engine.globe, toPosition)
                    isDragging = true
                    wwd.requestRedraw()
                    true
                } else false
            }

            MouseEvent.MOUSE_RELEASED -> {
                if (isDragging) {
                    val cb = callback
                    val renderable = pickedRenderable
                    val position = pickedPosition
                    if (cb != null && renderable != null && position != null) cb.onRenderableMovingFinished(renderable, position)
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
        val renderable = pickedRenderable
        when {
            position == null -> cb.onNothingPicked()
            renderable != null && cb.canPickRenderable(renderable) -> cb.onRenderablePicked(renderable, position)
            else -> cb.onTerrainPicked(position)
        }
        wwd.requestRedraw()
    }

    protected open fun onDoubleClick() {
        val cb = callback ?: return
        val position = pickedPosition
        val renderable = pickedRenderable
        when {
            position == null -> cb.onNothingContext()
            renderable != null && cb.canPickRenderable(renderable) -> cb.onRenderableDoubleTap(renderable, position)
            else -> cb.onTerrainDoubleTap(position)
        }
        wwd.requestRedraw()
    }

    protected open fun pick(event: MouseEvent) {
        val p = wwd.viewportCoordinates(event.x, event.y)
        val pickList = runBlocking { wwd.pickAsync(p.x, p.y, 4.0, 4.0).await() }
        val topObject = pickList.topPickedObject?.userObject
        val movable = topObject as? Movable
        val terrainPos = pickList.terrainPickedObject?.terrainPosition
        val renderable = topObject as? Renderable
        pickedRenderable = renderable
        pickedPosition = terrainPos ?: movable?.referencePosition
        isDraggingArmed = renderable != null && callback?.canMoveRenderable(renderable) == true
        grabRotation = if (movable != null && !movable.isPointShape && terrainPos != null) {
            SphericalRotation(terrainPos, movable.referencePosition)
        } else null
    }
}
