package earth.worldwind.navigator

import earth.worldwind.geom.Camera
import earth.worldwind.gesture.TouchEvent
import earth.worldwind.util.BasicPool

open class NavigatorEvent protected constructor() {
    var camera: Camera? = null
        protected set
    var action = NavigatorAction.MOVED
        protected set
    var lastInputEvent: TouchEvent? = null
        protected set

    companion object {
        private val pool = BasicPool<NavigatorEvent>()

        fun obtain(camera: Camera, action: NavigatorAction, lastInputEvent: TouchEvent?): NavigatorEvent {
            val instance = pool.acquire() ?: NavigatorEvent()
            instance.camera = camera
            instance.action = action
            instance.lastInputEvent = lastInputEvent
            return instance
        }
    }

    open fun recycle() {
        camera = null
        action = NavigatorAction.MOVED
        lastInputEvent = null
        pool.release(this)
    }
}
