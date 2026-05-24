package earth.worldwind.layer.cache

import kotlinx.serialization.Serializable

/**
 * Platform-neutral geometry envelope for feature cache rows. Platform implementations convert
 * to/from their native geometry (mil.nga.sf on JVM, JSON on JS). Z is altitude in meters.
 */
@Serializable
sealed class CachedGeometry {
    @Serializable
    data class Point(val x: Double, val y: Double, val z: Double? = null) : CachedGeometry()

    @Serializable
    data class LineString(val points: List<Point>) : CachedGeometry()

    /** First ring is outer; remaining rings are holes. Closing vertex may or may not be present. */
    @Serializable
    data class Polygon(val rings: List<LineString>) : CachedGeometry()
}

/** One cached feature row. Null [geometry] is the sentinel that marks a tile as fetched-but-empty. */
@Serializable
data class CachedFeatureRow(val geometry: CachedGeometry?, val properties: String?)
