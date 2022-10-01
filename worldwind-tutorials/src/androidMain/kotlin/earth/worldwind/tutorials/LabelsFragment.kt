package earth.worldwind.tutorials

class LabelsFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of label shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { LabelsTutorial(it.engine).start() }
}