package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.WfsLayerFactory

/**
 * A [RenderableLayer] whose contents are sourced from an OGC WFS endpoint and can be
 * re-fetched at will. Use this when the underlying feature set is too large to fetch
 * once — e.g. drive [refresh] from your application's navigator-change handler with the
 * current viewport [Sector] so only features inside the visible globe extent are loaded.
 *
 * Each [refresh] call internally invokes [WfsLayerFactory.createLayer] (so capabilities
 * negotiation, output-format selection, OWS exception handling, and GeoJSON/GML
 * decoding all flow through the same code path) and atomically swaps the renderables.
 * Concurrent refresh calls are not coordinated by this class — cancel the outer job
 * yourself before issuing a new refresh if you need that.
 */
class WfsLayer(
    private val serviceAddress: String,
    private val typeName: String,
    displayName: String? = null,
    private val cqlFilter: String? = null,
) : RenderableLayer(displayName ?: typeName) {

    /**
     * Fetch features for the given [sector] (or the feature type's full WGS84 bounding
     * box if null) and atomically replace this layer's renderables.
     */
    suspend fun refresh(sector: Sector? = null, maxFeatures: Int? = null) {
        val fresh = WfsLayerFactory.createLayer(
            serviceAddress = serviceAddress,
            typeName = typeName,
            displayName = displayName,
            sector = sector,
            maxFeatures = maxFeatures,
            cqlFilter = cqlFilter,
        )
        val newRenderables = fresh.toList()
        clearRenderables()
        addAllRenderables(newRenderables)
    }
}
