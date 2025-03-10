package earth.worldwind.tutorials

import earth.worldwind.shape.milstd2525.MilStd2525

class MilStd2525Fragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a RenderableLayer populated with MilStd2525 Tactical Graphics.
     */
    override fun createWorldWindow() = super.createWorldWindow().also {
        // Trigger MilStd2525 symbol fonts initialization
        MilStd2525.initializeRenderer(requireContext())
        MilStd2525Tutorial(it.engine).start()
    }
}