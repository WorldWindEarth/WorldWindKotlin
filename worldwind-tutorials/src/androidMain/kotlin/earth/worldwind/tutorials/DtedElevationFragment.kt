package earth.worldwind.tutorials

class DtedElevationFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a local-DTED elevation coverage. The tutorial
     * synthesises a small DTED library in the app's temp dir on start; point
     * `DTED_DIR` (system property) at a real library to use that instead.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { DtedElevationTutorial(it.engine).start() }
}
