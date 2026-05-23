package earth.worldwind.layer

/**
 * Vector-feature counterpart to [WebImageLayer] / [earth.worldwind.globe.elevation.coverage.WebElevationCoverage].
 * Tags a [RenderableLayer] as being backed by an OGC feature service (WFS, OGC API - Features, …)
 * so the GPKG content manager can persist the service association alongside cached
 * feature data and rebuild a service-backed layer when the package is reopened.
 */
interface WebFeatureLayer : Layer {
    /** Identifier of the backing service (e.g. `"WFS"`). */
    val serviceType: String
    /** Base URL of the service endpoint (without query string). */
    val serviceAddress: String
    /** Cached GetCapabilities XML, if available, so subsequent loads can avoid the network. */
    val serviceMetadata: String? get() = null
    /** The technical name of the feature type / collection on the server. */
    val layerName: String
    /** Output MIME advertised by the server for fetched features (e.g. `application/json`). */
    val outputFormat: String
}
