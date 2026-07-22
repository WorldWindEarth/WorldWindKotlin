package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import kotlin.math.cos
import kotlin.math.sin
import earth.worldwind.render.RenderContext

abstract class AbstractTutorial(protected val engine: WorldWind) {

    /**
     * Defines a list of custom actions
     */
    open val actions: ArrayList<String>? = null

    /**
     * Sun azimuth in degrees from north, clockwise. The default `315°` (NW) is the
     * cartographic hillshading convention - relief shading reads correctly with top-left
     * light, and shadows still fall legibly toward the scene's lower right for a camera
     * looking north. Override per tutorial when a different angle reads better.
     */
    protected open val sunAzimuthDegrees: Double = 315.0

    /**
     * Sun elevation in degrees above the local horizon. The default `45°` matches the
     * hillshading convention and keeps shadows legible (long enough to read but not
     * horizon-grazing).
     */
    protected open val sunElevationDegrees: Double = 45.0

    /**
     * Runs any of custom actions listed in [actions]
     */
    open fun runAction(actionName: String) {}

    /**
     * Runs after switching to this example. The default installs a per-frame
     * [WorldWind.lightDirectionProvider] that orients shadows according to
     * [sunAzimuthDegrees] / [sunElevationDegrees] relative to the **current camera position**
     * - so even tutorials whose camera animates keep their lighting consistent. Subclasses
     * that want the day/night terminator (e.g. [BasicTutorial]) override and set
     * [WorldWind.time] instead.
     */
    open fun start() {
        engine.time = null
        engine.lightDirectionProvider = ::applySceneLight
    }

    /**
     * Runs before switching to another example. Default clears the provider; subclasses that
     * set [WorldWind.time] in [start] should also clear it here.
     */
    open fun stop() {
        engine.lightDirectionProvider = null
    }

    /**
     * Default [WorldWind.lightDirectionProvider] implementation. Reads the current
     * camera latitude/longitude from `rc` and computes a world-space unit vector toward the
     * sun via [computeSceneLightDirection]. Lives as a method (not a captured lambda) so
     * subclasses can override the angle parameters without re-registering a new provider.
     */
    protected open fun applySceneLight(rc: RenderContext) {
        computeSceneLightDirection(
            rc.camera.position, sunAzimuthDegrees, sunElevationDegrees, rc.lightDirection
        )
    }

}

/**
 * Computes a world-space (Cartesian, unit length) direction toward the sun given a camera
 * position and the sun's local-horizon angle (azimuth from north clockwise; elevation above
 * horizon). Uses the local east/north/up basis at the camera's lat/lon to map the
 * (azimuth, elevation) pair into ECEF, so a fixed (azimuth, elevation) gives a consistent
 * lighting angle relative to the camera no matter where on the globe the camera is.
 *
 * Result is written into [result] and returned (zero allocations on the hot path when callers
 * reuse a scratch Vec3).
 */
fun computeSceneLightDirection(
    position: Position,
    azimuthDegrees: Double,
    elevationDegrees: Double,
    result: Vec3,
): Vec3 {
    val lat = position.latitude.inRadians
    val lon = position.longitude.inRadians
    val az = azimuthDegrees.degrees.inRadians
    val el = elevationDegrees.degrees.inRadians

    val cosLat = cos(lat)
    val sinLat = sin(lat)
    val cosLon = cos(lon)
    val sinLon = sin(lon)

    // Local-frame basis at (lat, lon) for WorldWind's Y-up Cartesian system: east is tangent
    // toward +lon, north is tangent toward +lat, up is the surface normal.
    val eastX = cosLon
    val eastY = 0.0
    val eastZ = -sinLon

    val northX = -sinLat * sinLon
    val northY = cosLat
    val northZ = -sinLat * cosLon

    val upX = cosLat * sinLon
    val upY = sinLat
    val upZ = cosLat * cosLon

    // Sun in local frame: (east, north, up) = (sin(az)*cos(el), cos(az)*cos(el), sin(el)).
    val sE = sin(az) * cos(el)
    val sN = cos(az) * cos(el)
    val sU = sin(el)

    return result.set(
        eastX * sE + northX * sN + upX * sU,
        eastY * sE + northY * sN + upY * sU,
        eastZ * sE + northZ * sN + upZ * sU,
    )
}
