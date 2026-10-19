package earth.worldwind.tutorials

class TerrainRayFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) that traces a camera frame's corner rays
     * against terrain at a resolution the caller fixes.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { TerrainRayTutorial(it.engine).start() }
}
