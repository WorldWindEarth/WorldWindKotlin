package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import kotlin.math.cos
import kotlin.math.sin

abstract class AbstractTutorial(protected val engine: WorldWind) {

    /**
     * Defines a list of custom actions
     */
    open val actions: ArrayList<String>? = null

    /**
     * Sun azimuth in degrees from north, clockwise. The default `290°` (WNW) places the sun
     * behind-and-slightly-left of a camera looking north so shadows fall toward the far right
     * of the scene. Override per tutorial when a different angle reads better.
     */
    protected open val sunAzimuthDegrees: Double = 290.0

    /**
     * Sun elevation in degrees above the local horizon. The default `45°` keeps shadows
     * legible (long enough to read but not horizon-grazing).
     */
    protected open val sunElevationDegrees: Double = 45.0

    /**
     * Runs any of custom actions listed in [actions]
     */
    open fun runAction(actionName: String) {}

    /**
     * Runs after switching to this example. The default installs a per-frame
     * [AtmosphereLayer.lightDirectionProvider] that orients shadows according to
     * [sunAzimuthDegrees] / [sunElevationDegrees] relative to the **current camera position**
     * - so even tutorials whose camera animates keep their lighting consistent. Subclasses
     * that want the day/night terminator (e.g. [BasicTutorial]) override and set
     * [AtmosphereLayer.time] instead.
     */
    open fun start() {
        val atm = findAtmosphereLayer() ?: return
        atm.time = null
        atm.lightDirectionProvider = ::applySceneLight
    }

    /**
     * Runs before switching to another example. Default clears the provider; subclasses that
     * set [AtmosphereLayer.time] in [start] should also clear it here.
     */
    open fun stop() {
        findAtmosphereLayer()?.lightDirectionProvider = null
    }

    /**
     * Default [AtmosphereLayer.lightDirectionProvider] implementation. Reads the current
     * camera latitude/longitude from `rc` and computes a world-space unit vector toward the
     * sun via [computeSceneLightDirection]. Lives as a method (not a captured lambda) so
     * subclasses can override the angle parameters without re-registering a new provider.
     */
    protected open fun applySceneLight(rc: earth.worldwind.render.RenderContext) {
        computeSceneLightDirection(
            rc.camera.position, sunAzimuthDegrees, sunElevationDegrees, rc.lightDirection
        )
    }

    /**
     * Look up the atmosphere layer in the engine's layer list by class. Tutorials use this
     * to mutate `time` / `lightDirectionProvider` without holding a direct reference (the
     * layer was added by the platform's tutorial entry point, not by the tutorial itself).
     * Class-cast lookup is more robust than name lookup against `displayName` mutations
     * (relocalisation, per-app overrides, etc.).
     */
    protected fun findAtmosphereLayer(): AtmosphereLayer? =
        engine.layers.firstOrNull { it is AtmosphereLayer } as? AtmosphereLayer

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
