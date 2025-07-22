package earth.worldwind.layer

import earth.worldwind.render.RenderContext

interface Layer {
    var displayName: String?
    var isEnabled: Boolean
    var isPickEnabled: Boolean
    var isDynamic: Boolean
    var opacity: Float
    var minActiveAltitude: Double
    var maxActiveAltitude: Double
    fun getUserProperty(key: Any): Any?
    fun putUserProperty(key: Any, value: Any): Any?
    fun removeUserProperty(key: Any): Any?
    fun hasUserProperty(key: Any): Boolean
    fun render(rc: RenderContext)
    fun isWithinActiveAltitudes(rc: RenderContext): Boolean
}