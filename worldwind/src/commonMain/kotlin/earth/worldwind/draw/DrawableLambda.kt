package earth.worldwind.draw

open class DrawableLambda(protected val lambda: (dc: DrawContext) -> Unit): Drawable {
    override fun recycle() { }
    /**
     * Performs the actual rendering in OpenGL.
     *
     * @param dc The current draw context.
     */
    override fun draw(dc: DrawContext) = lambda(dc)
}