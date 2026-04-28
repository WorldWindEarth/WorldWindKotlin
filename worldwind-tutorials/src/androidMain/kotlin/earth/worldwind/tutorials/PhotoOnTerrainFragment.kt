package earth.worldwind.tutorials

class PhotoOnTerrainFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with an additional RenderableLayer containing one
     * ProjectedMediaSurface that drapes a static drone photo onto the terrain.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { PhotoOnTerrainTutorial(it.engine).start() }
}
