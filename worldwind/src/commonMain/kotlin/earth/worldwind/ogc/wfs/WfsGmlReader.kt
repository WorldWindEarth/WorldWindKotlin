@file:OptIn(nl.adaptivity.xmlutil.XmlUtilInternal::class)

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
 * geometries + feature properties inside it. Used when the server doesn't advertise a
 * GeoJSON-compatible output format.
 *
 * The reader walks `<wfs:member>` / `<gml:featureMember>` boundaries to associate each
 * geometry with the surrounding feature's attribute leaves (captured as a flat
 * `Map<String,String>` keyed by element local name). GML Multi* geometries fan out
 * into one [FeatureRecord] per inner geometry, all sharing the same properties.
 *
 * Polygon interior rings (holes) are passed through as additional boundaries on the
 * resulting [Polygon] shape.
 */
internal object WfsGmlReader {
    // GML 3.1 (WFS 1.1.0) uses the older namespace; accept both.
    private const val GML31_NAMESPACE = "http://www.opengis.net/gml"

    /** One geometry + the feature attributes that surrounded it. */
    internal data class FeatureRecord(val geometry: GmlGeometry, val properties: Map<String, String>)

    /** Geometry extracted from a GML response. Multi* containers expand into one record
     *  per inner geometry, each sharing the parent feature's properties. */
    internal sealed interface GmlGeometry {
        /** [is3D] records whether the source coordinates carried a Z, so the cache can keep a
         *  legitimate altitude (including exactly 0.0) and distinguish it from a 2D geometry. */
        data class PointGeom(val position: Position, val is3D: Boolean) : GmlGeometry
        data class LineGeom(val positions: List<Position>, val is3D: Boolean) : GmlGeometry
        /** [interiors] is the list of hole boundaries (GML `<gml:interior>` rings). */
        data class PolygonGeom(
            val exterior: List<Position>, val interiors: List<List<Position>> = emptyList(), val is3D: Boolean = false,
        ) : GmlGeometry
    }

    /** Decode the GML payload into [Renderable]s suitable for adding to a [RenderableLayer]. */
    fun parseFeatures(xmlText: String): List<Renderable> = toRenderables(parseFeatureRecords(xmlText))

    /** Convert pre-parsed [FeatureRecord]s to renderables, invoking [customLogicToApplyProperties]
     *  once per record with the created renderable as `this` and the feature's properties
     *  copied into a [LinkedHashMap] as the argument. */
    fun toRenderables(
        records: List<FeatureRecord>,
        customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
    ): List<Renderable> = records.map { record ->
        val renderable: Renderable = when (val g = record.geometry) {
            is GmlGeometry.PointGeom -> Placemark(g.position)
            is GmlGeometry.LineGeom -> Path(g.positions).asTerrain()
            is GmlGeometry.PolygonGeom -> Polygon(g.exterior).apply {
                g.interiors.forEach { addBoundary(it) }
                asTerrain()
            }
        }
        renderable.customLogicToApplyProperties(LinkedHashMap<String, Any?>(record.properties))
        renderable
    }

    /** Decode the GML payload into raw feature records. Split from [parseFeatures] so the
     *  parsing logic can be unit-tested without instantiating Android-coupled shape
     *  classes like [Placemark]. */
    internal fun parseFeatureRecords(xmlText: String): List<FeatureRecord> {
        val reader = xmlStreaming.newGenericReader(StringReader(xmlText))
        val out = mutableListOf<FeatureRecord>()
        while (reader.hasNext()) {
            if (reader.next() == EventType.START_ELEMENT && reader.isMemberWrapper()) {
                out += parseFeatureMember(reader)
            }
        }
        return out
    }

    private fun XmlReader.isMemberWrapper(): Boolean =
        localName == "member" || localName == "featureMember"

    /** Reader sits on `<wfs:member>` / `<gml:featureMember>`. Inside is a single application
     *  feature element (or, for older servers, the geometry / property directly). */
    private fun parseFeatureMember(reader: XmlReader): List<FeatureRecord> {
        val out = mutableListOf<FeatureRecord>()
        forEachChildElement(reader) {
            // Direct child of the wrapper is the application feature element.
            val (geometries, properties) = extractFeature(reader)
            geometries.forEach { out += FeatureRecord(it, properties) }
        }
        return out
    }

    /** Walk the children of an application feature element. Each non-GML element is
     *  either a leaf text property or wraps a GML geometry; GML elements at this depth
     *  are bare geometry properties. */
    private fun extractFeature(reader: XmlReader): Pair<List<GmlGeometry>, Map<String, String>> {
        val geometries = mutableListOf<GmlGeometry>()
        val properties = mutableMapOf<String, String>()
        forEachChildElement(reader) {
            if (reader.isGml()) {
                geometries += parseGmlGeometry(reader)
            } else {
                val propertyName = reader.localName
                val (text, nested) = readPropertyContent(reader)
                geometries += nested
                if (text != null) properties[propertyName] = text
            }
        }
        return geometries to properties
    }

