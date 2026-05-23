package earth.worldwind.ogc.wfs

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position
import earth.worldwind.ogc.gml.GML32_NAMESPACE
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.core.impl.multiplatform.StringReader
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Pull-parses an OGC WFS GetFeature response encoded as GML (3.1 or 3.2) and emits the
 * geometries inside it as WorldWind [Renderable]s. Used when the server doesn't advertise
 * a GeoJSON-compatible output format.
 *
 * Feature attribute schemas vary per WFS endpoint, so this reader doesn't try to map them
 * to placemark labels — it focuses purely on geometry extraction: Point → [Placemark],
 * LineString → [Path], Polygon → [Polygon] (exterior ring only), and the GML Multi*
 * containers expand to lists of the corresponding shape.
 */
internal object WfsGmlReader {
    // GML 3.1 (WFS 1.1.0) uses the older namespace; accept both.
    private const val GML31_NAMESPACE = "http://www.opengis.net/gml"

    /** Geometry extracted from a GML response. Each variant is one shape; Multi*
     *  containers are flattened into one element per inner geometry. */
    internal sealed interface GmlGeometry {
        data class PointGeom(val position: Position) : GmlGeometry
        data class LineGeom(val positions: List<Position>) : GmlGeometry
        data class PolygonGeom(val exterior: List<Position>) : GmlGeometry
    }

    /** Decode the GML payload into [Renderable]s suitable for adding to a [RenderableLayer]. */
    fun parseFeatures(xmlText: String): List<Renderable> = parseGeometries(xmlText).map { geom ->
        when (geom) {
            is GmlGeometry.PointGeom -> Placemark(geom.position)
            is GmlGeometry.LineGeom -> Path(geom.positions).asTerrain()
            is GmlGeometry.PolygonGeom -> Polygon(geom.exterior).asTerrain()
        }
    }

    /** Decode the GML payload into raw geometries — split from [parseFeatures] so the
     *  parsing logic can be unit-tested without instantiating Android-coupled shape
     *  classes like [Placemark]. */
    internal fun parseGeometries(xmlText: String): List<GmlGeometry> {
        val reader = xmlStreaming.newGenericReader(StringReader(xmlText))
        val out = mutableListOf<GmlGeometry>()
        while (reader.hasNext()) {
            if (reader.next() == EventType.START_ELEMENT && reader.isGml()) {
                when (reader.localName) {
                    "Point" -> parsePoint(reader)?.let { out += GmlGeometry.PointGeom(it) }
                    "LineString" -> parseLineString(reader)?.let { out += GmlGeometry.LineGeom(it) }
                    "Polygon" -> parsePolygon(reader)?.let { out += GmlGeometry.PolygonGeom(it) }
                    "MultiPoint" -> parseMulti(reader, "Point", ::parsePoint)
                        .forEach { out += GmlGeometry.PointGeom(it) }
                    "MultiCurve", "MultiLineString" -> parseMulti(reader, "LineString", ::parseLineString)
                        .forEach { out += GmlGeometry.LineGeom(it) }
                    "MultiSurface", "MultiPolygon" -> parseMulti(reader, "Polygon", ::parsePolygon)
                        .forEach { out += GmlGeometry.PolygonGeom(it) }
                }
            }
        }
        return out
    }

    /** Parse `<gml:Point>` — reader positioned on its START_ELEMENT, exits on END_ELEMENT. */
    private fun parsePoint(reader: XmlReader): Position? {
        val srs = reader.attr("srsName")
        var result: Position? = null
        forEachChildElement(reader) {
            if (reader.isGml() && (reader.localName == "pos" || reader.localName == "coordinates")) {
                val text = readElementText(reader)
                val normalized = if (reader.localName == "coordinates") text.replace(',', ' ') else text
                result = parseSinglePos(normalized, srs)
            } else skipElement(reader)
        }
        return result
    }

    /** Parse `<gml:LineString>` — reader positioned on its START_ELEMENT. */
    private fun parseLineString(reader: XmlReader): List<Position>? {
        val srs = reader.attr("srsName")
        val outerDim = reader.attr("srsDimension")?.toIntOrNull() ?: 2
        val positions = mutableListOf<Position>()
        forEachChildElement(reader) {
            if (!reader.isGml()) { skipElement(reader); return@forEachChildElement }
            when (reader.localName) {
                "posList" -> {
                    val dim = reader.attr("srsDimension")?.toIntOrNull() ?: outerDim
                    positions += parsePosList(readElementText(reader), srs, dim)
                }
                "pos" -> parseSinglePos(readElementText(reader), srs)?.let(positions::add)
                "coordinates" -> positions += parsePosList(readElementText(reader).replace(',', ' '), srs, outerDim)
                else -> skipElement(reader)
            }
        }
        return positions.takeIf { it.size >= 2 }
    }

