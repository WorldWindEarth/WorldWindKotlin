package earth.worldwind.navigator

import earth.worldwind.WorldWindow
import earth.worldwind.geom.Matrix4
import earth.worldwind.gesture.TouchEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * iOS port of NavigatorEventSupport. The "stopped" delay is scheduled on the engine's
 * main coroutine scope (same as Android — driven by `WorldWindow.engine.renderResourceCache.mainScope`).
 * Differences from Android: events are our [TouchEvent], not `MotionEvent` (no recycle pool needed
 * because TouchEvent allocations are cheap and short-lived on iOS).
 */
open class NavigatorEventSupport(protected val wwd: WorldWindow) {
    var stoppedEventDelay = 250.milliseconds
    protected val listeners = mutableListOf<NavigatorListener>()
    protected var lastModelview: Matrix4? = null
    protected var lastProjection: Matrix4? = null
    protected var lastElevationTimestamp = 0L
    protected var lastTouchEvent: TouchEvent? = null
    protected var stopTouchEvent: TouchEvent? = null
    protected var stopJob: Job? = null

    open fun reset() {
        lastModelview = null
        lastProjection = null
        lastElevationTimestamp = 0L
        lastTouchEvent = null
        stopTouchEvent = null
        stopJob?.cancel()
        stopJob = null
    }

    fun addNavigatorListener(listener: NavigatorListener) { listeners.add(listener) }
    fun removeNavigatorListener(listener: NavigatorListener) { listeners.remove(listener) }

    open fun onTouchEvent(event: TouchEvent) {
        if (listeners.isEmpty()) return
        lastModelview ?: return
        lastTouchEvent = event
    }

    open fun onFrameRendered(modelview: Matrix4, projection: Matrix4, elevationTimestamp: Long) {
        if (listeners.isEmpty()) return
        val lastModelview = this.lastModelview
        val lastProjection = this.lastProjection
        if (lastModelview == null || lastProjection == null) {
            this.lastModelview = Matrix4(modelview)
            this.lastProjection = Matrix4(projection)
            stopJob = wwd.engine.renderResourceCache.mainScope.launch { onNavigatorStopped() }
        } else if (lastModelview != modelview || lastProjection != projection || lastElevationTimestamp != elevationTimestamp) {
            lastModelview.copy(modelview)
            lastProjection.copy(projection)
            this.lastElevationTimestamp = elevationTimestamp
            onNavigatorMoved()
            stopJob?.cancel()
            stopJob = wwd.engine.renderResourceCache.mainScope.launch {
                delay(stoppedEventDelay)
                onNavigatorStopped()
            }
        }
    }

    protected open fun onNavigatorMoved() {
        notifyListeners(NavigatorAction.MOVED, lastTouchEvent)
        if (lastTouchEvent != null) {
            stopTouchEvent = lastTouchEvent
            lastTouchEvent = null
        }
    }

    protected open fun onNavigatorStopped() {
        notifyListeners(NavigatorAction.STOPPED, stopTouchEvent)
        stopTouchEvent = null
    }

    protected open fun notifyListeners(action: NavigatorAction, inputEvent: TouchEvent?) {
        val event = NavigatorEvent.obtain(wwd.engine.camera, action, inputEvent)
        for (listener in listeners) listener.onNavigatorEvent(wwd, event)
        event.recycle()
    }
}
