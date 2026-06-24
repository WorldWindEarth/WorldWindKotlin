package earth.worldwind.formats.gpkg

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.globe.elevation.coverage.TiledElevationCoverage
import earth.worldwind.layer.BulkFeatureLayer
import earth.worldwind.layer.TiledImageLayer
import earth.worldwind.layer.cache.CacheEntry
import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.cache.CachedTiledFeatureSource
import earth.worldwind.layer.cache.GpkgFeatureStore
import earth.worldwind.util.LevelSet
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import earth.worldwind.layer.source.TileBlob
import earth.worldwind.layer.source.TileSource
import mil.nga.geopackage.validate.GeoPackageValidate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackage as NgaGeoPackage
import mil.nga.geopackage.db.GeoPackageDataType
import mil.nga.geopackage.features.columns.GeometryColumns
import mil.nga.geopackage.extension.nga.index.FeatureTableIndex
import mil.nga.geopackage.extension.rtree.RTreeIndexExtension
import mil.nga.geopackage.features.index.FeatureIndexManager
import mil.nga.geopackage.features.index.FeatureIndexType
import mil.nga.geopackage.features.user.FeatureColumn
import mil.nga.geopackage.features.user.FeatureTableMetadata
import mil.nga.geopackage.geom.GeoPackageGeometryData
import mil.nga.sf.GeometryType
import mil.nga.sf.Point
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validates the step-2 generic feature reader: a standard GeoPackage features table written by a
 * foreign tool (here: the raw NGA API, no WorldWind columns) reads back through WorldWind with its
 * typed attributes preserved as properties JSON — and a WorldWind-cache table still round-trips its
 * own feature_properties column unchanged.
 */
class ForeignFeatureReadTest {
    private val file = File.createTempFile("foreign-features", ".gpkg").also { it.delete() }

    @AfterTest fun cleanup() { file.delete() }

    @Test
    fun readsForeignFeaturesTableWithTypedAttributes() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            // A standard features table as ArcGIS/QGIS would export it: geometry + typed columns,
            // no tile_z/x/y, no feature_properties, no last_modified.
            createForeignFeaturesTable(gpkg.core as NgaGeoPackage, "cities")

            val content = assertNotNull(gpkg.getContent("cities"), "foreign features content missing")
            val rows = gpkg.readCachedFeatures(content)

            assertEquals(1, rows.size)
            val (geometry, propsJson) = rows[0]
            val point = assertIs<Point>(geometry)
            assertEquals(-89.65, point.x, 1e-9)
            assertEquals(39.78, point.y, 1e-9)

            val props = Json.parseToJsonElement(assertNotNull(propsJson)).jsonObject
            assertEquals("Springfield", props["name"]!!.jsonPrimitive.content)
            assertEquals(116250L, props["population"]!!.jsonPrimitive.long)
            assertEquals(156.8, props["area"]!!.jsonPrimitive.double, 1e-9)
            // The PK and geometry column must not leak into properties.
            assertFalse(props.containsKey("geom"))
            assertFalse(props.containsKey("id"))
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun readsWorldWindCacheFeaturePropertiesUnchanged() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val content = gpkg.setupFeaturesContent("ww_cache")
            val json = """{"name":"WorldWind","n":5}"""
            gpkg.writeFeatureTile(content, 1, 2, 3, listOf(GpkgFeatureRow(Point(-89.65, 39.78), json)))

            val rows = gpkg.readCachedFeatures(content)
            assertEquals(1, rows.size)
            // WorldWind cache path reads the stored feature_properties JSON verbatim.
            assertEquals(json, rows[0].second)
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun createsSpatialIndexKeptInSyncAndQueryableByBoundingBox() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val content = gpkg.setupFeaturesContent("indexed")
            val nga = gpkg.core as NgaGeoPackage
            val dao = nga.getFeatureDao("indexed")

            // setupFeaturesContent created the standard RTree spatial index.
            assertTrue(RTreeIndexExtension(nga).has(dao.table), "RTree index not created")

            // A feature written through the normal cache path must land in the RTree (sync triggers).
            gpkg.writeFeatureTile(content, 0, 0, 0, listOf(GpkgFeatureRow(Point(10.0, 20.0), """{"k":1}""")))

