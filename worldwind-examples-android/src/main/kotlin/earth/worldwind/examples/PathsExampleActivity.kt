package earth.worldwind.examples

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

open class PathsExampleActivity: GeneralGlobeActivity() {
    protected val airportTable = mutableListOf<Airport>()
    protected val airportIkoIndex = mutableMapOf<String?, Airport>()
    protected val flightPathLayer = RenderableLayer()
    protected var animationAmount = 0.0
    protected var animationIncrement = 0.01 // increment 1% each iteration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aboutBoxTitle= "About the " + resources.getText(R.string.title_paths_example)
        aboutBoxText = "Demonstrates Paths used to animate aircraft great-circle routes from Seattle to other US airports."
        readAirportTable()
        populateFlightPaths()
        wwd.engine.layers.addLayer(flightPathLayer)

        // Set the camera to look at the area where the symbols will be displayed.
        val lookAt = LookAt(
            position = Position(47.448982.degrees, (-122.309311).degrees, 0.0), altitudeMode = AltitudeMode.ABSOLUTE,
            range = wwd.engine.distanceToViewGlobeExtents / 4, heading = 0.0.degrees, tilt = 60.0.degrees, roll = 0.0.degrees
        )
        wwd.engine.cameraFromLookAt(lookAt)
    }

    override fun onResume() {
        super.onResume()
        // Start or resume the animation.
        lifecycleScope.launch { doAnimation() }
    }

    private fun doAnimation() {
        if (animationAmount < 1) {
            // Increment the animation amount.
            animationAmount += animationIncrement
            for (idx in 0 until flightPathLayer.count) {
                // Identify the departure airport and destination airport associated with each flight path.
                val path = flightPathLayer.getRenderable(idx) as Path
                val dept = path.getUserProperty<Airport>("dept")
                val dest = path.getUserProperty<Airport>("dest")
                if (dept == null || dest == null) continue

                // Compute the location on the great circle path between the departure and the destination that
                // corresponds to the animation amount.
                val nextPos = dept.pos.interpolateAlongPath(dest.pos, PathType.GREAT_CIRCLE, animationAmount, Position())

                // Compute the altitude on the flight path that corresponds to the animation amount. We mock altitude
                // using an inverse parabolic function scaled to reach a max altitude of 10% of the flight distance.
                val dist = dept.pos.greatCircleDistance(dest.pos) * wwd.engine.globe.equatorialRadius
                val altCurve = (1 - animationAmount) * animationAmount * 4
                nextPos.altitude = altCurve * dist * 0.1

                // Append the location and altitude to the flight path's list of positions.
                val positions = path.positions.toMutableList()
                positions.add(nextPos)
                path.positions = positions
            }

            // Redraw the WorldWindow to display the changes.
            wwd.requestRedraw()

            // Continue the animation after a delay.
            lifecycleScope.launch {
                delay(1000L)
                doAnimation()
            }
        }
    }

    protected open fun readAirportTable() {
        try {
            var headers = true
            var lat = 0
            var lon = 0
            var alt = 0
            // var nam = 0
            var iko = 0
            var na3 = 0
            var use = 0
            resources.openRawResource(R.raw.world_apts).bufferedReader().forEachLine { line ->
                val fields = line.split(",")
                if (headers) {
                    headers = false
                    // The first line is the CSV header:
                    //  LAT,LON,ALT,NAM,IKO,NA3,USE,USEdesc
                    lat = fields.indexOf("LAT")
                    lon = fields.indexOf("LON")
                    alt = fields.indexOf("ALT")
                    // nam = fields.indexOf("NAM")
                    iko = fields.indexOf("IKO")
                    na3 = fields.indexOf("NA3")
                    use = fields.indexOf("USE")
                } else {
                    // Read the remaining lines
                    val apt = Airport()
                    apt.pos.latitude = fields[lat].toDouble().degrees
                    apt.pos.longitude = fields[lon].toDouble().degrees
                    apt.pos.altitude = fields[alt].toDouble()
                    apt.iko = fields[iko]
                    apt.na3 = fields[na3]
                    apt.use = fields[use]
                    airportTable.add(apt)
                    airportIkoIndex[apt.iko] = apt
                }
            }
        } catch (e: IOException) {
            log(Logger.ERROR, "Exception attempting to read Airports database")
        }
    }

    protected open fun populateFlightPaths() {
        val attrs = ShapeAttributes()
        attrs.interiorColor.set(0.8f, 0.8f, 1.0f, 0.8f)
        attrs.outlineColor.set(1.0f, 1.0f, 0.0f, 1.0f)
        val dept = airportIkoIndex["KSEA"] ?: return
        for (dest in airportTable) {
            if (dest == dept) continue  // the destination and departure must be different
            if (dest.iko!!.length != 4) continue  // the destination must be a major airfield
            if (!dest.na3!!.startsWith("US")) continue  // the destination must be in the United States
            if (dest.use != "49") continue  // the destination must a Civilian/Public airport
            val positions = mutableListOf<Position>()
            positions.add(dept.pos)
            val path = Path(positions, attrs)
            path.putUserProperty("dept", dept)
            path.putUserProperty("dest", dest)
            path.maximumIntermediatePoints = 0 // Suppress intermediate points as we will calculate many of them explicitly in positions
            flightPathLayer.addRenderable(path)
        }
    }

    protected open class Airport {
        val pos = Position()
        var iko: String? = null
        var na3: String? = null
        var use: String? = null
    }
}