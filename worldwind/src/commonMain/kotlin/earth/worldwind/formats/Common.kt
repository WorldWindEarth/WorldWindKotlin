package earth.worldwind.formats

import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Ellipse
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import earth.worldwind.shape.SurfaceImage
import kotlin.math.cos

private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
internal const val DEFAULT_DENSITY = 1.0f
internal const val HIGHLIGHT_INCREMENT = 4f
internal const val DEFAULT_IMAGE_SCALE = 1.0
internal const val DEFAULT_PLACEMARK_ICON_SIZE = 24.0
internal const val DEFAULT_LABEL_VISIBILITY_THRESHOLD = 0.0

/**
 * Compute a bounding sector for a list of renderables. The sector is expanded by a margin
 * fraction to ensure the renderables are not too close to the edge of the sector.
 */
internal fun computeSector(
    renderables: List<Renderable>,
    marginFraction: Double = 0.05
): Sector? {
    if (renderables.isEmpty()) return null

    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY

    fun Position.check() {
        val lat = latitude.inDegrees
        val lon = longitude.inDegrees

        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        if (lon < minLon) minLon = lon
        if (lon > maxLon) maxLon = lon
    }

    renderables.forEach { renderable ->
        when (renderable) {
            is Label -> renderable.position.check()
            is Placemark -> renderable.position.check()
            is Path -> renderable.positions.forEach { it.check() }
            is Polygon -> {
                for (index in 0 until renderable.boundaryCount) {
                    renderable.getBoundary(index).forEach { it.check() }
                }
            }

            is Ellipse -> {
                val majorRadius = renderable.majorRadius
                val minorRadius = renderable.minorRadius
                val center = renderable.center
                val lat = center.latitude.inDegrees
                val lon = center.longitude.inDegrees
                val latDelta = (majorRadius / METERS_PER_LATITUDE_DEGREE)
                val lonDelta =
                    (minorRadius / (METERS_PER_LATITUDE_DEGREE * cos(center.latitude.inRadians)))
                if (lat - latDelta < minLat) minLat = lat - latDelta
                if (lat + latDelta > maxLat) maxLat = lat + latDelta
                if (lon - lonDelta < minLon) minLon = lon - lonDelta
                if (lon + lonDelta > maxLon) maxLon = lon + lonDelta
            }

            is SurfaceImage -> {
                renderable.sector.run {
                    if (minLatitude.inDegrees < minLat) minLat = minLatitude.inDegrees
                    if (maxLatitude.inDegrees > maxLat) maxLat = maxLatitude.inDegrees
                    if (minLongitude.inDegrees < minLon) minLon = minLongitude.inDegrees
                    if (maxLongitude.inDegrees > maxLon) maxLon = maxLongitude.inDegrees
                }
            }
        }
    }

    val deltaLat = maxLat - minLat
    val deltaLon = maxLon - minLon
    val latMargin = deltaLat * marginFraction
    val lonMargin = deltaLon * marginFraction

    // verify sector values is valid
    val values = setOf(maxLon, maxLat, minLon, minLat)
    if (values.contains(Double.POSITIVE_INFINITY)) return null
    if (values.contains(Double.NEGATIVE_INFINITY)) return null

    return Sector.fromDegrees(
        minLatDegrees = minLat - latMargin,
        minLonDegrees = minLon - lonMargin,
        deltaLatDegrees = deltaLat + 2 * latMargin,
        deltaLonDegrees = deltaLon + 2 * lonMargin
    )
}

internal fun isValidHttpsUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val httpsUrlRegex = Regex(
        pattern = "^https://[\\w.-]+(?:\\.[\\w.-]+)+(?:/\\S*)?$",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    return httpsUrlRegex.matches(url)
}

internal fun forceHttps(url: String) = url.replace("http://", "https://")