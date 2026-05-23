package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.layer.WebFeatureLayer
import earth.worldwind.ogc.WfsLayerFactory
import earth.worldwind.render.Renderable
import io.ktor.client.HttpClientConfig

/**
 * A [RenderableLayer] backed by an OGC WFS endpoint. Mirrors the
 * [earth.worldwind.layer.WebImageLayer] / [WmsImageLayer] pattern used by tile services
 * so the GPKG content manager can persist the service association alongside cached
 * feature data and rebuild the layer when the package is reopened.
 *
 * The constructor only captures the service binding; call [refresh] (or have the GPKG
 * content manager call it for you) to fetch features. Per-fetch [HttpClientConfig]
 * customizers, viewport [Sector], `maxFeatures`, `pageSize` and `cqlFilter` flow through
 * to [WfsLayerFactory.createLayer] unchanged.
 */
open class WfsFeatureLayer(
    override val serviceAddress: String,
    override val layerName: String,
    override val serviceMetadata: String? = null,
    override val outputFormat: String = "application/json",
    displayName: String? = null,
    protected val cqlFilter: String? = null,
    protected val customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
    protected val pageSize: Int? = null,
    protected val clientConfig: HttpClientConfig<*>.() -> Unit = {},
) : RenderableLayer(displayName ?: layerName), WebFeatureLayer {

    override val serviceType: String = SERVICE_TYPE

    /**
     * Fetch features from the WFS endpoint and atomically replace this layer's
     * renderables. Each call re-runs the full [WfsLayerFactory] pipeline (capabilities
     * negotiation, output-format selection, OWS exception handling, GeoJSON or GML
     * decoding) so the layer stays in sync with the server.
     *
     * Subclasses can override to add side-effects (e.g. a GPKG cache write) by passing
     * an [onResponseBody] hook through to [WfsLayerFactory.createLayer].
     */
    open suspend fun refresh(
        sector: Sector? = null,
        maxFeatures: Int? = null,
        onResponseBody: (suspend (String, Boolean) -> Unit)? = null,
    ) {
        val fresh = WfsLayerFactory.createLayer(
            serviceAddress = serviceAddress,
            typeName = layerName,
            serviceMetadata = serviceMetadata,
            displayName = displayName,
            sector = sector,
            maxFeatures = maxFeatures,
            cqlFilter = cqlFilter,
            customLogicToApplyProperties = customLogicToApplyProperties,
            pageSize = pageSize,
            clientConfig = clientConfig,
            onResponseBody = onResponseBody,
        )
        val newRenderables = fresh.toList()
        clearRenderables()
        addAllRenderables(newRenderables)
    }

    companion object {
        const val SERVICE_TYPE = "WFS"
    }
}
