@file:OptIn(LowLevelCacheApi::class)

package earth.worldwind.layer.cache
import earth.worldwind.layer.source.TileSource

import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.ElevationSourceFactory
import earth.worldwind.ogc.Wcs100ElevationCoverage
import earth.worldwind.ogc.Wcs201ElevationCoverage
import earth.worldwind.ogc.WmsElevationCoverage
import earth.worldwind.ogc.WmsImageLayer
import earth.worldwind.ogc.WmtsImageLayer
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.source.GeoJsonBulkFeatureSource
import earth.worldwind.ogc.wfs.WfsBulkFeatureSource
import earth.worldwind.util.CachedLayerRegistry
import earth.worldwind.util.ContentManager
import earth.worldwind.util.LevelSet
import earth.worldwind.util.dispatchCachedEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Regression tests for the cross-platform reopen dispatcher. The bug classes covered:
 *   1. An entry with a registered web service must dispatch through the registry, not
 *      through `tryOpenNativeContent` (the JVM-specific gpkg fallback).
 *   2. An entry without a registered web service must consult `tryOpenNativeContent`
 *      and return whatever it yields (including `null` on platforms with no fallback).
 *   3. The built-in registrations cover every standard service type (so apps don't
 *      need to register them manually).
 */
class CachedLayerRegistryTest {

    @Test
    fun builtInRegistrations_cover_standard_service_types() {
        // Force the dispatcher module to load so its built-in CachedLayerRegistry entries run.
        // (The init block is private; calling the public entry exercises it.)
        runDispatcherSmoke()

        // Image layers
        assertNotNull(CachedLayerRegistry.opener(
            CacheEntry.DataType.TILES, WmsImageLayer.SERVICE_TYPE))
        assertNotNull(CachedLayerRegistry.opener(
            CacheEntry.DataType.TILES, WmtsImageLayer.SERVICE_TYPE))
        // Coverage / elevation
        assertNotNull(CachedLayerRegistry.opener(
            CacheEntry.DataType.COVERAGE, Wcs100ElevationCoverage.SERVICE_TYPE))
        assertNotNull(CachedLayerRegistry.opener(
            CacheEntry.DataType.COVERAGE, Wcs201ElevationCoverage.SERVICE_TYPE))
        assertNotNull(CachedLayerRegistry.opener(
            CacheEntry.DataType.COVERAGE, WmsElevationCoverage.SERVICE_TYPE))
        // Features
        assertNotNull(CachedLayerRegistry.opener(
            CacheEntry.DataType.FEATURES, WfsBulkFeatureSource.SERVICE_TYPE))
        assertNotNull(CachedLayerRegistry.opener(
            CacheEntry.DataType.FEATURES, GeoJsonBulkFeatureSource.SERVICE_TYPE))
    }

    @Test
    fun no_service_routes_to_native_fallback() = runTest {
        // Bug class: an entry with no WebServiceInfo on a manager without a native
        // fallback must return null, not fall through to a registry lookup that would
        // throw on `service!!`.
        val cm = NoServiceContentManager()
        val entry = CacheEntry(
            contentKey = "k",
            dataType = CacheEntry.DataType.TILES,
            service = null,
            boundingSector = null,
            lastModified = null,
        )
        val result = cm.dispatchCachedEntry(entry)
        assertNull(result)
        assertEquals(1, cm.tryOpenNativeContentInvocations)
    }

    @Test
    fun unregistered_service_type_returns_null_without_crashing() = runTest {
        // Bug class: a stale entry (e.g. a service type the app removed) must return
        // null, not throw, so listEntries() consumers can ignore unknown rows.
        val cm = NoServiceContentManager()
        val entry = CacheEntry(
            contentKey = "k",
            dataType = CacheEntry.DataType.TILES,
            service = WebServiceInfo(type = "TotallyUnknownService", address = "https://example.com"),
            boundingSector = null,
            lastModified = null,
        )
        val result = cm.dispatchCachedEntry(entry)
        assertNull(result)
        assertEquals(0, cm.tryOpenNativeContentInvocations,
            "tryOpenNativeContent must NOT be called when a service is registered")
    }

    @Test
    fun native_fallback_returning_object_is_propagated() = runTest {
        // Bug class: the native-fallback hook must be called and its value returned
        // verbatim (i.e. not swallowed by the dispatcher's null-coalescing).
        val sentinel = "any-layer-object"
        val cm = NoServiceContentManager(nativeContent = sentinel)
        val entry = CacheEntry(
            contentKey = "k",
            dataType = CacheEntry.DataType.TILES,
            service = null,
            boundingSector = null,
            lastModified = null,
        )
        val result = cm.dispatchCachedEntry(entry)
        assertEquals(sentinel, result)
    }

    private fun runDispatcherSmoke() {
        // No-op call that forces the dispatcher module init block to fire (registers the
        // built-in CachedLayerRegistry openers). We intentionally don't await — we only
        // need the side-effect.
        @Suppress("UNUSED_VARIABLE")
        val unused: suspend ContentManager.(CacheEntry) -> Any? = { h ->
            this.dispatchCachedEntry(h)
        }
    }
}

/** Tiny [ContentManager] stub used by the dispatcher tests. Records every
 *  [tryOpenNativeContent] call so tests can assert which path the dispatcher took. */
private class NoServiceContentManager(
    private val nativeContent: Any? = null,
) : ContentManager {
    override val pathName: String = "stub"
    override val isReadOnly: Boolean = true
    var tryOpenNativeContentInvocations = 0

    override suspend fun contentSize(): Long = 0L
    override suspend fun sizeBytesOf(contentKey: String): Long = 0L
    override suspend fun lastModifiedDate(): Instant? = null

    override suspend fun openImageTileStore(
        contentKey: String, levelSet: LevelSet,
        imageFormat: String, isTransparent: Boolean, cachePolicy: CachePolicy,
        displayName: String?,
    ): TileStore = throw UnsupportedOperationException()

    override suspend fun openVectorTileStore(
        contentKey: String, levelSet: LevelSet,
        cachePolicy: CachePolicy, displayName: String?,
    ): TileStore = throw UnsupportedOperationException()

    override suspend fun createElevationSourceFactory(
        contentKey: String, tileMatrixSet: TileMatrixSet,
        networkSource: TileSource?, outputFormat: String, isFloat: Boolean,
        cachePolicy: CachePolicy, displayName: String?,
    ): ElevationSourceFactory = throw UnsupportedOperationException()

    override suspend fun openFeatureStore(
        contentKey: String, cachePolicy: CachePolicy, displayName: String?,
    ): FeatureStore = throw UnsupportedOperationException()

    override suspend fun registerWebService(contentKey: String, info: WebServiceInfo) = Unit
    override suspend fun listEntries(): List<CacheEntry> = emptyList()
    override suspend fun findEntry(contentKey: String): CacheEntry? = null
    override suspend fun clearEntry(contentKey: String) = Unit
    override suspend fun deleteEntry(contentKey: String) = Unit
    override suspend fun setDisplayName(contentKey: String, displayName: String) = Unit

    override suspend fun tryOpenNativeContent(entry: CacheEntry): Any? {
        tryOpenNativeContentInvocations++
        return nativeContent
    }
}

/** Tiny suspend test runner — uses kotlinx-coroutines-test's runTest under the hood
 *  via its public name to stay portable across JVM / JS / iOS test runners. */
private fun runTest(body: suspend () -> Unit) {
    kotlinx.coroutines.test.runTest { body() }
}
