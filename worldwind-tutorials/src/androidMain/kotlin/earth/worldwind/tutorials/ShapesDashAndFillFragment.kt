package earth.worldwind.tutorials

class ShapesDashAndFillFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Path and Polygon shapes with dashed lines and
     * repeating fill.
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { ShapesDashAndFillTutorial(it.engine).start() }
}