    /** Reader sits on a non-GML property element. Walk to its END_ELEMENT, collecting
     *  text content and any GML geometries found nested inside. */
    private fun readPropertyContent(reader: XmlReader): Pair<String?, List<GmlGeometry>> {
        val text = StringBuilder()
        val geoms = mutableListOf<GmlGeometry>()
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> {
                    if (reader.isGml()) {
                        geoms += parseGmlGeometry(reader)
                    } else {
                        // Nested non-GML element — recurse so we still pick up geometries
                        // wrapped by complex property paths.
                        val (_, nestedGeoms) = readPropertyContent(reader)
                        geoms += nestedGeoms
                    }
                }
                EventType.TEXT, EventType.CDSECT -> text.append(reader.text)
                EventType.END_ELEMENT -> return (text.toString().trim().ifEmpty { null }) to geoms
                else -> Unit
            }
        }
        return (text.toString().trim().ifEmpty { null }) to geoms
    }

    /** Reader sits on a GML geometry element. Returns a list because Multi* containers
     *  fan out into multiple inner geometries. */
    private fun parseGmlGeometry(reader: XmlReader): List<GmlGeometry> = when (reader.localName) {
        "Point" -> parsePoint(reader)?.let { listOf(it) } ?: emptyList()
        "LineString" -> parseLineString(reader)?.let { listOf(it) } ?: emptyList()
        "Polygon" -> parsePolygon(reader)?.let { listOf(it) } ?: emptyList()
        "MultiPoint" -> parseMulti(reader, "Point", ::parsePoint)
        "MultiCurve", "MultiLineString" -> parseMulti(reader, "LineString", ::parseLineString)
        "MultiSurface", "MultiPolygon" -> parseMulti(reader, "Polygon", ::parsePolygon)
        else -> { skipElement(reader); emptyList() }
    }

    /** Parse `<gml:Point>` — reader positioned on its START_ELEMENT, exits on END_ELEMENT. */
    private fun parsePoint(reader: XmlReader): GmlGeometry.PointGeom? {
        val srs = reader.attr("srsName")
        var result: Position? = null
        var is3D = false
        forEachChildElement(reader) {
            if (reader.isGml() && (reader.localName == "pos" || reader.localName == "coordinates")) {
                val text = readElementText(reader)
                val normalized = if (reader.localName == "coordinates") text.replace(',', ' ') else text
                // A third coordinate token means the source carried a Z (true 3D point).
                is3D = normalized.trim().split(Regex("\\s+")).size >= 3
                result = parseSinglePos(normalized, srs)
            } else skipElement(reader)
        }
        return result?.let { GmlGeometry.PointGeom(it, is3D) }
    }

    /** Parse `<gml:LineString>` — reader positioned on its START_ELEMENT. */
    private fun parseLineString(reader: XmlReader): GmlGeometry.LineGeom? {
        val srs = reader.attr("srsName")
        val outerDim = reader.attr("srsDimension")?.toIntOrNull() ?: 2
        var is3D = outerDim >= 3
        val positions = mutableListOf<Position>()
        forEachChildElement(reader) {
            if (!reader.isGml()) { skipElement(reader); return@forEachChildElement }
            when (reader.localName) {
                "posList" -> {
                    val dim = reader.attr("srsDimension")?.toIntOrNull() ?: outerDim
                    if (dim >= 3) is3D = true
                    positions += parsePosList(readElementText(reader), srs, dim)
                }
                "pos" -> parseSinglePos(readElementText(reader), srs)?.let(positions::add)
                "coordinates" -> positions += parsePosList(readElementText(reader).replace(',', ' '), srs, outerDim)
                else -> skipElement(reader)
            }
        }
        return positions.takeIf { it.size >= 2 }?.let { GmlGeometry.LineGeom(it, is3D) }
    }

    /** Parse `<gml:Polygon>` — reader positioned on its START_ELEMENT. Returns the
     *  exterior ring and any interior (hole) rings. */
    private fun parsePolygon(reader: XmlReader): GmlGeometry.PolygonGeom? {
        val srs = reader.attr("srsName")
        val outerDim = reader.attr("srsDimension")?.toIntOrNull() ?: 2
        var exterior: List<Position>? = null
        val interiors = mutableListOf<List<Position>>()
        forEachChildElement(reader) {
            if (!reader.isGml()) { skipElement(reader); return@forEachChildElement }
            when (reader.localName) {
                "exterior" -> exterior = parseLinearRing(reader, srs, outerDim)
                "interior" -> parseLinearRing(reader, srs, outerDim)?.let(interiors::add)
                else -> skipElement(reader)
            }
        }
        return exterior?.takeIf { it.size >= 3 }?.let { GmlGeometry.PolygonGeom(it, interiors, outerDim >= 3) }
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
     *  [memberLocalName]; we descend through the wrapper to invoke [parser]. */
    private fun <T : Any> parseMulti(reader: XmlReader, memberLocalName: String, parser: (XmlReader) -> T?): List<T> {
        val out = mutableListOf<T>()
        forEachChildElement(reader) {
            if (!reader.isGml()) { skipElement(reader); return@forEachChildElement }
            forEachChildElement(reader) {
                if (reader.isGml() && reader.localName == memberLocalName) {
                    parser(reader)?.let { out += it }
                } else skipElement(reader)
            }
        }
        return out
    }

    // ---- coord parsing ----

    /** True if [srs] denotes a CRS that publishes coordinates as longitude-then-latitude. */
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
     *  *must* consume that child fully (e.g. via [skipElement] or another descent). */
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
