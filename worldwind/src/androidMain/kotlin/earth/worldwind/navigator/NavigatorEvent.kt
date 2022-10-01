package earth.worldwind.navigator

import android.view.InputEvent
import earth.worldwind.geom.Camera
import earth.worldwind.util.BasicPool

open class NavigatorEvent protected constructor() {
    var camera: Camera? = null
        protected set
    var action = NavigatorAction.MOVED
        protected set
    var lastInputEvent: InputEvent? = null
        protected set

    companion object {
        private val pool = BasicPool<NavigatorEvent>()

        @JvmStatic
        fun obtain(camera: Camera, action: NavigatorAction, lastInputEvent: InputEvent?): NavigatorEvent {
            val instance = pool.acquire() ?: NavigatorEvent()
            instance.camera = camera
            instance.action = action
            instance.lastInputEvent = lastInputEvent
            return instance
        }
    }

    /**
     * Recycle the event, making it available for re-use.
     */
    open fun recycle() {
        camera = null
        action = NavigatorAction.MOVED
        lastInputEvent = null
        pool.release(this)
    }
}