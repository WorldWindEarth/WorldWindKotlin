package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Position
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import earth.worldwind.layer.source.parseGeoJsonAsFeatureRows
import earth.worldwind.ogc.WfsLayerFactory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decodes a WFS GetFeature response body into [CachedFeatureRow]s — the commonMain-only path
 * shared by [WfsBulkFeatureSource] (whole layer) and [WfsTiledFeatureSource] (per tile). GeoJSON
 * goes through kotlinx-serialization (after [WfsLayerFactory.sanitizeGeoJson]); GML through
 * [WfsGmlReader].
 */
internal object WfsFeatureDecoder {

    fun decode(body: String, isGml: Boolean): List<CachedFeatureRow> = if (isGml) {
        WfsGmlReader.parseFeatureRecords(body).map { record ->
            CachedFeatureRow(record.geometry.toCached(), propertiesToJson(record.properties))
        }
    } else {
        parseGeoJsonAsFeatureRows(WfsLayerFactory.sanitizeGeoJson(body))
    }

    private fun WfsGmlReader.GmlGeometry.toCached(): CachedGeometry = when (this) {
        is WfsGmlReader.GmlGeometry.PointGeom -> position.toCachedPoint(is3D)
        is WfsGmlReader.GmlGeometry.LineGeom -> positions.toCachedRing(is3D)
        is WfsGmlReader.GmlGeometry.PolygonGeom -> CachedGeometry.Polygon(
            buildList {
                add(exterior.toCachedRing(is3D))
                for (interior in interiors) add(interior.toCachedRing(is3D))
            }
        )
    }

    // Keep altitude only when the source coordinates were 3D — a 2D geometry yields z = null so it
    // clamps to ground rather than being treated as absolute-altitude-0.
    private fun Position.toCachedPoint(is3D: Boolean): CachedGeometry.Point =
        CachedGeometry.Point(longitude.inDegrees, latitude.inDegrees, altitude.takeIf { is3D })

    /** Flatten GML positions straight into a ring's primitive arrays — no per-vertex object. */
    private fun List<Position>.toCachedRing(is3D: Boolean): CachedGeometry.LineString {
        val xy = DoubleArray(size * 2)
        val z = if (is3D) DoubleArray(size) else null
        for (i in indices) {
            val p = this[i]
            xy[i * 2] = p.longitude.inDegrees; xy[i * 2 + 1] = p.latitude.inDegrees
            if (z != null) z[i] = p.altitude
        }
        return CachedGeometry.LineString(xy, z)
    }

    private fun propertiesToJson(props: Map<String, String>): String =
        JsonObject(props.mapValues { JsonPrimitive(it.value) }).toString()
}
