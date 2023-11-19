package earth.worldwind.render

interface Renderable {
    var displayName: String?
    var isEnabled: Boolean
    var isPickEnabled: Boolean
    var pickDelegate: Any?
    fun <T> getUserProperty(key: Any): T?
    fun putUserProperty(key: Any, value: Any): Any?
    fun removeUserProperty(key: Any): Any?
    fun hasUserProperty(key: Any): Boolean
    fun render(rc: RenderContext)
}