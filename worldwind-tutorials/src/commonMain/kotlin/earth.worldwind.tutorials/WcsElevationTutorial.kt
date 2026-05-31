package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.ogc.Wcs100ElevationCoverage
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.math.atan
import kotlin.coroutines.cancellation.CancellationException

/**
 * @param layerLoader full coverage-creation override. JVM/Android pass a loader that
 *   constructs a [Wcs100ElevationCoverage] and calls
 *   [earth.worldwind.layer.cache.attachCache] — the per-coverage attach routes through
 *   `ContentManager.createElevationSourceFactory` so the gpkg PNG-Int16 / TIFF-Float32
 *   transcoding pipeline runs on every cache write. The default loader skips the cache
 *   and runs network-only.
 */
class WcsElevationTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope = MainScope(),
    private val layerLoader: suspend () -> TiledElevationCoverage = ::defaultNetworkOnlyLoader,
) : AbstractTutorial(engine) {

    private var coverage: TiledElevationCoverage? = null
    private var cacheJob: Job? = null

    override fun start() {
        super.start()
        cacheJob = scope.launch {
            try {
                val resolved = layerLoader()
                coverage = resolved
                engine.globe.elevationModel.apply {
                    forEach { c -> c.isEnabled = false }
                    addCoverage(resolved)
                }
                // Match WMS / WFS / MVT tutorials — async loader completion needs an
                // explicit redraw to wake the renderer once the new coverage is wired.
                WorldWind.requestRedraw()
                Logger.log(Logger.INFO, "WCS coverage loaded")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // attachCache validation on re-open (sector / matrix / data-type mismatch)
                // surfaces here. Pre-3.0 callers got the same error via the synchronous
                // setupElevationCoverageCache → silent failure on a fire-and-forget launch
                // would hide a real schema regression.
                Logger.log(Logger.ERROR, "WCS coverage failed to load", e)
            }
        }
        positionView()
    }

    override fun stop() {
        super.stop()
        cacheJob?.cancel()
        cacheJob = null
        engine.globe.elevationModel.apply {
            coverage?.let { removeCoverage(it) }
            forEach { c -> c.isEnabled = true }
        }
        coverage = null
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

    companion object {
        /** USGS National Map 3DEP elevation coverage — WCS 1.0.0. */
        const val SERVICE_ADDRESS =
            "https://elevation.nationalmap.gov/arcgis/services/3DEPElevation/ImageServer/WCSServer"
        const val COVERAGE_NAME = "DEP3Elevation"
        const val OUTPUT_FORMAT = "geotiff"
        val BOUNDING_SECTOR: Sector = Sector.fromDegrees(25.0, -125.0, 25.0, 60.0)
        val RESOLUTION: Angle = Angle.fromSeconds(1.0 / 3.0)

        /** Default loader: network-only [Wcs100ElevationCoverage] constructor (no cache wired). */
        @Suppress("RedundantSuspendModifier")
        suspend fun defaultNetworkOnlyLoader(): Wcs100ElevationCoverage = Wcs100ElevationCoverage(
            serviceAddress = SERVICE_ADDRESS,
            coverageName = COVERAGE_NAME,
            outputFormat = OUTPUT_FORMAT,
            sector = BOUNDING_SECTOR,
            resolution = RESOLUTION,
        )
    }
}
