package earth.worldwind.gesture

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
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
                val renderable = pickedRenderable ?: return false
                val fromPosition = if (renderable is Movable) renderable.referencePosition else pickedPosition ?: return false
                val toPosition = Position()

                val toGround = isDragTerrainPosition || renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
                val moved = if (toGround) {
                    wwd.engine.pickTerrainPosition(event.x.toDouble(), event.y.toDouble(), toPosition)
                } else {
                    wwd.engine.screenPointToGroundPosition(event.x.toDouble(), event.y.toDouble(), toPosition)
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
        val pickList = runBlocking { wwd.pickAsync(event.x.toFloat(), event.y.toFloat(), 4f, 4f).await() }
        val topObject = pickList.topPickedObject?.userObject
        pickedRenderable = topObject as? Renderable
        pickedPosition = pickList.terrainPickedObject?.terrainPosition
            ?: (topObject as? Movable)?.referencePosition
        val r = pickedRenderable
        isDraggingArmed = r != null && callback?.canMoveRenderable(r) == true
    }
}