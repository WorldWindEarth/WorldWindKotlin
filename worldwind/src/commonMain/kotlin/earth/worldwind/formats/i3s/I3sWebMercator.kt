package earth.worldwind.formats.i3s

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator (EPSG:3857) support for projected-CRS I3S layers: detection plus the spherical
 * metre↔degree conversions the standard defines. Projected packages store OBB centers and vertex
 * offsets in Mercator metres; x is linear in longitude while y needs the exact inverse projection
 * (linearizing y across a node drifts tens of centimetres at mid latitudes).
 */
object I3sWebMercator {
    /** Layer's horizontal CRS is Web Mercator — from `spatialReference.wkid`/`latestWkid`, falling
     *  back to the `store` CRS URLs (`…/EPSG/0/{wkid}`) some packages carry instead. */
    fun isWebMercator(doc: SceneLayerDoc): Boolean {
        doc.spatialReference?.let { sr ->
            if (sr.wkid in WEB_MERCATOR_WKIDS || sr.latestWkid in WEB_MERCATOR_WKIDS) return true
            if (sr.wkid != 0 || sr.latestWkid != 0) return false
        }
        val crs = doc.store.vertexCRS ?: doc.store.indexCRS ?: return false
        return WEB_MERCATOR_WKIDS.any { crs.trimEnd('/').endsWith("/$it") }
    }

    fun xToLongitudeDegrees(x: Double): Double = x / RADIUS * DEGREES_PER_RADIAN

    fun yToLatitudeRadians(y: Double): Double = atan(sinh(y / RADIUS))

    fun yToLatitudeDegrees(y: Double): Double = yToLatitudeRadians(y) * DEGREES_PER_RADIAN

    fun latitudeRadiansToY(latitudeRadians: Double): Double = asinh(tan(latitudeRadians)) * RADIUS

    /** EPSG:3857 sphere radius (the WGS84 semi-major axis). */
    const val RADIUS = 6378137.0

    private const val DEGREES_PER_RADIAN = 180.0 / PI

    /** Web Mercator across its aliases: EPSG:3857, legacy Esri 102100/102113, informal 900913. */
    private val WEB_MERCATOR_WKIDS = intArrayOf(3857, 102100, 102113, 900913)
}
