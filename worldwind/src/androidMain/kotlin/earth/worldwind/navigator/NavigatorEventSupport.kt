package earth.worldwind.navigator

import android.view.InputEvent
import android.view.MotionEvent
import earth.worldwind.WorldWindow
import earth.worldwind.geom.Matrix4
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

open class NavigatorEventSupport(protected var wwd: WorldWindow) {
    var stoppedEventDelay = 250.milliseconds
    protected val listeners = mutableListOf<NavigatorListener>()
    protected var lastModelview: Matrix4? = null
    protected var lastProjection: Matrix4? = null
    protected var lastElevationTimestamp = 0L
    protected var lastTouchEvent: MotionEvent? = null
    protected var stopTouchEvent: MotionEvent? = null
    protected var stopJob: Job? = null

    open fun reset() {
        lastModelview = null
        lastProjection = null
        lastElevationTimestamp = 0L
        lastTouchEvent?.recycle()
        lastTouchEvent = null
        stopTouchEvent?.recycle()
        stopTouchEvent = null
        stopJob?.cancel()
        stopJob = null
    }

    fun addNavigatorListener(listener: NavigatorListener) { listeners.add(listener) }

    fun removeNavigatorListener(listener: NavigatorListener) { listeners.remove(listener) }

    open fun onTouchEvent(event: MotionEvent) {
        if (listeners.isEmpty()) return  // no listeners to notify; ignore the event
        lastModelview ?: return  // no frame rendered yet; ignore the event
        lastTouchEvent?.recycle()
        lastTouchEvent = MotionEvent.obtain(event)
    }

    open fun onFrameRendered(modelview: Matrix4, projection: Matrix4, elevationTimestamp: Long) {
        if (listeners.isEmpty()) return  // no listeners to notify; ignore the event
        val lastModelview = this.lastModelview
        val lastProjection = this.lastProjection
        if (lastModelview == null || lastProjection == null) { // this is the first frame; copy the frame's modelview
            this.lastModelview = Matrix4(modelview)
            this.lastProjection = Matrix4(projection)
            // Notify listeners with stopped event on first frame
            stopJob = wwd.mainScope.launch { onNavigatorStopped() }
        } else if (lastModelview != modelview || lastProjection != projection || lastElevationTimestamp != elevationTimestamp) {
            // the frame's modelview or elevation timestamp has changed
            lastModelview.copy(modelview)
            lastProjection.copy(projection)
            lastElevationTimestamp = elevationTimestamp
            // Notify the listeners of a navigator moved event.
            onNavigatorMoved()
            // Schedule a navigator stopped event after a specified delay in milliseconds.
            stopJob?.cancel()
            stopJob = wwd.mainScope.launch {
                delay(stoppedEventDelay)
                onNavigatorStopped()
            }
        }
    }

    protected open fun onNavigatorMoved() {
        notifyListeners(NavigatorAction.MOVED, lastTouchEvent)
        if (lastTouchEvent != null) {
            stopTouchEvent?.recycle()
            stopTouchEvent = lastTouchEvent
            lastTouchEvent = null
        }
    }

    protected open fun onNavigatorStopped() {
        notifyListeners(NavigatorAction.STOPPED, stopTouchEvent)
        stopTouchEvent?.recycle()
        stopTouchEvent = null
    }

    protected open fun notifyListeners(action: NavigatorAction, inputEvent: InputEvent?) {
        val event = NavigatorEvent.obtain(wwd.engine.camera, action, inputEvent)
        for (listener in listeners) listener.onNavigatorEvent(wwd, event)
        event.recycle()
    }
}