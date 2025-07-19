package earth.worldwind.tutorials

import earth.worldwind.WorldWindow
import earth.worldwind.ogc.GpkgLayerFactory
import earth.worldwind.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeoPackageFragment: BasicGlobeFragment() {
    /**
     * Creates a new WorldWindow (GLSurfaceView) object with a GeoPackage Layer
     *
     * @return The WorldWindow object containing the globe.
     */
    override fun createWorldWindow(): WorldWindow {
        // Let the super class (BasicGlobeFragment) do the creation
        val wwd = super.createWorldWindow()

        wwd.mainScope.launch(Dispatchers.IO) {
            try {
                // Unpack the tutorial GeoPackage asset to the Android application cache. GeoPackage relies on the Android
                // SQLite library which operates only on files in the local Android filesystem.
                val geoPackageFile = TutorialUtil.unpackAsset(requireContext(), "geopackage_tutorial.gpkg")

                // Create an OGC GeoPackage layer to display a high resolution monochromatic image of Naval Air Station
                // Oceana in Virginia Beach, VA.
                val layer = GpkgLayerFactory.createLayer(geoPackageFile.path)

                // Add the finished GeoPackage layer to the WorldWindow.
                wwd.engine.layers.addLayer(layer)
                wwd.requestRedraw()
                Logger.log(Logger.INFO, "GeoPackage layer creation succeeded")
            } catch (e: Exception) {
                Logger.log(Logger.ERROR, "GeoPackage layer creation failed", e)
            }
        }

        // Place the viewer directly over the GeoPackage image.
        wwd.engine.camera.position.setDegrees(
            36.8139677556754, -76.03260320181614, 10e3
        )
        return wwd
    }
}