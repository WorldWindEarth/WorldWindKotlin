package earth.worldwind.layer.cache

/**
 * Opt-in marker for low-level cache plumbing on [earth.worldwind.util.ContentManager].
 *
 * Methods like `openImageTileStore`, `openVectorTileStore`, `openFeatureStore`,
 * `createElevationSourceFactory`, and `registerWebService` are part of the platform-impl
 * contract — every [earth.worldwind.util.ContentManager] backend (GeoPackage, IndexedDB,
 * iOS filesystem) must provide them. App code should not call them directly.
 *
 * The supported app-facing paths are:
 *   * Writing: build a layer with its OGC factory, then call
 *     [earth.worldwind.layer.cache.register].
 *   * Reading: [earth.worldwind.util.openLayer] / [earth.worldwind.util.openLayers] /
 *     [earth.worldwind.util.openElevationCoverage] /
 *     [earth.worldwind.util.openElevationCoverages].
 *
 * Annotate with `@OptIn(LowLevelCacheApi::class)` to acknowledge the low-level use site.
 * The warning serves as a signal that the canonical path may be a better fit.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Low-level cache API. Prefer ContentManager.attachCache + openLayer / openElevationCoverage.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
annotation class LowLevelCacheApi
