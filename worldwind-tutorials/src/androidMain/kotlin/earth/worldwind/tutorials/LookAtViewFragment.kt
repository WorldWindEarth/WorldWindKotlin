package earth.worldwind.tutorials

class LookAtViewFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with its camera configured to look at a given location from a given position.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { LookAtViewTutorial(it.engine).start() }
}