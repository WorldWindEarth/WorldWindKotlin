package earth.worldwind.layer

import earth.worldwind.render.RenderContext
import kotlin.jvm.JvmOverloads

abstract class AbstractLayer @JvmOverloads constructor(override var displayName: String? = null): Layer {
    override var isEnabled = true
    override var isPickEnabled = true
    override var isDynamic = false
    override var opacity = 1f
    override var minActiveAltitude = Double.NEGATIVE_INFINITY
    override var maxActiveAltitude = Double.POSITIVE_INFINITY
    private var userProperties: MutableMap<Any, Any>? = null

    override fun getUserProperty(key: Any) = userProperties?.get(key)

    override fun putUserProperty(key: Any, value: Any): Any? {
        val userProperties = userProperties ?: mutableMapOf<Any, Any>().also { userProperties = it }
        return userProperties.put(key, value)
    }

    override fun removeUserProperty(key: Any) = userProperties?.remove(key)

    override fun hasUserProperty(key: Any) = userProperties?.containsKey(key) == true

    override fun render(rc: RenderContext) {
        if (isEnabled && (isPickEnabled || !rc.isPickMode) && isWithinActiveAltitudes(rc)) doRender(rc)
    }

    override fun isWithinActiveAltitudes(rc: RenderContext) = rc.camera.position.altitude in minActiveAltitude..maxActiveAltitude

    protected abstract fun doRender(rc: RenderContext)
}