            val fim = FeatureIndexManager(nga, dao).apply { indexLocation = FeatureIndexType.RTREE }
            try {
                fun countInBox(minX: Double, minY: Double, maxX: Double, maxY: Double): Int {
                    val results = fim.query(BoundingBox(minX, minY, maxX, maxY))
                    return try {
                        var n = 0
                        val iter = results.iterator()
                        while (iter.hasNext()) { iter.next(); n++ }
                        n
                    } finally {
                        results.close()
                    }
                }
                assertEquals(1, countInBox(9.0, 19.0, 11.0, 21.0))
                assertEquals(0, countInBox(100.0, 100.0, 101.0, 101.0))
            } finally {
                fim.close()
            }
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun alsoBuildsGeometryIndexExtensionForCrossPlatformReads() = runBlocking {
        // Android's framework SQLite can't host the OGC RTree, so a JVM-authored cache must ALSO
        // carry the NGA Geometry Index Extension for an Android (non-RTree) reader. Verify both
        // write paths populate it AND keep it in sync on re-fetch, queried explicitly via
        // FeatureIndexType.GEOPACKAGE (the nga_geometry_index) — not the RTree.
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val nga = gpkg.core as NgaGeoPackage

            // Exactly the read path the Android actual uses: FeatureTableIndex.queryFeatures over
            // the nga_geometry_index (the OGC RTree is absent on Android, so it never participates).
            fun geometryIndexCount(table: String, minX: Double, minY: Double, maxX: Double, maxY: Double): Int {
                val results = FeatureTableIndex(nga, nga.getFeatureDao(table))
                    .queryFeatures(BoundingBox(minX, minY, maxX, maxY))
                return try {
                    var c = 0
                    val it = results.iterator()
                    while (it.hasNext()) { it.next(); c++ }
                    c
                } finally {
                    results.close()
                }
            }

            // Bulk write path (insertCachedFeatures -> index(true)).
            gpkg.setupFeaturesContent("bulk")
            insertCachedFeatures(gpkg.core, "bulk", listOf(Point(30.0, 40.0) to """{"k":"b"}"""))
            assertEquals(1, geometryIndexCount("bulk", 29.0, 39.0, 31.0, 41.0))

            // Tiled, uid-keyed write path (writeFeatureTileFlat -> index(row)).
            val tiled = gpkg.setupFeaturesContent("tiled")
            gpkg.writeFeatureTile(tiled, 0, 0, 0, listOf(
                GpkgFeatureRow(Point(10.0, 20.0), """{"k":"keep"}""", uid = "keep"),
                GpkgFeatureRow(Point(12.0, 22.0), """{"k":"gone"}""", uid = "gone"),
            ))
            assertEquals(2, geometryIndexCount("tiled", 9.0, 19.0, 13.0, 23.0))

            // Re-fetch the tile without "gone" — deleteIndex must drop it from the Geometry Index too.
            gpkg.writeFeatureTile(tiled, 0, 0, 0, listOf(
                GpkgFeatureRow(Point(10.0, 20.0), """{"k":"keep"}""", uid = "keep"),
            ))
            assertEquals(1, geometryIndexCount("tiled", 9.0, 19.0, 13.0, 23.0))
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun readsFeaturesByBoundingBoxViaRTree() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val content = gpkg.setupFeaturesContent("bbox")
            gpkg.writeFeatureTile(content, 0, 0, 0, listOf(GpkgFeatureRow(Point(10.0, 20.0), """{"k":"near"}""")))
            gpkg.writeFeatureTile(content, 1, 1, 1, listOf(GpkgFeatureRow(Point(120.0, 60.0), """{"k":"far"}""")))

            // A window around the first point returns only it; geometry + properties preserved.
            val near = readFeaturesInBoundingBox(gpkg.core, "bbox", 9.0, 19.0, 11.0, 21.0)
            assertEquals(1, near.size)
            assertEquals(10.0, assertIs<Point>(near[0].first).x, 1e-9)
            assertTrue(near[0].second!!.contains("near"))

            // A window covering both returns both.
            assertEquals(2, readFeaturesInBoundingBox(gpkg.core, "bbox", -180.0, -90.0, 180.0, 90.0).size)
            // A window covering neither returns nothing.
            assertEquals(0, readFeaturesInBoundingBox(gpkg.core, "bbox", -10.0, -10.0, -5.0, -5.0).size)
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun storeReadsViaCoverageAndUpsertsByUid() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val content = gpkg.setupFeaturesContent("osm")
            val store = GpkgFeatureStore(gpkg, content)

            // Miss: no coverage row yet (distinct from a fetched-but-empty tile).
            assertNull(store.readTile(0, 0, 0))

            // Write one OSM-style feature (stable "id") in tile (0,0,0), whose bbox holds its point.
            store.writeTile(0, 0, 0, flowOf(
                CachedFeatureRow(CachedGeometry.Point(10.0, 20.0), """{"id":"way/1","name":"A"}""")
            ))
            assertEquals(1, store.readTile(0, 0, 0)?.toList()?.size, "hit should serve the feature")

            // Re-fetch the same tile, same uid, updated payload → upsert dedupes to one row.
            store.writeTile(0, 0, 0, flowOf(
                CachedFeatureRow(CachedGeometry.Point(10.0, 20.0), """{"id":"way/1","name":"B"}""")
            ))
            val after = store.readTile(0, 0, 0)?.toList()
            assertEquals(1, after?.size, "same uid must not duplicate")
            assertTrue(after!![0].properties!!.contains("\"B\""), "row should carry the updated payload")

            // Negative cache: an empty fetch records is_empty → readTile is an empty flow, not null.
            store.writeTile(1, 1, 1, emptyFlow())
            val negative = store.readTile(1, 1, 1)
            assertNotNull(negative, "fetched-empty tile is a hit, not a miss")
            assertEquals(0, negative.toList().size)
            // ...and it didn't disturb the feature in the other tile.
            assertEquals(1, store.readTile(0, 0, 0)?.toList()?.size)
        } finally {
            gpkg.shutdown()
        }
    }

    /** End-to-end #1 (foreign → WorldWind): a standard GeoPackage features table written by a
     *  foreign tool opens through the real content-manager native path into a renderable layer. */
    @Test
    fun foreignFeaturesOpenEndToEndThroughContentManager() = runBlocking {
        run {
            val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
            try { createForeignFeaturesTable(gpkg.core as NgaGeoPackage, "cities") } finally { gpkg.shutdown() }
        }
        val manager = GpkgContentManager(file.absolutePath, isReadOnly = true)
        try {
            val entry = assertNotNull(manager.findEntry("cities"), "foreign features entry not discovered")
            assertEquals(CacheEntry.DataType.FEATURES, entry.dataType)
            val layer = manager.tryOpenNativeContent(entry)
            assertEquals(1, assertIs<BulkFeatureLayer>(layer).count, "foreign feature should render")
        } finally {
            manager.close()
        }
    }

    /** End-to-end #2 (WorldWind → external): a feature cache written through the store reopens and
     *  renders through the same native path a foreign GIS file would — proving it's a standard,
     *  self-describing features layer, not a private schema. */
    @Test
    fun worldWindFeatureCacheReopensAndRendersThroughContentManager() = runBlocking {
        run {
            val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
            try {
                val content = gpkg.setupFeaturesContent("osm")
                GpkgFeatureStore(gpkg, content).writeTile(0, 0, 0, flowOf(
                    CachedFeatureRow(CachedGeometry.Point(10.0, 20.0), """{"id":"way/1","name":"Tower"}""")
                ))
            } finally { gpkg.shutdown() }
        }
        val manager = GpkgContentManager(file.absolutePath, isReadOnly = true)
        try {
            val entry = assertNotNull(manager.findEntry("osm"), "features entry missing on reopen")
            assertEquals(CacheEntry.DataType.FEATURES, entry.dataType)
            val layer = manager.tryOpenNativeContent(entry)
            assertEquals(1, assertIs<BulkFeatureLayer>(layer).count, "cached feature should render on reopen")
        } finally {
            manager.close()
        }
    }

    @Test
    fun evictionDropsWholeOldestTilesOverlapSafe() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val content = gpkg.setupFeaturesContent("osm")
            val store = GpkgFeatureStore(gpkg, content, CachePolicy(maxEntries = 1L))
            // Two non-overlapping tiles (opposite corners of the world), one feature each.
            store.writeTile(2, 0, 0, flowOf(CachedFeatureRow(CachedGeometry.Point(-135.0, 75.0), """{"id":"way/A"}""")))
            store.writeTile(2, 3, 3, flowOf(CachedFeatureRow(CachedGeometry.Point(135.0, -75.0), """{"id":"way/B"}""")))
            assertEquals(1, store.readTile(2, 0, 0)?.toList()?.size)
            assertEquals(1, store.readTile(2, 3, 3)?.toList()?.size)

            store.evict() // maxEntries=1 → keep newest coverage tile, evict the oldest as a whole

            assertNull(store.readTile(2, 0, 0), "evicted tile's coverage is gone → miss")
            assertEquals(1, store.readTile(2, 3, 3)?.toList()?.size, "retained tile keeps its feature")
            assertEquals(1, store.readAll().toList().size, "evicted tile's feature is deleted from the flat store")
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun cachedSourceRendersBoundaryFeatureInExactlyOneTile() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val content = gpkg.setupFeaturesContent("osm")
            val store = GpkgFeatureStore(gpkg, content)
            // A polygon straddling lon=0 (the boundary between tiles (1,0,0) and (1,1,0)); its
            // envelope center is at lon=-2.5 → owned by the western tile.
            val ring = CachedGeometry.LineString.of(listOf(
                CachedGeometry.Point(-10.0, 40.0), CachedGeometry.Point(5.0, 40.0),
                CachedGeometry.Point(5.0, 50.0), CachedGeometry.Point(-10.0, 50.0),
            ))
            val feature = CachedFeatureRow(CachedGeometry.Polygon(listOf(ring)), """{"id":"way/9"}""")
            // Both neighbouring tiles fetch the straddling feature (same uid → one stored row).
            store.writeTile(1, 0, 0, flowOf(feature))
            store.writeTile(1, 1, 0, flowOf(feature))
            assertEquals(1, store.readAll().toList().size, "uid dedup: one stored row")

            // The store returns it for both tiles' bbox...
            assertEquals(1, store.readTile(1, 0, 0)?.toList()?.size)
            assertEquals(1, store.readTile(1, 1, 0)?.toList()?.size)

            // ...but the cached source assigns it to exactly one tile by envelope-center ownership.
            val source = CachedTiledFeatureSource(inner = null, store = store)
            val west = Sector.fromDegrees(0.0, -180.0, 85.0511, 180.0) // tile (1,0,0)
            val east = Sector.fromDegrees(0.0, 0.0, 85.0511, 180.0)    // tile (1,1,0)
            assertEquals(1, source.tryReadCachedTile(1, 0, 0, west)?.toList()?.size, "owner renders it")
            assertEquals(0, source.tryReadCachedTile(1, 1, 0, east)?.toList()?.size, "neighbor must not double-render")
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun reFetchRemovesVanishedFeatures() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val content = gpkg.setupFeaturesContent("osm")
            val store = GpkgFeatureStore(gpkg, content)
            // Two features the tile owns.
            store.writeTile(0, 0, 0, flowOf(
                CachedFeatureRow(CachedGeometry.Point(10.0, 20.0), """{"id":"way/1"}"""),
                CachedFeatureRow(CachedGeometry.Point(30.0, 40.0), """{"id":"way/2"}"""),
            ))
            assertEquals(2, store.readAll().toList().size)

            // Re-fetch the same tile; way/2 was deleted upstream and isn't reported anymore.
            store.writeTile(0, 0, 0, flowOf(CachedFeatureRow(CachedGeometry.Point(10.0, 20.0), """{"id":"way/1"}""")))
            assertEquals(1, store.readAll().toList().size, "vanished feature removed on re-fetch")
        } finally {
            gpkg.shutdown()
        }
    }

    @Test
    fun legacyWebServiceTableMigratesOnWritableOpen() = runBlocking {
        // A legacy cache: a gpkg_web_service table (old reserved-prefix name) with one row, as a
        // prior WorldWind version wrote it.
        run {
            val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
            try {
                gpkg.core.database.execSQL(
                    "CREATE TABLE gpkg_web_service (table_name TEXT NOT NULL PRIMARY KEY, " +
                        "service_type TEXT NOT NULL, service_address TEXT NOT NULL, service_metadata TEXT, " +
                        "layer_name TEXT, output_format TEXT, is_transparent SMALLINT DEFAULT 0)"
                )
                gpkg.core.database.execSQL(
                    "INSERT INTO gpkg_web_service (table_name, service_type, service_address) " +
                        "VALUES ('layer1', 'WMS', 'https://example.com/wms')"
                )
            } finally { gpkg.shutdown() }
        }
        // Reopen writable → the rename migration runs at construction; the binding survives under the new name.
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val ws = assertNotNull(gpkg.getWebService("layer1"), "web service preserved after migration")
            assertEquals("WMS", ws.type)
            assertEquals("https://example.com/wms", ws.address)
        } finally { gpkg.shutdown() }
    }

    @Test
    fun legacyWebServiceTableNotMigratedWhenReadOnly() = runBlocking {
        run {
            val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
            try {
                gpkg.core.database.execSQL(
                    "CREATE TABLE gpkg_web_service (table_name TEXT NOT NULL PRIMARY KEY, " +
                        "service_type TEXT NOT NULL, service_address TEXT NOT NULL, service_metadata TEXT, " +
                        "layer_name TEXT, output_format TEXT, is_transparent SMALLINT DEFAULT 0)"
                )
                gpkg.core.database.execSQL(
                    "INSERT INTO gpkg_web_service (table_name, service_type, service_address) " +
                        "VALUES ('layer1', 'WMS', 'https://example.com/wms')"
                )
            } finally { gpkg.shutdown() }
        }
        // Read-only open: the file can't be migrated, and the layer simply opens offline (no rebind).
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = true)
        try {
            assertNull(gpkg.getWebService("layer1"), "read-only legacy cache opens offline, no migration")
        } finally { gpkg.shutdown() }
    }

    @Test
    fun tilePyramidReopensThroughContentManager() = runBlocking {
        // Produce a standard raster tile pyramid (EPSG:4326), then reopen it offline through the
        // content manager — the round trip an external tool would also make.
        run {
            val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
            try {
                val world = Sector.fromDegrees(-90.0, -180.0, 180.0, 360.0)
                val content = gpkg.setupTilesContent("tiles", LevelSet(world, world, Location.fromDegrees(90.0, 90.0), 3, 256, 256))
                gpkg.writeTileUserData(content, 0, 0, 0, ByteArray(64))
            } finally { gpkg.shutdown() }
        }
        val manager = GpkgContentManager(file.absolutePath, isReadOnly = true)
        try {
            val entry = assertNotNull(manager.findEntry("tiles"), "tile entry not discovered")
            assertIs<TiledImageLayer>(manager.tryOpenNativeContent(entry), "tiles must reopen as a tiled image layer")
            assertEquals(CacheEntry.DataType.TILES, entry.dataType)
        } finally { manager.close() }
    }

    @Test
    fun griddedCoverageReopensThroughContentManager() = runBlocking {
        // Produce a standard OGC Tiled-Gridded-Coverage, then reopen it offline through the content manager.
        run {
            val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
            try {
                val world = Sector.fromDegrees(-90.0, -180.0, 180.0, 360.0)
                val tms = TileMatrixSet.fromTilePyramid(world, 2, 1, 256, 256, Angle.fromDegrees(0.3))
                gpkg.setupGriddedCoverageContent("dem", tms, isFloat = false)
            } finally { gpkg.shutdown() }
        }
        val manager = GpkgContentManager(file.absolutePath, isReadOnly = true)
        try {
            val entry = assertNotNull(manager.findEntry("dem"), "coverage entry not discovered")
            assertIs<TiledElevationCoverage>(manager.tryOpenNativeContent(entry), "coverage must reopen as an elevation coverage")
            assertEquals(CacheEntry.DataType.COVERAGE, entry.dataType)
        } finally { manager.close() }
    }

    @Test
    fun producedGeoPackageValidatesAgainstNgaReferenceValidator() = runBlocking {
        // A multi-content cache: a raster tile pyramid + a features layer (with the ww_* side tables).
        run {
            val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
            try {
                val world = Sector.fromDegrees(-90.0, -180.0, 180.0, 360.0)
                val tiles = gpkg.setupTilesContent("tiles", LevelSet(world, world, Location.fromDegrees(90.0, 90.0), 2, 256, 256))
                gpkg.writeTileUserData(tiles, 0, 0, 0, ByteArray(64))
                val features = gpkg.setupFeaturesContent("places")
                gpkg.writeFeatureTile(features, 0, 0, 0, listOf(GpkgFeatureRow(Point(10.0, 20.0), """{"id":"n/1"}""")))
            } finally { gpkg.shutdown() }
        }
        // Validate with the canonical NGA GeoPackage library's reference spec checks.
        assertTrue(GeoPackageValidate.hasGeoPackageExtension(file), "produced file has the .gpkg extension")
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = true)
        try {
            assertTrue(GeoPackageValidate.hasMinimumTables(gpkg.core), "required GeoPackage tables present")
            GeoPackageValidate.validateMinimumTables(gpkg.core) // throws GeoPackageException if non-conformant
        } finally { gpkg.shutdown() }
    }

    @Test
    fun coverageRevalidationConditionalGetBumpsOn304() = runBlocking {
        val gpkg = GeoPackage(file.absolutePath, isReadOnly = false)
        try {
            val world = Sector.fromDegrees(-90.0, -180.0, 180.0, 360.0)
            val tms = TileMatrixSet.fromTilePyramid(world, 2, 1, 256, 256, Angle.fromDegrees(0.3))
            val content = gpkg.setupGriddedCoverageContent("dem", tms, isFloat = false)
            // A cached coverage tile with a stored ETag and a long-ago freshness stamp.
            val tpudtId = gpkg.writeTileUserData(content, 0, 0, 0, ByteArray(8) { 1 })
            gpkg.writeTileRevalidation(content, tpudtId, etag = "v1", httpLastModified = null, validatedAt = 1000L)

            // A network source that answers 304 (null) to the conditional GET, recording the sent ETag.
            var sentEtag: String? = null
            val source = object : TileSource {
                override suspend fun fetchTile(
                    z: Int, x: Int, y: Int, previousEtag: String?, previousLastModified: String?,
                ): TileBlob? {
                    sentEtag = previousEtag
                    return null // 304 Not Modified
                }
            }
            val perTile = GpkgCachedElevationDataFactory(
                GpkgCachedElevationSourceFactory(
                    geoPackage = gpkg, content = content, networkSource = source,
                    outputFormat = "application/bil16", isFloat = false, tileMatrixSet = tms,
                ),
                0, 0, 0,
            )
            val changed = perTile.conditionalRevalidate("v1", null, tpudtId)

            assertEquals("v1", sentEtag, "the stored ETag was sent as the conditional validator")
            assertFalse(changed, "304 must not change bytes or trigger a redraw")
            assertTrue(
                assertNotNull(gpkg.readTileRevalidation(content, tpudtId)?.validatedAt) > 1000L,
                "validatedAt bumped on 304",
            )
        } finally {
            gpkg.shutdown()
        }
    }

    private fun createForeignFeaturesTable(nga: NgaGeoPackage, tableName: String) {
        val srs = nga.spatialReferenceSystemDao.getOrCreateFromEpsg(4326L)
        val geometryColumns = GeometryColumns().also {
            it.tableName = tableName
            it.columnName = "geom"
            it.geometryType = GeometryType.POINT
            it.srs = srs
            it.z = 0
            it.m = 0
        }
        val columns = listOf(
            FeatureColumn.createColumn("name", GeoPackageDataType.TEXT),
            FeatureColumn.createColumn("population", GeoPackageDataType.INTEGER),
            FeatureColumn.createColumn("area", GeoPackageDataType.DOUBLE),
        )
        val box = BoundingBox(-180.0, -90.0, 180.0, 90.0)
        nga.createFeatureTable(FeatureTableMetadata.create(geometryColumns, columns, box))

        val dao = nga.getFeatureDao(tableName)
        dao.newRow().also { row ->
            row.geometry = GeoPackageGeometryData.create(dao.geometryColumns.srsId, Point(-89.65, 39.78))
            row.setValue("name", "Springfield")
            row.setValue("population", 116250L)
            row.setValue("area", 156.8)
            dao.insert(row)
        }
    }
}
