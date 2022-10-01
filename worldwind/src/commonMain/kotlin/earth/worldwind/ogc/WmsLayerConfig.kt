package earth.worldwind.ogc

/**
 * Configuration values for a WMS layer.
 */
class WmsLayerConfig(
    /**
     * The WMS service address used to build Get Map URLs.
     */
    var serviceAddress: String,
    /**
     * The comma-separated list of WMS layer names.
     */
    var layerNames: String
) {
    /**
     * The WMS protocol version. Defaults to 1.3.0.
     */
    var wmsVersion = "1.3.0"
    /**
     * The comma-separated list of WMS style names.
     */
    var styleNames: String? = null
    /**
     * The coordinate reference system to use when requesting layers. Defaults to EPSG:4326.
     */
    var coordinateSystem = "EPSG:4326"
    /**
     * The image content type to use in Get Map requests.
     */
    var imageFormat: String? = null
    /**
     * Indicates whether Get Map requests should include transparency.
     */
    var isTransparent = true
    /**
     * The time parameter to include in Get Map requests.
     */
    var timeString: String? = null
}