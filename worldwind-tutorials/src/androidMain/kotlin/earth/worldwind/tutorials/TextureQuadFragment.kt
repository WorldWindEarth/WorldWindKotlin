package earth.worldwind.tutorials

class TextureQuadFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with an additional RenderableLayer containing one TextureQuad.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { TextureQuadTutorial(it.engine).start() }
}