    /** Parse `<gml:Polygon>` — reader positioned on its START_ELEMENT. Returns the
     *  exterior ring; interior rings (holes) are skipped because [Polygon] doesn't
     *  currently expose multi-ring construction here. */
    private fun parsePolygon(reader: XmlReader): List<Position>? {
        val srs = reader.attr("srsName")
        val outerDim = reader.attr("srsDimension")?.toIntOrNull() ?: 2
        var exterior: List<Position>? = null
        forEachChildElement(reader) {
            if (reader.isGml() && reader.localName == "exterior") {
                exterior = parseLinearRing(reader, srs, outerDim)
            } else skipElement(reader)
        }
        return exterior?.takeIf { it.size >= 3 }
    }

    /** Reader on `<gml:exterior>` / `<gml:interior>` start; descends into the inner LinearRing. */
    private fun parseLinearRing(reader: XmlReader, srs: String?, outerDim: Int): List<Position>? {
        var ring: List<Position>? = null
        forEachChildElement(reader) {
            if (reader.isGml() && reader.localName == "LinearRing") {
                val dim = reader.attr("srsDimension")?.toIntOrNull() ?: outerDim
                forEachChildElement(reader) {
                    if (reader.isGml() && reader.localName == "posList") {
                        ring = parsePosList(readElementText(reader), srs, dim)
                    } else skipElement(reader)
                }
            } else skipElement(reader)
        }
        return ring
    }

    /** Parse a GML Multi* element. Each member element wraps one inner geometry of
     *  [memberLocalName] (Point/LineString/Polygon); we descend through the wrapper to
     *  the inner geometry and invoke [parser]. */
    private fun <T : Any> parseMulti(reader: XmlReader, memberLocalName: String, parser: (XmlReader) -> T?): List<T> {
        val out = mutableListOf<T>()
        forEachChildElement(reader) {
            if (!reader.isGml()) { skipElement(reader); return@forEachChildElement }
            // wrapper is <surfaceMember>/<curveMember>/<pointMember>
            forEachChildElement(reader) {
                if (reader.isGml() && reader.localName == memberLocalName) {
                    parser(reader)?.let { out += it }
                } else skipElement(reader)
            }
        }
        return out
    }

    // ---- coord parsing ----

    /** True if [srs] denotes a CRS that publishes coordinates as longitude-then-latitude.
     *  The URN form `urn:ogc:def:crs:EPSG::4326` is latitude-first per OGC convention;
     *  `CRS84` / `CRS:84` and the legacy bare `EPSG:4326` are longitude-first. */
    private fun isLonLat(srs: String?): Boolean = srs != null && (
        srs.contains("CRS84", ignoreCase = true) ||
        srs.contains("CRS:84", ignoreCase = true) ||
        srs.equals("EPSG:4326", ignoreCase = true)
    )

    private fun parseSinglePos(raw: String, srs: String?): Position? {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val a = parts[0].toDoubleOrNull() ?: return null
        val b = parts[1].toDoubleOrNull() ?: return null
        val alt = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        return if (isLonLat(srs)) Position.fromDegrees(b, a, alt)
        else Position.fromDegrees(a, b, alt)
    }

    private fun parsePosList(raw: String, srs: String?, dim: Int): List<Position> {
        val nums = raw.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        val stride = if (dim >= 3) 3 else 2
        if (nums.size < stride) return emptyList()
        val lonLat = isLonLat(srs)
        return (0 until nums.size - (stride - 1) step stride).map { i ->
            val a = nums[i]
            val b = nums[i + 1]
            val alt = if (stride == 3) nums[i + 2] else 0.0
            if (lonLat) Position.fromDegrees(b, a, alt) else Position.fromDegrees(a, b, alt)
        }
    }

    // ---- low-level XML iteration ----

    /** Reader sits on a parent START_ELEMENT. Pull events until the matching END_ELEMENT,
     *  invoking [onChild] each time we encounter a direct-child START. The callback
     *  *must* consume that child fully (e.g. via [skipElement] or another descent); on
     *  return the reader must be positioned just past the child's END_ELEMENT. */
    private inline fun forEachChildElement(reader: XmlReader, onChild: () -> Unit) {
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> onChild()
                EventType.END_ELEMENT -> return
                else -> Unit
            }
        }
    }

    /** Reader sits on a START_ELEMENT; advance through nested children to the matching END_ELEMENT. */
    private fun skipElement(reader: XmlReader) {
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                EventType.START_ELEMENT -> depth++
                EventType.END_ELEMENT -> depth--
                else -> Unit
            }
        }
    }

    /** Reader sits on a START_ELEMENT; collect concatenated text content until the END_ELEMENT. */
    private fun readElementText(reader: XmlReader): String {
        val sb = StringBuilder()
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.TEXT, EventType.CDSECT -> sb.append(reader.text)
                EventType.END_ELEMENT -> return sb.toString()
                EventType.START_ELEMENT -> skipElement(reader) // unexpected nested element; just skip
                else -> Unit
            }
        }
        return sb.toString()
    }

    private fun XmlReader.isGml() = namespaceURI == GML32_NAMESPACE || namespaceURI == GML31_NAMESPACE

    private fun XmlReader.attr(name: String): String? {
        for (i in 0 until attributeCount) if (getAttributeLocalName(i) == name) return getAttributeValue(i)
        return null
    }

    private fun Path.asTerrain() = also {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
    }

    private fun Polygon.asTerrain() = also {
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
    }
}
