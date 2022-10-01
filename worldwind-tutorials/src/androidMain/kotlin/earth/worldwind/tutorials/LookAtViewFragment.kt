package earth.worldwind.tutorials

open class LookAtViewFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with its camera configured to look at a given location from a given position.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { setLookAtView(wwd.engine) }
}