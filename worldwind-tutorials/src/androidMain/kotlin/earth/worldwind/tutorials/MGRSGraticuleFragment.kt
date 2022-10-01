package earth.worldwind.tutorials

class MGRSGraticuleFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a MGRS Graticule layer.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { MGRSGraticuleTutorial(it.engine).start() }
}