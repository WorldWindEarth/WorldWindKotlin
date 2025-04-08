package earth.worldwind.render

interface BatchRenderer {

    /* return true if shape was batched */
    fun addOrUpdateRenderable(renderable: Renderable) : Boolean

    fun removeRenderable(renderable : Renderable)

    fun render(rc : RenderContext)

    fun clear()
}