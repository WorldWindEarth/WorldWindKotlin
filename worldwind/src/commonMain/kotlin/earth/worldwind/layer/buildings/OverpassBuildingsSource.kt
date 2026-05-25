package earth.worldwind.layer.buildings

import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.TiledFeatureSource
import earth.worldwind.util.http.DefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Fetches OSM building footprints from a public [Overpass API](https://overpass-api.de/) endpoint.
 *
 * One request per [Sector] is issued, returning all `way`s tagged either `building=*` or
 * `building:part=*` whose geometry is inside the bbox. Parts are emitted alongside their
 * parent shell (Overpass unions de-dupe by id, so a way tagged both ways still appears once);
 * each part has its own height/`min_height`, so a tower-on-podium reads as two stacked volumes
 * without any extra assembly logic.
 *
 * Multipolygon (relation-based) buildings are intentionally not assembled here — they are rare
 * for buildings and would require a second round-trip; callers needing them can layer a
 * relation-aware source on top of this one.
 *
 * Heights are derived via [OsmHeight.resolve]; coordinates are emitted as
 * [Position]s with the top-altitude pre-baked, ready for [OsmBuildingsLayer] to wrap in an
 * extruded [earth.worldwind.shape.Polygon] with [AltitudeMode.RELATIVE_TO_GROUND].
 *
 * @param endpoint Overpass-compatible HTTP endpoint. Defaults to the public main instance —
 *                 for production use, route to a self-hosted mirror to respect fair-use limits.
 * @param timeout  Per-request Overpass timeout, also wired into the QL `[timeout:N];` header.
 */
class OverpassBuildingsSource(
    val endpoint: String = "https://overpass-api.de/api/interpreter",
    val timeout: Int = 25,
) : TiledFeatureSource {

    // One shared client per source. Constructing a fresh DefaultHttpClient per fetch defeats
    // OkHttp's connection pool + DNS cache, allocating a full dispatcher/pool/plugin chain per
    // tile — profiling showed >250k client constructions and ~2M IOException allocations during
    // a Manhattan zoom session. Reuse keeps the pool warm between tile fetches.
    private val client: HttpClient by lazy {
        DefaultHttpClient(
            connectTimeout = 5.seconds.inWholeMilliseconds,
            requestTimeout = (timeout + 5).seconds.inWholeMilliseconds,
        )
    }

    override suspend fun fetchTile(z: Int, x: Int, y: Int, sector: Sector): Flow<CachedFeatureRow>? {
        val buildings = fetchBuildings(sector)
        return buildings.mapNotNull(OsmBuildingCodec::encode).asFlow()
    }

    private suspend fun fetchBuildings(sector: Sector): List<OsmBuilding> {
        val south = sector.minLatitude.inDegrees
        val west = sector.minLongitude.inDegrees
        val north = sector.maxLatitude.inDegrees
        val east = sector.maxLongitude.inDegrees
        val query = """
            [out:json][timeout:$timeout];
            (way["building"]($south,$west,$north,$east);
             way["building:part"]($south,$west,$north,$east););
            out tags geom;
        """.trimIndent()

        val url = "$endpoint?data=${query.encodeURLParameter()}"
        val body = client.get(url) { expectSuccess = true }.bodyAsText()

        val response = JSON.decodeFromString<OverpassResponse>(body)
        return response.elements.mapNotNull(::toBuilding)
    }

    override fun close() { client.close() }

    private fun toBuilding(element: OverpassElement): OsmBuilding? {
        // Only handle ways with inline geometry. Skip nodes (they're isolated points) and
        // relations (would need member assembly, deferred).
        if (element.type != "way" || element.geometry.size < 4) return null
        // `building=no` and `building:part=no` are OSM conventions for "explicitly not a
        // building" — Overpass returns them because the tag is present, but we don't want
        // them rendering as boxes.
        if (element.tags["building"] == "no" || element.tags["building:part"] == "no") return null
        val (minHeight, height) = OsmHeight.resolve(element.tags)

        val ring = ArrayList<Position>(element.geometry.size)
        for (n in element.geometry) ring += Position.fromDegrees(n.lat, n.lon, height)
        // Drop the trailing duplicate vertex if Overpass closed the ring — Polygon's tessellator
        // handles open ring input and a duplicated endpoint produces a degenerate triangle on
        // some platforms.
        if (ring.size >= 2 && ring.first() == ring.last()) ring.removeAt(ring.lastIndex)
        if (ring.size < 3) return null
        // OSM ring winding isn't enforced; CW outer rings flip the roof normal DOWN
        // (culled from above) and walls INWARD. Normalise to CCW for Polygon's tessellator.
        if (isClockwise(ring)) ring.reverse()

        return OsmBuilding(
            id = "way/${element.id}",
            outerRing = ring,
            height = height,
            minHeight = minHeight,
            name = element.tags["name"],
            tags = element.tags,
        )
    }

    @Serializable
    private data class OverpassResponse(val elements: List<OverpassElement> = emptyList())

    @Serializable
    private data class OverpassElement(
        val type: String,
        val id: Long,
        val tags: Map<String, String> = emptyMap(),
        val geometry: List<OverpassNode> = emptyList(),
    )

    @Serializable
    private data class OverpassNode(val lat: Double, val lon: Double)

    companion object {
        /** Web-service type tag — written into `gpkg_web_service` when wiring a cache so
         *  `ContentManager.openLayer<OsmBuildingsLayer>` can reconstruct the source on subsequent loads. */
        const val SERVICE_TYPE = "OsmBuildings"

        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Shoelace signed-area sign in lon/lat space (positive = CCW). Only the sign matters. */
        internal fun isClockwise(ring: List<Position>): Boolean {
            var sum = 0.0
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                sum += a.longitude.inDegrees * b.latitude.inDegrees -
                       b.longitude.inDegrees * a.latitude.inDegrees
            }
            return sum < 0
        }
    }
}
