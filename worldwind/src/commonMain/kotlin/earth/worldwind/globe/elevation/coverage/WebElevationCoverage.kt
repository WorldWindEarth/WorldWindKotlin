package earth.worldwind.globe.elevation.coverage

interface WebElevationCoverage : ElevationCoverage {
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
     * The technical name of the desired coverage
     */
    val coverageName: String
    /**
     * Expected data output format
     */
    val outputFormat: String
}