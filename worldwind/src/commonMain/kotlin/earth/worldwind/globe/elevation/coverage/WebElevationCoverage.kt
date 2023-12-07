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
     * The technical name of the desired coverage
     */
    val coverageName: String
    /**
     * Expected data output format
     */
    val outputFormat: String
}