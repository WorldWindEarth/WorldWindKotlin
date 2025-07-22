package earth.worldwind.examples

import android.os.Bundle
import earth.worldwind.formats.geojson.GeoJsonLayerFactory
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class GeoJsonDemoActivity : GeneralGlobeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle = "About the " + resources.getText(R.string.title_geo_json_demo)
        aboutBoxText = """
   GeoJson demo screen.
   
   Different placemarks and shapes are loaded from a GeoJson file.
    """.trimIndent()

        wwd.mainScope.launch(Dispatchers.IO) {
            try {
                val layer = GeoJsonLayerFactory.createLayer(
                    text = assets.open("GEO_JSON_Samples.json").bufferedReader().readText(),
                    labelVisibilityThreshold = 18000.0, // Set a visibility threshold for labels
                )
                wwd.engine.layers.addLayer(layer)
                wwd.requestRedraw()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val lookAt = LookAt(
            position = Position.fromDegrees(50.430, 30.520, 4000.0),
            altitudeMode = AltitudeMode.RELATIVE_TO_GROUND,
            range = 4000.0,
            heading = 0.0.degrees,
            tilt = 45.0.degrees,
            roll = 0.0.degrees
        )
        wwd.engine.cameraFromLookAt(lookAt)
    }
}