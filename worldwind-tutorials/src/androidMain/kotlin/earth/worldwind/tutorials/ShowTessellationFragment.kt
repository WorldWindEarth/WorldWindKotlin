package earth.worldwind.tutorials

class ShowTessellationFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a tessellation layer.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { ShowTessellationTutorial(it.engine).start() }
}