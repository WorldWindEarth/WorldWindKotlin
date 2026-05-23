package earth.worldwind.layer.buildings

import earth.worldwind.geom.Sector

/**
 * Strategy that produces [OsmBuilding] features for a given geographic [Sector].
 *
 * Implementations are typically network-backed (Overpass API, OSM vector tiles) and MUST be
 * safe to call from any thread; [OsmBuildingsLayer] invokes them on [kotlinx.coroutines.Dispatchers.Default].
 */
fun interface OsmBuildingsSource {
    /**
     * Fetch all buildings whose footprint intersects [sector]. May return an empty list if there
     * is nothing in the sector, or throw if the underlying source is unreachable — the layer
     * treats thrown errors as transient and will retry on the next render cycle.
     */
    suspend fun fetchBuildings(sector: Sector): List<OsmBuilding>
}
