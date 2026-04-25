package earth.worldwind.gesture

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec2
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Movable
import kotlinx.coroutines.runBlocking
import java.awt.event.MouseEvent

open class SelectDragDetector(protected val wwd: WorldWindow) {
    var callback: SelectDragCallback? = null
    var isEnabled = true
    var isDragTerrainPosition = false

    protected var pickedRenderable: Renderable? = null
    protected var pickedPosition: Position? = null
    protected var isDraggingArmed = false
    protected var isDragging = false
    // Cursor screen position from the previous drag event, so we can apply incremental deltas
    // and shift the reference point in parallel to the cursor — instead of snapping it under
    // the cursor, which would jump the shape if the user grabbed any point other than the
    // reference itself.
    private var lastDragX = 0.0
    private var lastDragY = 0.0
    private val dragRefPt = Vec2()

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

                val toGround = isDragTerrainPosition || renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
                val p = wwd.viewportCoordinates(event.x, event.y)
                // Cursor delta since the previous drag event. We translate the reference's
                // screen position by this same delta so the picked shape moves rigidly with
                // the cursor (no jump to cursor position).
                val deltaX = p.x - lastDragX
                val deltaY = p.y - lastDragY
                lastDragX = p.x
                lastDragY = p.y
                val refMappedToScreen = wwd.engine.geographicToScreenPoint(
                    fromPosition.latitude, fromPosition.longitude, 0.0, dragRefPt
                )
                val moved = refMappedToScreen && if (toGround) {
                    wwd.engine.pickTerrainPosition(dragRefPt.x + deltaX, dragRefPt.y + deltaY, toPosition)
                } else {
                    wwd.engine.screenPointToGroundPosition(dragRefPt.x + deltaX, dragRefPt.y + deltaY, toPosition)
                }

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
        pickedRenderable = topObject as? Renderable
        pickedPosition = pickList.terrainPickedObject?.terrainPosition
            ?: (topObject as? Movable)?.referencePosition
        val r = pickedRenderable
        isDraggingArmed = r != null && callback?.canMoveRenderable(r) == true
    }
}