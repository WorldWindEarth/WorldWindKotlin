package earth.worldwind.perftest

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import earth.worldwind.WorldWindowController
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.interpolateAngle180
import earth.worldwind.geom.Angle.Companion.interpolateAngle360
import earth.worldwind.geom.Camera
import earth.worldwind.geom.Location
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.Layer
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.shape.PathType
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes.Companion.createWithImage
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

open class BasicPerformanceBenchmarkActivity: GeneralGlobeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Suppress the WorldWindow's built-in navigation behavior.
        wwd.controller = object : WorldWindowController {}
    }

    override fun onStart() {
        super.onStart()

        lifecycleScope.launch {
            test_default()

            // After a 1-second delay, log the frame statistics associated with this test.
            delay(1000)
            log(Logger.INFO, wwd.engine.frameMetrics.toString())
            finish()
        }
    }

    private suspend fun test_default() {
        // Add a layer containing a large number of placemarks.
        wwd.engine.layers.addLayer(createPlacemarksLayer())

        // Create location objects for the places used in this test.
        val arc = fromDegrees(37.415229, -122.06265)
        val gsfc = fromDegrees(38.996944, -76.848333)
        val esrin = fromDegrees(41.826947, 12.674122)

        // After a 1-second initial delay, clear the frame statistics associated with this test.
        delay(1000)
        wwd.engine.frameMetrics?.reset()

        // After a 1/2-second delay, fly to NASA Ames Research Center over 100 frames.
        endCamera.set(
            arc.latitude, arc.longitude, 600.0, AltitudeMode.ABSOLUTE,
            ZERO, ZERO, ZERO
        )
        animateCamera(100)

        // After a 1/2-second delay, rotate the camera to look at NASA Goddard Space Flight Center over 50 frames.
        var azimuth = arc.greatCircleAzimuth(gsfc)
        endCamera.set(
            arc.latitude, arc.longitude, 600.0, AltitudeMode.ABSOLUTE,
            azimuth, 70.0.degrees, ZERO
        )
        delay(500)
        animateCamera(100)

        // After a 1/2-second delay, fly the camera to NASA Goddard Space Flight Center over 200 frames.
        var midLoc = arc.interpolateAlongPath(gsfc, PathType.GREAT_CIRCLE, 0.5, Location())
        azimuth = midLoc.greatCircleAzimuth(gsfc)
        delay(500)
        endCamera.set(
            midLoc.latitude, midLoc.longitude, 100e3, AltitudeMode.ABSOLUTE,
            azimuth, ZERO, ZERO
        )
        animateCamera(200)
        endCamera.set(
            gsfc.latitude, gsfc.longitude, 600.0, AltitudeMode.ABSOLUTE,
            azimuth, 70.0.degrees, ZERO
        )
        animateCamera(200)

        // After a 1/2-second delay, rotate the camera to look at ESA Centre for Earth Observation over 50 frames.
        azimuth = gsfc.greatCircleAzimuth(esrin)
        endCamera.set(
            gsfc.latitude, gsfc.longitude, 600.0, AltitudeMode.ABSOLUTE,
            azimuth, POS90, ZERO
        )
        delay(500)
        animateCamera(100)

        // After a 1/2-second delay, fly the camera to ESA Centre for Earth Observation over 200 frames.
        midLoc = gsfc.interpolateAlongPath(esrin, PathType.GREAT_CIRCLE, 0.5, Location())
        delay(500)
        endCamera.set(
            midLoc.latitude, midLoc.longitude, 100e3, AltitudeMode.ABSOLUTE,
            azimuth, 60.0.degrees, ZERO
        )
        animateCamera(200)
        endCamera.set(
            esrin.latitude, esrin.longitude, 600.0, AltitudeMode.ABSOLUTE,
            azimuth, 30.0.degrees, ZERO
        )
        animateCamera(200)

        // After a 1/2-second delay, back the camera out to look at ESA Centre for Earth Observation over 100 frames.
        endCamera.set(
            esrin.latitude, esrin.longitude, 20e3, AltitudeMode.ABSOLUTE,
            ZERO, ZERO, ZERO
        )
        delay(500)
        animateCamera(200)
    }
    protected open fun createPlacemarksLayer(): Layer {
        val layer = RenderableLayer("Placemarks")
        val attrs = arrayOf(
            createWithImage(fromResource(R.drawable.aircraft_fixwing)),
            createWithImage(fromResource(R.drawable.airplane)),
            createWithImage(fromResource(R.drawable.airport)),
            createWithImage(fromResource(R.drawable.airport_terminal))
        )
        try {
            var headers = true
            var lat = 0
            var lon = 0
            var na3 = 0
            var use = 0
            var attrIndex = 0
            resources.openRawResource(R.raw.world_apts).bufferedReader().forEachLine { line ->
                val fields = line.split(",")
                if (headers) {
                    headers = false
                    // The first line is the CSV header: LAT,LON,ALT,NAM,IKO,NA3,USE,USEdesc
                    lat = fields.indexOf("LAT")
                    lon = fields.indexOf("LON")
                    na3 = fields.indexOf("NA3")
                    use = fields.indexOf("USE")
                } else if (fields[na3].startsWith("US") && fields[use] == "49") {
                    // display USA Civilian/Public airports
                    val pos = fromDegrees(fields[lat].toDouble(), fields[lon].toDouble(), 0.0)
                    layer.addRenderable(
                        Placemark(pos, attrs[attrIndex++ % attrs.size]).apply {
                            altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                        }
                    )
                }
            }
        } catch (e: IOException) {
            log(Logger.ERROR, "Exception attempting to read Airports database")
        }
        return layer
    }

    private val beginCamera = Camera()
    private val endCamera = Camera()
    private val curCamera = Camera()

    protected val FRAME_INTERVAL = 33L // 33 millis; 30 frames per second

    private suspend fun animateCamera(steps: Int) {
        beginCamera.copy(wwd.engine.camera)
        for (i in 0 until steps) {
            val amount = i / (steps - 1).toDouble()
            beginCamera.position.interpolateAlongPath(
                endCamera.position, PathType.GREAT_CIRCLE, amount, curCamera.position
            )
            curCamera.heading = interpolateAngle360(amount, beginCamera.heading, endCamera.heading)
            curCamera.tilt = interpolateAngle180(amount, beginCamera.tilt, endCamera.tilt)
            curCamera.roll = interpolateAngle180(amount, beginCamera.roll, endCamera.roll)
            wwd.engine.camera.copy(curCamera)
            wwd.requestRedraw()
            delay(FRAME_INTERVAL)
        }
    }
}