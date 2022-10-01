package earth.worldwind.render

interface Renderable {
    var displayName: String?
    var isEnabled: Boolean
    var pickDelegate: Any?
    fun getUserProperty(key: Any): Any?
    fun putUserProperty(key: Any, value: Any): Any?
    fun removeUserProperty(key: Any): Any?
    fun hasUserProperty(key: Any): Boolean
    fun render(rc: RenderContext)
}