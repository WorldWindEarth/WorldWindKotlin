package earth.worldwind.gesture

import earth.worldwind.WorldWindow
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.geom.SphericalRotation
import earth.worldwind.geom.Vec2
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_CANCEL
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_DOWN
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_MOVE
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_POINTER_DOWN
import earth.worldwind.gesture.TouchEvent.Companion.ACTION_UP
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Movable
import kotlin.math.abs
import platform.QuartzCore.CACurrentMediaTime

/**
 * iOS port of the JVM/Android [SelectDragDetector]. Mirrors the JVM pattern: pick is driven
 * synchronously on DOWN (via [WorldWindow.pick] which drains the pick frame inline), so by
 * the time we return to the dispatcher we already know whether a draggable renderable is
 * under the finger. Subsequent MOVE events drive the drag directly when armed; otherwise
 * they fall through to [BasicWorldWindowController] for camera panning.
 */
open class SelectDragDetector(protected val wwd: WorldWindow) {
    var callback: SelectDragCallback? = null
    var isEnabled = true
    var tapTimeoutMs: Long = 250
    var doubleTapTimeoutMs: Long = 350
    var tapSlopPx: Double = 10.0

    protected var pickedRenderable: Renderable? = null
    protected var pickedPosition: Position? = null
    protected var isDraggingArmed = false
    protected var isDragging = false

    private var downX = 0.0
    private var downY = 0.0
    private var lastDragX = 0.0
    private var lastDragY = 0.0
    private var downTimeMs = 0L
    private var lastTapTimeMs = 0L
    private val dragRefPt = Vec2()
    // Press-time rigid rotation taking the cursor's terrain pick to the renderable's reference.
    // Captured only for extended shapes (Polygon, Path, Mesh) that need the grabbed point pinned
    // to the cursor; null for point shapes (Placemark, Label, sightlines) which snap their anchor
    // directly to the cursor each event.
    private var grabRotation: SphericalRotation? = null

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
        val renderable = pickedRenderable ?: return false
        val fromPosition =
            if (renderable is Movable) renderable.referencePosition
            else pickedPosition ?: return false
        val toPosition = Position()
        val toGround = renderable !is Movable || renderable.altitudeMode == AltitudeMode.CLAMP_TO_GROUND
        val moved = if (toGround) {
            // Snap-to-cursor for point shapes (grabRotation == null), grab-anchor for
            // extended shapes (grabRotation rotates the fresh terrain pick into the
            // reference's frame, preserving the press-time offset).
            wwd.engine.pickTerrainPosition(x, y, toPosition).also {
                if (it) grabRotation?.apply(toPosition)
            }
        } else {
            // Screen-delta: project the reference at sea level, shift by the cursor's
            // incremental delta, resolve back at sea level.
            wwd.engine.geographicToScreenPoint(
                fromPosition.latitude, fromPosition.longitude, 0.0, dragRefPt
            ) && wwd.engine.screenPointToGroundPosition(
                dragRefPt.x + (x - lastDragX), dragRefPt.y + (y - lastDragY), toPosition
            )
        }
        lastDragX = x; lastDragY = y
        return if (moved) {
            toPosition.altitude = fromPosition.altitude
            cb.onRenderableMoved(renderable, fromPosition, toPosition)
            if (renderable is Movable) renderable.moveTo(wwd.engine.globe, toPosition)
            isDragging = true
            wwd.requestRedraw()
            true
        } else false
    }

    private fun finishDragging() {
        val cb = callback; val renderable = pickedRenderable; val position = pickedPosition
        if (cb != null && renderable != null && position != null) {
            cb.onRenderableMovingFinished(renderable, position)
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
        val renderable = pickedRenderable
        when {
            position == null -> if (isDouble) cb.onNothingContext() else cb.onNothingPicked()
            renderable != null && cb.canPickRenderable(renderable) ->
                if (isDouble) cb.onRenderableDoubleTap(renderable, position)
                else cb.onRenderablePicked(renderable, position)
            else -> if (isDouble) cb.onTerrainDoubleTap(position) else cb.onTerrainPicked(position)
        }
        wwd.requestRedraw()
    }

    protected open fun pick(x: Double, y: Double) {
        val pickList = wwd.pick(x.toFloat(), y.toFloat(), 8f, 8f)
        val topPicked = pickList.topPickedObject
        val userObject = topPicked?.userObject
        val renderable = userObject as? Renderable
        val movable = userObject as? Movable
        val terrainPos = pickList.terrainPickedObject?.terrainPosition
        pickedRenderable = renderable
        // Prefer the depth-tested shape-surface point over the terrain behind the shape.
        pickedPosition = topPicked?.geographicPoint(wwd.engine.globe) ?: terrainPos
            ?: movable?.referencePosition
        isDraggingArmed = renderable != null && callback?.canMoveRenderable(renderable) == true
        grabRotation = if (movable != null && !movable.isPointShape && terrainPos != null) {
            SphericalRotation(terrainPos, movable.referencePosition)
        } else null
    }
}
