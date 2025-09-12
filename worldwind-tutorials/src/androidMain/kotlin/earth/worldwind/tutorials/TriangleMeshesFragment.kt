package earth.worldwind.tutorials

class TriangleMeshesFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of Tringle Mesh shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { TriangleMeshesTutorial(it.engine).start() }
}