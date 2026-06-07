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

    /**
     * Output-format heuristic — "what does this format type carry in its bytes": `true` for
     * BIL32 / GeoTIFF / DTED, `false` for BIL16 and other 16-bit-integer formats.
     *
     * **This is not the cache storage layout.** Network output format and cache storage
     * layout are *independent* by design — a coverage may download as 32-bit-float TIFF
     * yet be stored as Int16 in the gpkg (or vice versa). The cache (when present)
     * records its actual `dataType` in `gpkg_2d_gridded_coverage_ancillary`; query
     * `ContentManager.findEntry(contentKey)?.metadata` and cast to
     * `CacheEntry.isFloat` for the authoritative
     * answer. This heuristic is a creation-time default for callers that don't pass an
     * explicit storage choice; it's also a useful UI hint when no cache exists yet.
     */
    val isFloat: Boolean get() = isFloatOutputFormat(outputFormat)

    companion object {
        /** Recognise a 32-bit-float elevation output format. Matches the set the per-platform
         *  decoders treat as float-valued — currently `application/bil32`, `image/tiff` /
         *  `image/geotiff` family, and the `application/dted*` family. Everything else
         *  (`application/bil16`, `image/bil`, etc.) is treated as 16-bit signed integer. */
        fun isFloatOutputFormat(outputFormat: String): Boolean {
            val ct = outputFormat.lowercase()
            return ct == "application/bil32" ||
                ct == "image/tiff" || ct == "image/geotiff" ||
                ct == "application/geotiff" || ct == "geotiff" || ct == "tiff" ||
                ct.startsWith("application/dted")
        }
    }
}