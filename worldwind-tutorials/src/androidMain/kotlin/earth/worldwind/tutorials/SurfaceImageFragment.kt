package earth.worldwind.tutorials

class SurfaceImageFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with an additional RenderableLayer containing two SurfaceImages.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { SurfaceImageTutorial(it.engine).start() }
}