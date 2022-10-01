package earth.worldwind.tutorials

class PlacemarksFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow with a RenderableLayer populated with four Placemarks.
     */
    override fun createWorldWindow() = super.createWorldWindow().also { PlacemarksTutorial(it.engine).start() }
}