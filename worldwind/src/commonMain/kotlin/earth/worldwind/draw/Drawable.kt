package earth.worldwind.draw

interface Drawable {
    fun recycle()
    fun draw(dc: DrawContext)
}