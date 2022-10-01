package earth.worldwind.tutorials

import earth.worldwind.WorldWindow

open class LabelsFragment : BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a set of label shapes
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        // Add Labels examples Layer
        wwd.engine.layers.addLayer(buildLabelsLayer())

        // Place the viewer directly over the tutorial labels.
        wwd.engine.camera.position.setDegrees(38.89, -77.023611, 10e3)
        return wwd
    }
}