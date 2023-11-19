package earth.worldwind.tutorials

class GKGraticuleFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a Gauss-Kruger Graticule layer.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { GKGraticuleTutorial(it.engine).start() }
}