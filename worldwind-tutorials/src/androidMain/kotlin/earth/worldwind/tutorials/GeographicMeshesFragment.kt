package earth.worldwind.tutorials

class GeographicMeshesFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Geographic Mesh shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { GeographicMeshesTutorial(it.engine).start() }
}