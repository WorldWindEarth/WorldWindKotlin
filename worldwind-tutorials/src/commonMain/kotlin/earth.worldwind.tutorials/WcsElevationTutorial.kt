package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.ogc.Wcs100ElevationCoverage
import kotlin.math.atan

class WcsElevationTutorial(private val engine: WorldWind) : AbstractTutorial() {
    // Create an elevation coverage from a version 1.0.0 WCS
    private val wcsElevationCoverage = Wcs100ElevationCoverage(
        // Specify the bounding sector - provided by the WCS
        sector = Sector.fromDegrees(25.0, -125.0, 25.0, 60.0),
        // Specify the number of levels to match data resolution
        numLevels = 12,
        // Specify the version 1.0.0 WCS address
        serviceAddress = "https://elevation.nationalmap.gov/arcgis/services/3DEPElevation/ImageServer/WCSServer",
        // Specify the coverage name
        coverage = "DEP3Elevation",
        // Specify the image format
        imageFormat = "geotiff"
    )

    override fun start() {
        super.start()
        engine.globe.elevationModel.apply {
            forEach { coverage -> coverage.isEnabled = false }
            addCoverage(wcsElevationCoverage)
        }
        positionView()
    }

    override fun stop() {
        super.stop()
        engine.globe.elevationModel.apply {
            removeCoverage(wcsElevationCoverage)
            forEach { coverage -> coverage.isEnabled = true }
        }
    }

    private fun positionView() {
        val mtRainier = Position.fromDegrees(46.852886, -121.760374, 4392.0)
        val eye = Position.fromDegrees(46.912, -121.527, 2000.0)

        // Compute heading and distance from peak to eye
        val heading = eye.greatCircleAzimuth(mtRainier)
        val distanceRadians = mtRainier.greatCircleDistance(eye)
        val distance = distanceRadians * engine.globe.getRadiusAt(mtRainier.latitude, mtRainier.longitude)

        // Compute camera settings
        val tilt = atan(distance / eye.altitude).radians

        // Apply the new view
        engine.camera.set(
            eye.latitude, eye.longitude, eye.altitude, AltitudeMode.ABSOLUTE, heading, tilt, roll = Angle.ZERO
        )
    }

}