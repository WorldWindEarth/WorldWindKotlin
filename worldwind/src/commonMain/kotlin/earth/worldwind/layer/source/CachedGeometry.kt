package earth.worldwind.layer.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform-neutral geometry envelope for feature cache rows. Platform implementations convert
 * to/from their native geometry (mil.nga.sf on JVM, JSON on JS). Z is altitude in meters.
 *
 * Pinned [SerialName]s decouple the cache wire format from the class FQN so package moves
 * don't invalidate every cache file (kotlinx-serialization's default discriminator is the FQN).
 */
@Serializable
sealed class CachedGeometry {
    @Serializable
    @SerialName("Point")
    data class Point(val x: Double, val y: Double, val z: Double? = null) : CachedGeometry()

    @Serializable
    @SerialName("LineString")
    data class LineString(val points: List<Point>) : CachedGeometry()

    /** First ring is outer; remaining rings are holes. Closing vertex may or may not be present. */
    @Serializable
    @SerialName("Polygon")
    data class Polygon(val rings: List<LineString>) : CachedGeometry()
}

/** One cached feature row. Null [geometry] is the sentinel that marks a tile as fetched-but-empty. */
@Serializable
data class CachedFeatureRow(val geometry: CachedGeometry?, val properties: String?)
