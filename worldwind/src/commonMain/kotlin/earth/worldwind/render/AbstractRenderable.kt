package earth.worldwind.render

import kotlin.jvm.JvmOverloads

abstract class AbstractRenderable @JvmOverloads constructor(override var displayName: String? = null): Renderable {
    override var isEnabled = true
    override var isPickEnabled = true
    override var pickDelegate: Any? = null
    private var userProperties: MutableMap<Any, Any>? = null

    @Suppress("UNCHECKED_CAST")
    override fun <T> getUserProperty(key: Any) = userProperties?.get(key) as? T

    override fun putUserProperty(key: Any, value: Any): Any? {
        val userProperties = userProperties ?: mutableMapOf<Any, Any>().also { userProperties = it }
        return userProperties.put(key, value)
    }

    override fun removeUserProperty(key: Any) = userProperties?.remove(key)

    override fun hasUserProperty(key: Any) = userProperties?.containsKey(key) == true

    override fun render(rc: RenderContext) { if (isEnabled && (isPickEnabled || !rc.isPickMode)) doRender(rc) }

    protected abstract fun doRender(rc: RenderContext)
}