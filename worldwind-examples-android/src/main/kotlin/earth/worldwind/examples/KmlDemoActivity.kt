package earth.worldwind.examples

import android.os.Bundle
import earth.worldwind.formats.kml.KmlLayerFactory
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class KmlDemoActivity : GeneralGlobeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_kml_demo)
        aboutBoxText = """
   KML interaction demo screen.
   
   Different placemarks and shapes are loaded from a KML file.
    """.trimIndent()

        wwd.mainScope.launch(Dispatchers.IO) {
            try {
                val reader = assets.open("KML_Samples.kml").bufferedReader()
                val layers = KmlLayerFactory.createLayers(reader)
                wwd.engine.layers.addAllLayers(layers)
                wwd.requestRedraw()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // And finally, for this demo, position the viewer to look at the placemarks
        val lookAt = LookAt(
            position = Position.fromDegrees(37.42228990140251, -122.0822035425683, 0.0),
            altitudeMode = AltitudeMode.ABSOLUTE,
            range = 2e2,
            heading = 0.0.degrees,
            tilt = 45.0.degrees,
            roll = 0.0.degrees
        )
        wwd.engine.cameraFromLookAt(lookAt)
    }
}