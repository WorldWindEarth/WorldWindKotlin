package earth.worldwind.layer

interface WebImageLayer : Layer {
    /**
     * Type of the service
     */
    val serviceType: String
    /**
     * The service address
     */
    val serviceAddress: String
    /**
     * Descriptive information about service capabilities
     */
    val serviceMetadata: String? get() = null
    /**
     * The technical name of the desired layer
     */
    val layerName: String? get() = null
    /**
     * Expected output image format
     */
    val imageFormat: String
    /**
     * Is layer a transparent overlay
     */
    val isTransparent: Boolean
}