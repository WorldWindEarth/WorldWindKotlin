package earth.worldwind.tutorials

class NitfImageryFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow and attaches the cross-platform
     * [NitfImageryTutorial] — synthesises a small RGB NITF in memory, decodes
     * it via the shared NITF parser, and drapes the result as a [SurfaceImage]
     * over Mt Etna.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { NitfImageryTutorial(it.engine).start() }
}
