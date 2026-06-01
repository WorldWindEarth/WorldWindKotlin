package earth.worldwind.formats.gpkg

import com.j256.ormlite.dao.BaseDaoImpl
import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import com.j256.ormlite.table.DatabaseTableConfig
import com.j256.ormlite.table.TableUtils
import earth.worldwind.MR
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import earth.worldwind.shape.Placemark
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh
import mil.nga.color.Color
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.contents.Contents
import mil.nga.geopackage.contents.ContentsDataType
import mil.nga.geopackage.db.DateConverter
import mil.nga.geopackage.db.GeoPackageDataType
import mil.nga.geopackage.extension.ExtensionScopeType
import mil.nga.geopackage.extension.WebPExtension
import mil.nga.geopackage.extension.coverage.CoverageDataCore
import mil.nga.geopackage.extension.coverage.CoverageDataImage
import mil.nga.geopackage.extension.coverage.CoverageDataRequest
import mil.nga.geopackage.extension.coverage.CoverageDataResults
import mil.nga.geopackage.extension.coverage.GriddedCoverageDataType
import mil.nga.geopackage.extension.im.vector_tiles.VectorTilesEncodingExtension
import mil.nga.geopackage.features.columns.GeometryColumns
import mil.nga.geopackage.features.user.FeatureColumn
import mil.nga.geopackage.features.user.FeatureTableMetadata
import mil.nga.geopackage.persister.DatePersister
import mil.nga.geopackage.tiles.user.TileTable
import mil.nga.proj.ProjectionConstants
import mil.nga.sf.*
import java.util.*
import kotlin.math.*
import mil.nga.geopackage.extension.Extensions as GpkgExtension
import mil.nga.geopackage.extension.coverage.GriddedCoverage as GpkgGriddedCoverage
import mil.nga.geopackage.extension.coverage.GriddedTile as GpkgGriddedTile
import mil.nga.geopackage.tiles.matrix.TileMatrix as GpkgTileMatrix
import mil.nga.geopackage.tiles.matrixset.TileMatrixSet as GpkgTileMatrixSet

typealias GpkgContent = Contents

expect abstract class CoverageData<TImage: CoverageDataImage>: CoverageDataCore<TImage> {
    override fun getValues(prequest: CoverageDataRequest, width: Int?, height: Int?): CoverageDataResults
    override fun getValuesUnbounded(request: CoverageDataRequest): CoverageDataResults
}

expect class StyleRow {
    fun getColor(): Color?
    fun getFillColor(): Color?
    fun getWidth(): Double?
}

expect class IconRow {
    fun getAnchorU(): Double?
    fun getAnchorV(): Double?
}

expect class FeatureStyle {
    fun getStyle(): StyleRow?
    fun getIcon(): IconRow?
}

expect fun openOrCreateGeoPackage(pathName: String, isReadOnly: Boolean): GeoPackageCore
expect fun createCoverageData(
    geoPackage: GeoPackageCore, tableName: String, identifier: String?, contentsBoundingBox: BoundingBox?,
    contentsSrsId: Long, tileBoundingBox: BoundingBox?, tileSrsId: Long, isFloat: Boolean
): CoverageData<*>
/**
 * Read cached features written by the WFS cache pipeline — each row carries a geometry
 * plus a JSON string of the feature's original properties so callers can re-apply their
 * styling logic on replay. Wraps the per-platform cursor-vs-iterable difference.
 */
expect fun readCachedFeaturesWithProperties(
    geoPackage: GeoPackageCore, tableName: String,
): List<Pair<Geometry, String?>>

/**
 * Serialize a foreign (non-WorldWind) features row's attribute columns into the flat JSON object
 * that [earth.worldwind.layer.source.CachedFeatureRow.properties] carries, so external GeoPackage
 * features (ArcGIS / QGIS exports) read into WorldWind with their attributes intact. WorldWind's
 * own caches keep a single `feature_properties` JSON column and bypass this. Returns null for an
 * attribute-less row (treated as an empty property map downstream).
 */
fun foreignFeaturePropertiesJson(columnValues: Map<String, Any?>): String? {
    if (columnValues.isEmpty()) return null
    return JsonObject(columnValues.mapValues { (_, v) -> v.toFeatureJsonElement() }).toString()
}

private fun Any?.toFeatureJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}
/**
 * Insert pre-built `(geometry, propertiesJson)` pairs into the WFS cache features table.
 * Caller must hold an open transaction or accept per-row commits.
 */
expect fun insertCachedFeatures(
    geoPackage: GeoPackageCore, tableName: String, rows: List<Pair<Geometry, String?>>,
)
/**
 * Wipe the WFS cache features table (delete all rows, keep schema). Implemented per
 * platform because Android uses a different DAO truncation path.
 */
expect fun truncateFeatureTable(geoPackage: GeoPackageCore, tableName: String)

/**
 * Create the standard GeoPackage RTree spatial index (`gpkg_rtree_index` extension) on
 * [tableName]'s geometry column, so the features table is spatially queryable — by any external GIS
 * and by WorldWind's bounding-box reads. NGA wires the virtual table + sync triggers + the
 * gpkg_extensions registration. Idempotent. Implemented per platform (NGA's RTree extension).
 */
expect fun createFeatureSpatialIndex(geoPackage: GeoPackageCore, tableName: String)

/**
 * Read every feature whose geometry intersects the bounding box (min/max lon/lat in the geometry
 * column's SRS) via the RTree spatial index — the flat-store query that replaces `tile_z/x/y`
 * filtering. Same `(geometry, propertiesJson)` shape and generic property handling as
 * [readCachedFeaturesWithProperties], so it serves WorldWind caches and foreign tables alike.
 */
expect fun readFeaturesInBoundingBox(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<Pair<Geometry, String?>>

/** Feature-row ids whose geometry intersects the bounding box, via the platform spatial index.
 *  Used by tile writes (bbox-replace) and overlap-safe eviction. */
expect fun featureIdsInBoundingBox(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
): List<Long>

/** On a uid-keyed re-fetch, delete features the tile bbox `[minX,minY,maxX,maxY]` OWNS (geometry
 *  envelope-center inside the tile) whose uid is not in [keepUids] — i.e. genuinely vanished
 *  upstream. Queries through the platform spatial index (OGC RTree on JVM, NGA Geometry Index
 *  Extension on Android — Android's SQLite can't host the RTree) and deletes via the DAO so the
 *  index stays in sync. No-op when [keepUids] is empty. */
expect fun deleteVanishedOwnedFeatures(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
    keepUids: Set<String>,
)

expect fun buildImageSource(iconRow: IconRow): ImageSource

/**
 * One row in a features cache table. Null [geometry] is the sentinel that marks a tile as
 * "fetched but empty"; [properties] is whatever JSON the caller chose to store.
 */
data class GpkgFeatureRow(val geometry: Geometry?, val properties: String?, val uid: String? = null)

/**
 * Write tile features into the flat store, within the geographic bounds (min/max lon-lat) that
 * delimit the tile's region. When [upsertByUid] each row's [GpkgFeatureRow.uid] replaces any row
 * with the same uid (DELETE-by-uid then INSERT); otherwise every feature intersecting the bbox is
 * deleted first (bbox-replace). An empty [rows] just clears the bbox (negative-cache region). No
 * `tile_z/x/y` or `last_modified` — those left with the flat schema.
 */
expect fun writeFeatureTileFlat(
    geoPackage: GeoPackageCore, tableName: String,
    minX: Double, minY: Double, maxX: Double, maxY: Double,
    rows: List<GpkgFeatureRow>, upsertByUid: Boolean,
)

open class GeoPackage(val pathName: String, val isReadOnly: Boolean = true) {
    private val geoPackage = openOrCreateGeoPackage(pathName, isReadOnly)
    /**
     * Underlying NGA `GeoPackageCore` instance. Exposed for extension plumbing (e.g.
     * constructing a `VectorTilesMapboxExtension` against this database) — prefer the
     * higher-level helpers on this class for everyday CRUD.
     */
    val core: GeoPackageCore get() = geoPackage
    private val connectionSource = geoPackage.database.connectionSource
    private val srsDao = geoPackage.spatialReferenceSystemDao
    private val contentDao = geoPackage.contentsDao
    private val webServiceDao: Dao<GpkgWebService, String> = DaoManager.createDao(connectionSource, GpkgWebService::class.java)
    private val tileRevalidationDao: Dao<GpkgTileRevalidation, String> =
        DaoManager.createDao(connectionSource, GpkgTileRevalidation::class.java)
    private val featureCoverageDao: Dao<GpkgFeatureCoverage, Long> =
        DaoManager.createDao(connectionSource, GpkgFeatureCoverage::class.java)
    private val tileMatrixSetDao = geoPackage.tileMatrixSetDao
    private val tileMatrixDao = geoPackage.tileMatrixDao
    private val extensionDao = geoPackage.extensionsDao
    private val griddedCoverageDao = CoverageDataCore.getGriddedCoverageDao(geoPackage)
    private val griddedTileDao = CoverageDataCore.getGriddedTileDao(geoPackage)
    private val tileUserDataDao = mutableMapOf<String, Dao<GpkgTileUserData, Int>>()
    private val tileMatrixCache = mutableMapOf<String, Map<Int, GpkgTileMatrix>>()
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1) // Single thread dispatcher

    init {
        // On a writable open, migrate the legacy gpkg_web_service table off the reserved gpkg_ prefix.
        if (!isReadOnly) runCatching { migrateLegacyWebServiceTable() }.onFailure {
            logMessage(WARN, "GeoPackage", "init", "Legacy web-service table migration skipped: ${it.message}")
        }
    }

    val isShutdown get() = !connectionSource.isOpen("")

    fun shutdown() = geoPackage.close().also {
        tileUserDataDao.clear()
        tileMatrixCache.clear()
    }

    suspend fun getContent(tableName: String): GpkgContent? = withContext(Dispatchers.IO) {
        if (contentDao.isTableExists) contentDao.queryForId(tableName) else null
    }

    suspend fun getContent(dataType: String, tableNames: List<String>?): List<GpkgContent> = withContext(Dispatchers.IO) {
        if (contentDao.isTableExists) {
            val builder = contentDao.queryBuilder()
            val where = builder.where().eq(GpkgContent.COLUMN_DATA_TYPE, dataType)
            if (tableNames != null) where.and().`in`(GpkgContent.COLUMN_TABLE_NAME, tableNames)
            where.query()
        } else emptyList()
    }

    suspend fun getWebService(content: GpkgContent): GpkgWebService? = getWebService(content.tableName)

    /**
     * Direct web-service lookup by table name. Does NOT require a [GpkgContent] row to
     * exist — capabilities metadata is independent of any tile/feature pyramid being
     * provisioned for the same key, so this query stays cheap and unconditional.
     */
    suspend fun getWebService(tableName: String): GpkgWebService? = withContext(Dispatchers.IO) {
        if (webServiceDao.isTableExists) {
            webServiceDao.queryBuilder().where().eq(GpkgWebService.COLUMN_TABLE_NAME, tableName).queryForFirst()
        } else null
    }

    suspend fun getGriddedCoverage(content: GpkgContent): GpkgGriddedCoverage? = withContext(Dispatchers.IO) {
        if (griddedCoverageDao.isTableExists) {
            griddedCoverageDao.queryBuilder()
                .where().eq(GpkgGriddedCoverage.COLUMN_TILE_MATRIX_SET_NAME, content.tableName).queryForFirst()
        } else null
    }

    suspend fun getExtension(
        tableName: String, columnName: String, extensionName: String
    ): GpkgExtension? = withContext(Dispatchers.IO) {
        if (extensionDao.isTableExists){
            extensionDao.queryBuilder().where().eq(GpkgExtension.COLUMN_TABLE_NAME, tableName)
                .and().eq(GpkgExtension.COLUMN_COLUMN_NAME, columnName).and().eq(GpkgExtension.COLUMN_EXTENSION_NAME, extensionName)
                .queryForFirst()
        } else null
    }

    suspend fun readFeaturesDataSize(tableName: String) = withContext(Dispatchers.IO) {
        var result = 0L
        val dao = geoPackage.geometryColumnsDao
        if (dao.isTableExists) {
            dao.queryBuilder().selectColumns(GeometryColumns.COLUMN_COLUMN_NAME)
                .where().eq(GeometryColumns.COLUMN_TABLE_NAME, tableName)
                .queryForFirst()?.columnName?.let { columnName ->
                    if (geoPackage.featureTables.contains(tableName)) {
                        // Geometry alone undercounts tag-heavy sources (OsmBuildings, WFS). A foreign
                        // table has no feature_properties column — drop that term instead of failing.
                        val propsTerm =
                            if (geoPackage.database.columnExists(tableName, FEATURE_PROPERTIES_COLUMN))
                                "+ COALESCE(SUM(LENGTH(\"$FEATURE_PROPERTIES_COLUMN\")), 0) "
                            else ""
                        result = dao.queryRawValue(
                            "SELECT COALESCE(SUM(LENGTH(\"$columnName\")), 0) $propsTerm FROM '$tableName'"
                        )
                    }
                }
        }
        result
    }

    suspend fun readTilesDataSize(tableName: String) = withContext(Dispatchers.IO) {
        val dao = getOrCreateTileUserDataDao(tableName)
        if (dao.isTableExists) dao.queryRawValue("SELECT SUM(LENGTH(tile_data)) FROM '$tableName'") else 0L
    }

    suspend fun readTileUserData(
        content: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int
    ): GpkgTileUserData? = withContext(Dispatchers.IO) {
        getOrCreateTileUserDataDao(content.tableName).queryBuilder().where().eq(GpkgTileUserData.ZOOM_LEVEL, zoomLevel)
            .and().eq(GpkgTileUserData.TILE_COLUMN, tileColumn).and().eq(GpkgTileUserData.TILE_ROW, tileRow)
            .queryForFirst()
    }

    /** Upsert the tile blob for `(z, x, y)` and return its tile-user-data row `id` (the `tpudt_id`
     *  that [writeTileRevalidation] keys freshness by). The id is stable across rewrites — the row
     *  is reused, not re-inserted. */
    @Throws(IllegalStateException::class)
    suspend fun writeTileUserData(
        content: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int, tileData: ByteArray
    ): Long = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile cannot be saved. GeoPackage is read-only!")
        val tileUserData = readTileUserData(content, zoomLevel, tileColumn, tileRow) ?: GpkgTileUserData().also {
            it.zoomLevel = zoomLevel
            it.tileColumn = tileColumn
            it.tileRow = tileRow
        }
        tileUserData.tileData = tileData
        getOrCreateTileUserDataDao(content.tableName).createOrUpdate(tileUserData)
        // Cache freshness (validated_at) rides in the ww_tile_revalidation side table, not a
        // column on this OGC tile-user-data table — callers stamp it via writeTileRevalidation.
        // Update content last modified date
        content.lastChange = Date()
        contentDao.update(content)
        tileUserData.id
    }

    /** The tile-user-data row `id` (tpudt_id) for `(z, x, y)`, or null if not cached. Selects only
     *  the id — never loads the tile blob — so the SWR freshness check stays cheap. */
    suspend fun readTileUserDataId(
        content: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int,
    ): Long? = withContext(Dispatchers.IO) {
        val dao = getOrCreateTileUserDataDao(content.tableName)
        if (!dao.isTableExists) return@withContext null
        dao.queryBuilder().selectColumns(GpkgTileUserData.ID).where()
            .eq(GpkgTileUserData.ZOOM_LEVEL, zoomLevel)
            .and().eq(GpkgTileUserData.TILE_COLUMN, tileColumn)
            .and().eq(GpkgTileUserData.TILE_ROW, tileRow)
            .queryForFirst()?.id
    }

    /**
     * Evict image/elevation/vector tiles per [policy]. Capacity-only: [CachePolicy.staleAfter]
     * never deletes (stale tiles refresh in place via SWR). [CachePolicy.maxEntries] (row count)
     * drops oldest-inserted rows first (by `id`, the autoincrement PK), one row per tile so a tile
     * is never split. No-op when read-only or unbounded.
     */
    suspend fun evictTiles(
        content: GpkgContent, policy: CachePolicy,
    ) = withContext(writeDispatcher) {
        if (isReadOnly || policy.isUnbounded) return@withContext
        val escapedTable = content.tableName.replace("\"", "\"\"")
        val q = "\"$escapedTable\""
        var deleted = false

        if (policy.maxEntries < Long.MAX_VALUE) {
            geoPackage.database.execSQL(
                "DELETE FROM $q WHERE id IN (" +
                        "SELECT id FROM $q ORDER BY id ASC " +
                        "LIMIT MAX(0, (SELECT COUNT(*) FROM $q) - ${policy.maxEntries})" +
                        ")"
            )
            deleted = true
        }

        // Drop revalidation rows orphaned by either eviction — by tpudt_id, so a tile row that no
        // longer exists in the pyramid leaves no stale freshness row behind.
        if (deleted && tileRevalidationDao.isTableExists) {
            val escapedName = content.tableName.replace("'", "''")
            val r = GpkgTileRevalidation.TABLE_NAME
            geoPackage.database.execSQL(
                "DELETE FROM $r WHERE ${GpkgTileRevalidation.COLUMN_TPUDT_NAME} = '$escapedName' " +
                        "AND ${GpkgTileRevalidation.COLUMN_TPUDT_ID} NOT IN " +
                        "(SELECT ${GpkgTileUserData.ID} FROM $q)"
            )
        }
    }

    /**
     * Cache freshness row (`ETag`, `Last-Modified`, [GpkgTileRevalidation.validatedAt]) for the
     * tile at `(z, x, y)` in [content]. Returns null when nothing has been stored for this tile.
     *
     * Lives in a worldwind-private side table; the OGC tile-user-data table stays clean for
     * external readers. Any HTTP-fetched tile cache (image, vector, elevation) can use this.
     */
    suspend fun readTileRevalidation(
        content: GpkgContent, tpudtId: Long,
    ): GpkgTileRevalidation? = withContext(Dispatchers.IO) {
        if (!tileRevalidationDao.isTableExists) return@withContext null
        queryTileRevalidation(content.tableName, tpudtId)
    }

    /** Fetch the revalidation row for `(tpudtName, tpudtId)` by the unique combo, or null. Caller
     *  ensures the table exists and runs on the appropriate dispatcher. */
    private fun queryTileRevalidation(tpudtName: String, tpudtId: Long): GpkgTileRevalidation? =
        tileRevalidationDao.queryBuilder().where()
            .eq(GpkgTileRevalidation.COLUMN_TPUDT_NAME, tpudtName)
            .and().eq(GpkgTileRevalidation.COLUMN_TPUDT_ID, tpudtId)
            .queryForFirst()

    /**
     * Upsert the full freshness row for the tile row [tpudtId] in [content] — validators plus
     * [validatedAt] (epoch-millis we just confirmed it fresh). Always writes, even when both headers
     * are null, because [validatedAt] is the stale-while-revalidate trigger and must exist for every
     * cached tile. Creates the side table on first use; the table name is fresh, so no schema migration.
     */
    @Throws(IllegalStateException::class)
    suspend fun writeTileRevalidation(
        content: GpkgContent, tpudtId: Long,
        etag: String?, httpLastModified: String?, validatedAt: Long,
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile revalidation cannot be saved. GeoPackage is read-only!")
        if (!tileRevalidationDao.isTableExists) {
            TableUtils.createTableIfNotExists(connectionSource, GpkgTileRevalidation::class.java)
            // Declare the side table in gpkg_extensions, scoped to itself (column_name null) — never
            // to the tile-pyramid tables — so strict readers see a documented extension yet still
            // edit the standard tiles freely. Idempotent.
            registerExtension(
                tableName = GpkgTileRevalidation.TABLE_NAME, columnName = null,
                extensionName = WW_TILE_REVALIDATION_EXTENSION,
            )
        }
        // Read-modify-write keyed on the unique combo: the generated id is unknown up front, so
        // reuse the existing row's id (→ UPDATE) or insert a fresh one (id 0 → INSERT).
        val row = queryTileRevalidation(content.tableName, tpudtId) ?: GpkgTileRevalidation().apply {
            tpudtName = content.tableName
            this.tpudtId = tpudtId
        }
        row.etag = etag
        row.httpLastModified = httpLastModified
        row.validatedAt = validatedAt
        tileRevalidationDao.createOrUpdate(row)
    }

    /**
     * Refresh only [GpkgTileRevalidation.validatedAt] for tile row [tpudtId] — the 304-Not-Modified
     * path, where the bytes and validators are unchanged but we want the freshness window to restart
     * so the tile isn't re-requested every frame. Read-modify-write preserves the stored ETag /
     * Last-Modified. No-op when the tile has no revalidation row yet.
     */
    @Throws(IllegalStateException::class)
    suspend fun bumpTileValidatedAt(
        content: GpkgContent, tpudtId: Long, validatedAt: Long,
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile revalidation cannot be updated. GeoPackage is read-only!")
        if (!tileRevalidationDao.isTableExists) return@withContext
        val row = queryTileRevalidation(content.tableName, tpudtId) ?: return@withContext
        row.validatedAt = validatedAt
        tileRevalidationDao.update(row)
    }

    /** Drop every revalidation row tied to [content]. Called when the content table is cleared. */
    @Throws(IllegalStateException::class)
    suspend fun clearTileRevalidation(content: GpkgContent): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile revalidation cannot be cleared. GeoPackage is read-only!")
        if (!tileRevalidationDao.isTableExists) return@withContext
        tileRevalidationDao.deleteBuilder().apply {
            where().eq(GpkgTileRevalidation.COLUMN_TPUDT_NAME, content.tableName)
        }.delete()
    }

    // --- Feature cache coverage (ww_feature_coverage) ----------------------------------------------
    // Step 1 of the feature-interop refactor: a side table that will hold per-tile fetch freshness
    // and the negative-cache flag for features, so the features table itself can become a clean,
    // standard GeoPackage features layer. Not yet wired into the feature read/write path — the
    // feature cache still uses the in-table last_modified column for now.

    /** Feature coverage row for the tile region `(z, x, y)` of [content]'s features table, or null
     *  when that region has never been fetched. */
    suspend fun readFeatureCoverage(
        content: GpkgContent, z: Int, x: Int, y: Int,
    ): GpkgFeatureCoverage? = withContext(Dispatchers.IO) {
        if (!featureCoverageDao.isTableExists) return@withContext null
        queryFeatureCoverage(content.tableName, z, x, y)
    }

    /** Fetch the coverage row for `(tpudtName, z, x, y)` by the unique combo, or null. Caller
     *  ensures the table exists and runs on the appropriate dispatcher. */
    private fun queryFeatureCoverage(tpudtName: String, z: Int, x: Int, y: Int): GpkgFeatureCoverage? =
        featureCoverageDao.queryBuilder().where()
            .eq(GpkgFeatureCoverage.COLUMN_TPUDT_NAME, tpudtName)
            .and().eq(GpkgFeatureCoverage.COLUMN_TILE_Z, z)
            .and().eq(GpkgFeatureCoverage.COLUMN_TILE_X, x)
            .and().eq(GpkgFeatureCoverage.COLUMN_TILE_Y, y)
            .queryForFirst()

    /**
     * Upsert the coverage row for the tile region `(z, x, y)` — validators, [validatedAt] (epoch-
     * millis just confirmed fresh) and the [isEmpty] negative-cache flag. Creates / declares the
     * side table on first use.
     */
    @Throws(IllegalStateException::class)
    suspend fun writeFeatureCoverage(
        content: GpkgContent, z: Int, x: Int, y: Int,
        etag: String?, httpLastModified: String?, validatedAt: Long, isEmpty: Boolean,
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Feature coverage cannot be saved. GeoPackage is read-only!")
        if (!featureCoverageDao.isTableExists) {
            TableUtils.createTableIfNotExists(connectionSource, GpkgFeatureCoverage::class.java)
            registerExtension(
                tableName = GpkgFeatureCoverage.TABLE_NAME, columnName = null,
                extensionName = WW_FEATURE_COVERAGE_EXTENSION,
            )
        }
        val row = queryFeatureCoverage(content.tableName, z, x, y) ?: GpkgFeatureCoverage().apply {
            tpudtName = content.tableName
            tileZ = z
            tileX = x
            tileY = y
        }
        row.etag = etag
        row.httpLastModified = httpLastModified
        row.validatedAt = validatedAt
        row.isEmpty = isEmpty
        featureCoverageDao.createOrUpdate(row)
    }

    /** Refresh only [GpkgFeatureCoverage.validatedAt] for the tile region `(z, x, y)` — the 304
     *  path. Read-modify-write preserves the validators / [GpkgFeatureCoverage.isEmpty]. No-op when
     *  the region has no coverage row yet. */
    @Throws(IllegalStateException::class)
    suspend fun bumpFeatureValidatedAt(
        content: GpkgContent, z: Int, x: Int, y: Int, validatedAt: Long,
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Feature coverage cannot be updated. GeoPackage is read-only!")
        if (!featureCoverageDao.isTableExists) return@withContext
        val row = queryFeatureCoverage(content.tableName, z, x, y) ?: return@withContext
        row.validatedAt = validatedAt
        featureCoverageDao.update(row)
    }

    /** Drop every coverage row tied to [content]. Called when the content table is cleared. */
    @Throws(IllegalStateException::class)
    suspend fun clearFeatureCoverage(content: GpkgContent): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Feature coverage cannot be cleared. GeoPackage is read-only!")
        if (!featureCoverageDao.isTableExists) return@withContext
        featureCoverageDao.deleteBuilder().apply {
            where().eq(GpkgFeatureCoverage.COLUMN_TPUDT_NAME, content.tableName)
        }.delete()
    }

    suspend fun readGriddedTile(
        content: GpkgContent, tileUserData: GpkgTileUserData
    ): GpkgGriddedTile? = withContext(Dispatchers.IO) {
        griddedTileDao.queryBuilder().where().eq(GpkgGriddedTile.COLUMN_TABLE_NAME, content.tableName)
            .and().eq(GpkgGriddedTile.COLUMN_TABLE_ID, tileUserData.id).queryForFirst()
    }

    @Throws(IllegalStateException::class)
    suspend fun writeGriddedTile(
        content: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int, scale: Float = 1.0f, offset: Float = 0.0f,
        min: Float? = null, max: Float? = null, mean: Float? = null, stdDev: Float? = null
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile cannot be saved. GeoPackage is read-only!")
        readTileUserData(content, zoomLevel, tileColumn, tileRow)?.let { tileUserData ->
            val griddedTile = readGriddedTile(content, tileUserData) ?: GpkgGriddedTile().also {
                it.contents = content
                it.tableId = tileUserData.id
            }
            // Replace tile attributes
            griddedTile.scale = scale.toDouble()
            griddedTile.offset = offset.toDouble()
            griddedTile.min = min?.toDouble()
            griddedTile.max = max?.toDouble()
            griddedTile.mean = mean?.toDouble()
            griddedTile.standardDeviation = stdDev?.toDouble()
            griddedTileDao.createOrUpdate(griddedTile)
        }
    }

    @Throws(IllegalArgumentException::class)
    suspend fun buildLevelSetConfig(content: GpkgContent) = withContext(Dispatchers.IO) {
        // Vector tiles use the same tile pyramid plumbing as raster tiles; only the BLOB
        // encoding differs. Accept both `data_type` values when materialising a LevelSet.
        require(
            content.dataTypeName.equals(TILES, ignoreCase = true) ||
                content.dataTypeName.equals(VECTOR_TILES, ignoreCase = true)
        ) {
            "Unsupported GeoPackage content data_type: ${content.dataTypeName}"
        }
        val srs = content.srs?.also { srsDao.refresh(it) }
        require(srs != null && srs.organization.equals(EPSG, ignoreCase = true)
                && (srs.organizationCoordsysId == EPSG_3857 || srs.organizationCoordsysId == EPSG_4326)) {
            "Unsupported GeoPackage spatial reference system: ${srs?.srsName ?: "undefined"}"
        }
        val tms = tileMatrixSetDao.queryForId(content.tableName)
        require(tms != null && tms.srs.id == srs.id) { "Unsupported GeoPackage tile matrix set" }
        val tm = content.tileMatrix?.associateBy { it.zoomLevel.toInt() }?.also { tileMatrixCache[content.tableName] = it }
        require(!tm.isNullOrEmpty()) { "Unsupported GeoPackage tile matrix" }
        // Determine tile matrix zoom range. Not the same as tile metrics min and max zoom level!
        val zoomLevels = tm.keys.sorted()
        val minZoom = zoomLevels.first()
        val maxZoom = zoomLevels.last()
        val minTileMatrix = tm[minZoom]!!
        val tmsSector = buildSector(tms.minX, tms.minY, tms.maxX, tms.maxY, tms.srs.srsId)
        val contentSector = getBoundingSector(content) ?: tmsSector
        // Create layer config based on tile matrix set bounding box and available matrix zoom range
        LevelSetConfig().apply {
            sector.copy(contentSector)
            tileOrigin.copy(tmsSector)
            firstLevelDelta = Location(
                tmsSector.deltaLatitude / (tms.maxY - tms.minY) * minTileMatrix.pixelYSize * minTileMatrix.tileHeight,
                tmsSector.deltaLongitude / (tms.maxX - tms.minX) * minTileMatrix.pixelXSize * minTileMatrix.tileWidth
            )
            firstLevelNumber = minZoom
            numLevels = maxZoom - minZoom + 1
        }
    }

    /**
     * Create (or open) an image-tile pyramid table. Idempotent — a prior partial setup or
     * a previous successful run is detected and the existing content row is returned.
     *
     * @param imageFormat triggers the `gpkg_webp` extension when `image/webp`.
     */
    @Throws(IllegalStateException::class)
    suspend fun setupTilesContent(
        tableName: String,
        levelSet: LevelSet,
        displayName: String? = null,
        imageFormat: String = "image/png",
    ): GpkgContent = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")

        // Re-entrant by design — a previous run that succeeded (or crashed midway) leaves
        // some of the artifacts in place. No transaction wraps these calls (see commit
        // history; NGA's createTileTable + ContentsDao.verifyCreate use different JDBC
        // connections), so every step here is guarded with an existence check + CREATE-IF
        // / createIfNotExists semantics so re-execution is a no-op for already-done work.
        contentDao.queryForId(tableName)?.let { existing ->
            check(existing.dataTypeName.equals(TILES, ignoreCase = true)) {
                "Content '$tableName' exists but is not a tiles table (was '${existing.dataTypeName}')"
            }
            return@withContext existing
        }

        // NGA's createTileMatrixSetTable / createTileMatrixTable internally check
        // isTableExists before issuing CREATE TABLE, so they're safe to re-call.
        geoPackage.createTileMatrixSetTable()
        geoPackage.createTileMatrixTable()

        // WebPExtension.getOrCreate queries the gpkg_extensions row first; idempotent.
        if (imageFormat.equals("image/webp", ignoreCase = true)) {
            WebPExtension(geoPackage).getOrCreate(tableName)
        }

        // srsDao.getOrCreateFromEpsg is get-then-create — idempotent across calls.
        val srs = srsDao.getOrCreateFromEpsg(if (levelSet.sector is MercatorSector) EPSG_3857 else EPSG_4326)

        val matrixBox = buildBoundingBox(levelSet.tileOrigin, srs.id)
        val contentBox = if (levelSet.sector != levelSet.tileOrigin)
            buildBoundingBox(levelSet.sector, srs.id) else matrixBox

        if (!geoPackage.isTable(tableName)) {
            val tileTable = TileTable(tableName, TileTable.createRequiredColumns())
            geoPackage.createTileTable(tileTable)
        }

        val content = GpkgContent().also {
            it.tableName = tableName
            it.dataTypeName = TILES
            it.identifier = displayName ?: tableName
            it.minX = contentBox.minLongitude
            it.minY = contentBox.minLatitude
            it.maxX = contentBox.maxLongitude
            it.maxY = contentBox.maxLatitude
            it.srs = srs
        }
        val persistedContent = contentDao.createIfNotExists(content)

        val tms = GpkgTileMatrixSet().also {
            it.contents = persistedContent
            it.srs = srs
            it.minX = matrixBox.minLongitude
            it.minY = matrixBox.minLatitude
            it.maxX = matrixBox.maxLongitude
            it.maxY = matrixBox.maxLatitude
        }
        tileMatrixSetDao.createIfNotExists(tms)

        setupTileMatrices(persistedContent, levelSet)
        persistedContent
    }

    /** Refresh the [GpkgContent] identifier + bbox to match the layer's current
     *  [LevelSet.sector] and [displayName]. The level-set sector is the in-memory
     *  source of truth for the layer's data extent; this call persists it back to gpkg
     *  so the round-trip is safe — open via `tryRecoverLevelSet` copies the persisted
     *  bbox into the level-set sector; the next reopen writes the same value back.
     *  Bulk-download mutates the level-set sector before re-binding the cache, so the
     *  new extent lands on disk through the same write. */
    suspend fun updateTilesContent(
        tableName: String, levelSet: LevelSet, displayName: String?, content: GpkgContent,
    ): Unit = withContext(writeDispatcher) {
        // Reopen of a read-only GeoPackage is pure-read; never attempt the metadata write.
        if (isReadOnly) return@withContext
        val srs = srsDao.queryForId(if (levelSet.sector is MercatorSector) EPSG_3857 else EPSG_4326)
        val box = buildBoundingBox(levelSet.sector, srs.srsId)
        with(content) {
            identifier = displayName ?: tableName
            minX = box.minLongitude
            minY = box.minLatitude
            maxX = box.maxLongitude
            maxY = box.maxLatitude
        }
        contentDao.update(content)
    }

    /**
     * Create a tile pyramid for vector tiles. Mirrors [setupTilesContent] but for a non-raster
     * `data_type` (typically `"vector-tiles"`) and an arbitrary encoding extension — the user
     * table schema is identical to raster tiles, only the BLOB payload format differs.
     *
     * Pass [encoding] (e.g. `VectorTilesMapboxExtension(geoPackage)` for MVT,
     * `VectorTilesGeoJSONExtension(geoPackage)` for GeoJSON tiles) to register the encoding
     * row in `gpkg_extensions`. Cross-tool readers identify the BLOB encoding from that row.
     */
    @Throws(IllegalStateException::class)
    suspend fun setupVectorTilesContent(
        tableName: String, levelSet: LevelSet,
        displayName: String? = null,
        dataType: String = VECTOR_TILES,
        encoding: VectorTilesEncodingExtension? = null,
    ): GpkgContent = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")

        // Re-entrant by design — see [setupTilesContent].
        contentDao.queryForId(tableName)?.let { existing ->
            check(existing.dataTypeName.equals(dataType, ignoreCase = true)) {
                "Content '$tableName' exists but data_type is '${existing.dataTypeName}' (expected '$dataType')"
            }
            // Make sure the encoding registration is still in place even on the re-entry path.
            encoding?.getOrCreate(tableName)
            return@withContext existing
        }

        geoPackage.createTileMatrixSetTable()
        geoPackage.createTileMatrixTable()

        val srs = srsDao.getOrCreateFromEpsg(if (levelSet.sector is MercatorSector) EPSG_3857 else EPSG_4326)

        val matrixBox = buildBoundingBox(levelSet.tileOrigin, srs.id)
        val contentBox = if (levelSet.sector != levelSet.tileOrigin)
            buildBoundingBox(levelSet.sector, srs.id) else matrixBox

        // Standard GeoPackage tile pyramid table — identical schema to raster tiles.
        if (!geoPackage.isTable(tableName)) {
            val tileTable = TileTable(tableName, TileTable.createRequiredColumns())
            geoPackage.createTileTable(tileTable)
        }

        val content = GpkgContent().also {
            it.tableName = tableName
            it.dataTypeName = dataType
            it.identifier = displayName ?: tableName
            it.minX = contentBox.minLongitude
            it.minY = contentBox.minLatitude
            it.maxX = contentBox.maxLongitude
            it.maxY = contentBox.maxLatitude
            it.srs = srs
        }
        val persistedContent = contentDao.createIfNotExists(content)

        val tms = GpkgTileMatrixSet().also {
            it.contents = persistedContent
            it.srs = srs
            it.minX = matrixBox.minLongitude
            it.minY = matrixBox.minLatitude
            it.maxX = matrixBox.maxLongitude
            it.maxY = matrixBox.maxLatitude
        }
        tileMatrixSetDao.createIfNotExists(tms)

        // Encoding extension row (e.g. im_vector_tiles_mapbox) tags the BLOB format for
        // external readers. getOrCreate is query-then-create — idempotent.
        encoding?.getOrCreate(tableName)

        setupTileMatrices(persistedContent, levelSet)
        persistedContent
    }

    @Throws(IllegalStateException::class)
    suspend fun setupTileMatrices(content: GpkgContent, levelSet: LevelSet): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Content ${content.tableName} cannot be updated. GeoPackage is read-only!")
        val tms = tileMatrixSetDao.queryForId(content.tableName) ?: error("Matrix set not found")
        val deltaX = tms.maxX - tms.minX
        val deltaY = tms.maxY - tms.minY
        initializeTileMatrices(content) // Ensure foreign collection exists
        val ctm = content.tileMatrix ?: error("Tile Matrix foreign collection must not be empty at this point")
        val tm = ctm.associateBy { it.zoomLevel.toInt() }.toMutableMap().also { tileMatrixCache[content.tableName] = it }
        // No transaction wrap — see [setupTilesContent].
        for (i in 0 until levelSet.numLevels) levelSet.level(i)?.run {
            if (!tm.containsKey(levelNumber)) {
                val matrixWidth = levelWidth / tileWidth
                val matrixHeight = levelHeight / tileHeight
                val pixelXSize = deltaX / levelWidth
                val pixelYSize = deltaY / levelHeight
                val matrix = GpkgTileMatrix().also {
                    it.contents = content
                    it.zoomLevel = levelNumber.toLong()
                    it.matrixWidth = matrixWidth.toLong()
                    it.matrixHeight = matrixHeight.toLong()
                    it.tileWidth = tileWidth.toLong()
                    it.tileHeight = tileHeight.toLong()
                    it.pixelXSize = pixelXSize
                    it.pixelYSize = pixelYSize
                }
                ctm.add(matrix)
                tm[levelNumber] = matrix
            }
        }
    }

    /**
     * Upsert a [GpkgWebService] row for [tableName] with the supplied fields. Single
     * entry point for every web-backed cache content (image / elevation / vector-tile /
     * feature) — the previous per-layer-type setup variants have been collapsed here.
     */
    @Throws(IllegalStateException::class)
    suspend fun setupWebService(
        tableName: String,
        type: String,
        address: String,
        layerName: String? = null,
        outputFormat: String? = null,
        metadata: String? = null,
        isTransparent: Boolean = false,
    ): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("WebService $tableName cannot be updated. GeoPackage is read-only!")
        createWebServiceTable()
        webServiceDao.createOrUpdate(
            GpkgWebService().also {
                it.tableName = tableName
                it.type = type
                it.address = address
                it.layerName = layerName
                it.outputFormat = outputFormat
                it.metadata = metadata
                it.isTransparent = isTransparent
            }
        )
    }

    @Throws(IllegalArgumentException::class)
    suspend fun buildTileMatrixSet(content: GpkgContent) = withContext(Dispatchers.IO) {
        require(content.dataTypeName.equals(COVERAGE, ignoreCase = true)) {
            "Unsupported GeoPackage content data_type: ${content.dataTypeName}"
        }
        val srs = content.srs?.also { srsDao.refresh(it) }
        require(srs != null && srs.organization.equals(EPSG, ignoreCase = true) && srs.organizationCoordsysId == EPSG_4326) {
            "Unsupported GeoPackage spatial reference system: ${srs?.srsName ?: "undefined"}"
        }
        val tms = tileMatrixSetDao.queryForId(content.tableName)
        require(tms != null && tms.srs.id == srs.id) { "Unsupported GeoPackage tile matrix set" }
        val tm = content.tileMatrix?.associateBy { it.zoomLevel.toInt() }?.also { tileMatrixCache[content.tableName] = it }
        require(!tm.isNullOrEmpty()) { "Unsupported GeoPackage tile matrix" }
        val sector = buildSector(tms.minX, tms.minY, tms.maxX, tms.maxY, tms.srs.id)
        val entries = tm.values.sortedBy { it.zoomLevel }.map {
            TileMatrix(sector, it.zoomLevel.toInt(), it.matrixWidth.toInt(), it.matrixHeight.toInt(), it.tileWidth.toInt(), it.tileHeight.toInt())
        }
        TileMatrixSet(sector, entries)
    }

    /**
     * Create (or open) a gridded-coverage tile pyramid. Idempotent — re-running after a
     * partial setup or a previous successful run is a no-op.
     */
    @Throws(IllegalStateException::class)
    suspend fun setupGriddedCoverageContent(
        tableName: String,
        tileMatrixSet: TileMatrixSet,
        sector: Sector = tileMatrixSet.sector,
        displayName: String? = null,
        isFloat: Boolean = false,
    ): GpkgContent = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")

        contentDao.queryForId(tableName)?.let { existing ->
            check(existing.dataTypeName.equals(COVERAGE, ignoreCase = true)) {
                "Content '$tableName' exists but is not a gridded-coverage table (was '${existing.dataTypeName}')"
            }
            return@withContext existing
        }

        val srs = srsDao.getOrCreateFromEpsg(EPSG_4326)
        val matrixBox = buildBoundingBox(tileMatrixSet.sector, srs.id)
        val contentBox = if (sector != tileMatrixSet.sector)
            buildBoundingBox(sector, srs.id) else matrixBox
        val coverageData = createCoverageData(
            geoPackage, tableName, displayName, contentBox, srs.id, matrixBox, srs.id, isFloat
        )
        val tms = coverageData.tileMatrixSet

        griddedCoverageDao.createIfNotExists(
            GpkgGriddedCoverage().also {
                it.tileMatrixSet = tms
                it.dataType = if (isFloat) FLOAT else INTEGER
                it.dataNull = if (isFloat) Float.MAX_VALUE.toDouble() else Short.MIN_VALUE.toDouble()
            }
        )

        val content = tms.contents
        setupTileMatrices(content, tileMatrixSet)
        content
    }

    /** Refresh the [GpkgContent] identifier + bbox for a coverage table, mirroring the
     *  round-trip safety of [updateTilesContent]. */
    suspend fun updateGriddedCoverageContent(
        tableName: String, sector: Sector, displayName: String?, content: GpkgContent,
    ) = withContext(writeDispatcher) {
        // Reopen of a read-only GeoPackage is pure-read; never attempt the metadata write.
        if (isReadOnly) return@withContext
        val srs = srsDao.queryForId(EPSG_4326)
        val box = buildBoundingBox(sector, srs.id)
        with(content) {
            identifier = displayName ?: tableName
            minX = box.minLongitude
            minY = box.minLatitude
            maxX = box.maxLongitude
            maxY = box.maxLatitude
        }
        contentDao.update(content)
    }

    @Throws(IllegalStateException::class)
    suspend fun setupTileMatrices(content: GpkgContent, tileMatrixSet: TileMatrixSet): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Content ${content.tableName} cannot be updated. GeoPackage is read-only!")
        val tms = tileMatrixSetDao.queryForId(content.tableName) ?: error("Matrix set not found")
        val deltaX = tms.maxX - tms.minX
        val deltaY = tms.maxY - tms.minY
        initializeTileMatrices(content) // Ensure foreign collection exists
        val ctm = content.tileMatrix ?: error("Tile Matrix foreign collection must not be empty at this point")
        val tm = ctm.associateBy { it.zoomLevel.toInt() }.toMutableMap().also { tileMatrixCache[content.tableName] = it }
        // No transaction wrap — see [setupTilesContent].
        for (tileMatrix in tileMatrixSet.entries) if (!tm.containsKey(tileMatrix.ordinal)) with(tileMatrix) {
            val pixelXSize = deltaX / matrixWidth / tileWidth
            val pixelYSize = deltaY / matrixHeight / tileHeight
            val matrix = GpkgTileMatrix().also {
                it.contents = content
                it.zoomLevel = ordinal.toLong()
                it.matrixWidth = matrixWidth.toLong()
                it.matrixHeight = matrixHeight.toLong()
                it.tileWidth = tileWidth.toLong()
                it.tileHeight = tileHeight.toLong()
                it.pixelXSize = pixelXSize
                it.pixelYSize = pixelYSize
            }
            ctm.add(matrix)
            tm[ordinal] = matrix
        }
    }


    /**
     * Create a features cache table with universal schema `(id, geom, tile_*, properties)`.
     * Tile columns are NULL for bulk-refresh sources, populated for tile-pyramid sources;
     * the composite index keeps per-tile reads O(log n). Idempotent.
     */
    @Throws(IllegalStateException::class)
    suspend fun setupFeaturesContent(
        tableName: String, displayName: String? = null,
    ): GpkgContent = withContext(writeDispatcher) {
        contentDao.queryForId(tableName)?.let { existing ->
            check(existing.dataTypeName.equals(FEATURES, ignoreCase = true)) {
                "Content '$tableName' exists but is not a features table"
            }
            return@withContext existing
        }
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")
        // No transaction wrap. NGA's createFeatureTable calls back into ORMLite DAOs
        // (ContentsDao.verifyCreate → GeometryColumnsDao.isTableExists) on a different
        // JDBC connection than NGA's private `Connection`. Wrapping in either ORMLite's
        // TransactionManager or NGA's beginTransaction makes one side hold uncommitted
        // schema changes that the other side can't see — manifesting as either
        // SQLITE_BUSY (ORMLite-txn + NGA writes) or "GeometryColumns table missing"
        // (NGA-txn + ORMLite reads). Without a transaction every CREATE IF NOT EXISTS /
        // INSERT autocommits; the operations below are individually idempotent.
        val srs = srsDao.getOrCreateFromEpsg(EPSG_4326)
        val box = BoundingBox(-180.0, -90.0, 180.0, 90.0)
        val geometryColumns = GeometryColumns().also {
            it.tableName = tableName
            it.columnName = FEATURE_GEOM_COLUMN
            // GEOMETRY (not POLYGON) — MVT mixes points/lines/polygons in one payload.
            it.geometryType = GeometryType.GEOMETRY
            it.srs = srs
            it.z = 0
            it.m = 0
        }
        // Flat standard features table: geometry + a properties JSON column + an optional stable
        // natural-key column (feature_uid) for upsert/dedup. No tile_z/x/y or last_modified — tile
        // coverage and freshness live in ww_feature_coverage, so this stays a clean GeoPackage
        // features layer any external GIS reads. List is `additionalColumns` (NGA prepends id+geom).
        val columns = listOf(
            FeatureColumn.createColumn(FEATURE_PROPERTIES_COLUMN, GeoPackageDataType.TEXT),
            FeatureColumn.createColumn(FEATURE_UID_COLUMN, GeoPackageDataType.TEXT),
        )
        val metadata = FeatureTableMetadata.create(geometryColumns, columns, box)
        metadata.identifier = displayName ?: tableName
        // NGA's createFeatureTable internally CREATEs the user table + INSERTs Contents +
        // GeometryColumns rows. If a prior run crashed mid-flight the user table may
        // already exist; skip in that case rather than fail on a raw CREATE TABLE.
        // For a partial state where the table exists but the Contents row is missing,
        // delete the orphan and retry: `deleteEntry(tableName)` then call setup again.
        if (!geoPackage.isFeatureTable(tableName)) {
            geoPackage.createFeatureTable(metadata)
        }
        // Standard GeoPackage spatial index — makes the features table bbox-queryable and lets any
        // external GIS read it as a properly-indexed layer. Idempotent; kept in sync by NGA triggers.
        createFeatureSpatialIndex(geoPackage, tableName)
        val escapedTable = tableName.replace("\"", "\"\"")
        // UNIQUE so a re-fetch upserts by stable key; SQLite treats NULLs as distinct, so id-less
        // (bulk) rows coexist freely.
        geoPackage.database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS \"${escapedTable}_uid_idx\" " +
                    "ON \"$escapedTable\" ($FEATURE_UID_COLUMN)"
        )
        contentDao.queryForId(tableName) ?: error(
            "Features content '$tableName' missing after createFeatureTable — the .gpkg file " +
                "may be in a half-created state. Call `deleteEntry(\"$tableName\")` and retry."
        )
    }

    /**
     * Replace every row in one transaction. Used by full-refresh sources (WFS, Shapefile);
     * tile-pyramid sources use [writeFeatureTile] instead.
     */
    @Throws(IllegalStateException::class)
    suspend fun replaceCachedFeatures(
        content: GpkgContent, rows: List<Pair<Geometry, String?>>,
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Cached features ${content.tableName} cannot be updated. GeoPackage is read-only!")
        // No transaction wrap — see [setupTilesContent]. Migration ran in setupFeaturesContent;
        // the feature-row actuals probe getColumnIndex per call to skip last_modified when absent.
        truncateFeatureTable(geoPackage, content.tableName)
        if (rows.isNotEmpty()) insertCachedFeatures(geoPackage, content.tableName, rows)
        content.lastChange = Date()
        contentDao.update(content)
    }

    /** Read back features previously cached via [replaceCachedFeatures]. */
    suspend fun readCachedFeatures(content: GpkgContent): List<Pair<Geometry, String?>> = withContext(Dispatchers.IO) {
        readCachedFeaturesWithProperties(geoPackage, content.tableName)
    }

    /** Read the cached features in tile `(z, x, y)`'s region via the RTree bbox query. The flat
     *  store has no per-tile rows, so this returns every feature intersecting the tile bounds;
     *  `ww_feature_coverage` is what records whether the tile was actually fetched. */
    suspend fun readFeatureTile(
        content: GpkgContent, z: Int, x: Int, y: Int,
    ): List<Pair<Geometry, String?>> = withContext(Dispatchers.IO) {
        val b = slippyTileBounds(z, x, y)
        readFeaturesInBoundingBox(geoPackage, content.tableName, b[0], b[1], b[2], b[3])
    }

    /**
     * Replace tile `(z, x, y)`'s features in the flat store and stamp coverage. Rows that carry a
     * stable [GpkgFeatureRow.uid] upsert by it (so a feature straddling tile borders dedupes to one
     * row); id-less rows replace everything in the tile's bbox. An empty list clears the region and
     * records the negative cache (`is_empty`) in `ww_feature_coverage` — no null-geometry sentinel.
     */
    @Throws(IllegalStateException::class)
    suspend fun writeFeatureTile(
        content: GpkgContent, z: Int, x: Int, y: Int, rows: List<GpkgFeatureRow>,
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Feature tile ${content.tableName}/$z/$x/$y cannot be saved. GeoPackage is read-only!")
        val b = slippyTileBounds(z, x, y)
        val upsertByUid = rows.isNotEmpty() && rows.all { it.uid != null }
        writeFeatureTileFlat(geoPackage, content.tableName, b[0], b[1], b[2], b[3], rows, upsertByUid)
        // On a uid-keyed re-fetch, drop features this tile OWNS (envelope-center inside it, matching
        // the read-side ownership) that the new fetch no longer reports — i.e. deleted upstream. The
        // just-inserted rows are excluded by uid, so only genuinely-vanished features are removed.
        if (upsertByUid) deleteVanishedOwnedFeatures(
            geoPackage, content.tableName, b[0], b[1], b[2], b[3], rows.mapNotNull { it.uid }.toSet()
        )
        writeFeatureCoverage(
            content, z, x, y, etag = null, httpLastModified = null,
            validatedAt = System.currentTimeMillis(), isEmpty = rows.isEmpty(),
        )
        content.lastChange = Date()
        contentDao.update(content)
    }

    /** Slippy (Web-Mercator) tile `(z, x, y)` → geographic bounds `[west, south, east, north]` in
     *  degrees (EPSG:4326, the feature geometry SRS) — the inverse of OsmBuildingsLayer.tileToSector. */
    private fun slippyTileBounds(z: Int, x: Int, y: Int): DoubleArray {
        val n = 1 shl z
        val west = x.toDouble() / n * 360.0 - 180.0
        val east = (x + 1).toDouble() / n * 360.0 - 180.0
        val north = atan(sinh(PI * (1 - 2 * y.toDouble() / n))) * 180.0 / PI
        val south = atan(sinh(PI * (1 - 2 * (y + 1).toDouble() / n))) * 180.0 / PI
        return doubleArrayOf(west, south, east, north)
    }

    /**
     * Evict from a features cache per [policy], driven by `ww_feature_coverage`:
     *  - [CachePolicy.staleAfter]: NOT a deletion trigger — stale tiles refresh in place (SWR).
     *  - [CachePolicy.maxEntries]: cap on cached coverage *tiles*.
     *
     * Keeps the newest coverage tiles (by id, so revalidation never changes the victim set) and
     * evicts the rest as WHOLE tiles — overlap-safe, so a feature shared with a retained tile
     * survives. No-op when read-only, unbounded, or there's no coverage table.
     */
    suspend fun evictFeatures(
        content: GpkgContent, policy: CachePolicy,
    ) = withContext(writeDispatcher) {
        if (isReadOnly || policy.isUnbounded) return@withContext
        if (!featureCoverageDao.isTableExists) return@withContext

        // Coverage tiles, newest-inserted first.
        val tiles = featureCoverageDao.queryBuilder()
            .orderBy(GpkgFeatureCoverage.COLUMN_ID, false)
            .where().eq(GpkgFeatureCoverage.COLUMN_TPUDT_NAME, content.tableName).query()
        if (tiles.isEmpty()) return@withContext

        // Cheap over-budget guard: the walk below runs a spatial query + cursor materialization per
        // coverage tile, so skip it when already within caps (common at startup).
        if (policy.maxEntries == Long.MAX_VALUE || tiles.size <= policy.maxEntries) return@withContext

        // Walk newest→oldest, keeping the prefix that fits the entry cap. keptIds accumulates the
        // kept tiles' features (deduped) for the overlap-safe delete below.
        val keptIds = HashSet<Long>()
        var keepCount = 0
        for (tile in tiles) {
            if (keepCount >= policy.maxEntries) break
            val ids = featureIdsForCoverage(content.tableName, tile)
            keptIds.addAll(ids)
            keepCount++
        }
        if (keepCount >= tiles.size) return@withContext // everything fits
        val evict = tiles.drop(keepCount)

        // Delete only features that lie in an evicted tile and NO retained tile.
        val evictIds = HashSet<Long>()
        for (tile in evict) evictIds += featureIdsForCoverage(content.tableName, tile)
        evictIds.removeAll(keptIds)
        deleteFeaturesByIds(content.tableName, evictIds)
        // Drop the evicted tiles' coverage rows.
        featureCoverageDao.deleteBuilder().apply {
            where().`in`(GpkgFeatureCoverage.COLUMN_ID, evict.map { it.id })
        }.delete()
    }

    /** Feature ids intersecting one coverage tile's bbox. */
    private fun featureIdsForCoverage(tableName: String, coverage: GpkgFeatureCoverage): List<Long> {
        val b = slippyTileBounds(coverage.tileZ, coverage.tileX, coverage.tileY)
        return featureIdsInBoundingBox(geoPackage, tableName, b[0], b[1], b[2], b[3])
    }


    /** Delete the given feature rows by id, chunked to stay within SQLite statement limits. */
    private fun deleteFeaturesByIds(tableName: String, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val q = "\"${tableName.replace("\"", "\"\"")}\""
        for (chunk in ids.chunked(500)) {
            geoPackage.database.execSQL("DELETE FROM $q WHERE $FEATURE_ID_COLUMN IN (${chunk.joinToString(",")})")
        }
    }


    /** Update the user-visible identifier on [tableName]'s registration row. */
    @Throws(IllegalStateException::class)
    suspend fun setDisplayName(tableName: String, displayName: String): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be updated. GeoPackage is read-only!")
        val content = contentDao.queryForId(tableName) ?: return@withContext
        content.identifier = displayName
        contentDao.update(content)
    }

    @Throws(IllegalStateException::class)
    suspend fun clearEntry(tableName: String): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")
        if (!contentDao.isTableExists) return@withContext
        val content = contentDao.queryForId(tableName) ?: return@withContext

        // Remove all tiles in specified content table and gridded tile data but keep the
        // table itself. Content row metadata (bbox, identifier, service config) stays —
        // clearing tiles isn't deletion; the content still exists with the same data
        // extent, just no cached blobs. No transaction wrap — see [setupTilesContent].
        val dao = getOrCreateTileUserDataDao(tableName)
        TableUtils.dropTable(dao, true)
        TableUtils.createTable(dao)
        if (griddedTileDao.isTableExists) griddedTileDao.deleteBuilder().apply {
            where().eq(GpkgGriddedTile.COLUMN_TABLE_NAME, content.tableName)
        }.delete()
        clearTileRevalidation(content)
        clearFeatureCoverage(content)
    }

    /**
     * Delete specified content table and its related metadata
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun deleteEntry(tableName: String): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")
        if (!contentDao.isTableExists) return@withContext
        val content = contentDao.queryForId(tableName) ?: return@withContext

        // No transaction wrap — see [setupTilesContent].
        if (griddedTileDao.isTableExists) griddedTileDao.deleteBuilder().apply {
            where().eq(GpkgGriddedTile.COLUMN_TABLE_NAME, tableName)
        }.delete()
        TableUtils.dropTable(getOrCreateTileUserDataDao(tableName), true)
        removeTileUserDataDao(tableName)

        if (tileMatrixDao.isTableExists) tileMatrixDao.deleteBuilder().apply {
            where().eq(GpkgTileMatrix.COLUMN_TABLE_NAME, tableName)
        }.delete()

        if (tileMatrixSetDao.isTableExists) tileMatrixSetDao.queryForId(tableName)?.let { tileMatrixSet ->
            if (griddedCoverageDao.isTableExists) griddedCoverageDao.deleteBuilder().apply {
                where().eq(GpkgGriddedCoverage.COLUMN_TILE_MATRIX_SET_NAME, tableName)
            }.delete()
            tileMatrixSetDao.delete(tileMatrixSet)
        }

        if (extensionDao.isTableExists) extensionDao.deleteBuilder().apply {
            where().eq(GpkgExtension.COLUMN_TABLE_NAME, tableName)
        }.delete()

        if (webServiceDao.isTableExists) webServiceDao.deleteById(tableName)

        clearTileRevalidation(content)
        clearFeatureCoverage(content)
        contentDao.delete(content)
    }

    fun getBoundingSector(content: GpkgContent): Sector? {
        val minX = content.minX ?: return null
        val minY = content.minY ?: return null
        val maxX = content.maxX ?: return null
        val maxY = content.maxY ?: return null
        val srsId = content.srs?.id ?: return null
        return buildSector(minX, minY, maxX, maxY, srsId)
    }

    fun getTileMatrix(content: GpkgContent, zoomLevel: Int) = tileMatrixCache[content.tableName]?.get(zoomLevel)

    protected open fun createWebServiceTable() {
        if (!webServiceDao.isTableExists) TableUtils.createTable(webServiceDao)
        registerExtension(tableName = GpkgWebService.TABLE_NAME, columnName = null, extensionName = WW_WEB_SERVICE_EXTENSION)
    }

    /**
     * One-time rename of the legacy `gpkg_web_service` table — which squatted on the spec-reserved
     * `gpkg_` prefix — to `ww_web_service`, re-scoping its `gpkg_extensions` row to the renamed table.
     * Runs only on a writable open; a read-only cache keeps the old name and opens offline (it can't
     * write back, so an online web-service rebind would be pointless). Idempotent; no-op for new files.
     */
    private fun migrateLegacyWebServiceTable() {
        if (!geoPackage.database.tableExists(LEGACY_WEB_SERVICE_TABLE)) return
        if (geoPackage.database.tableExists(GpkgWebService.TABLE_NAME)) return
        geoPackage.database.execSQL(
            "ALTER TABLE \"$LEGACY_WEB_SERVICE_TABLE\" RENAME TO \"${GpkgWebService.TABLE_NAME}\""
        )
        if (extensionDao.isTableExists) geoPackage.database.execSQL(
            "UPDATE gpkg_extensions SET ${GpkgExtension.COLUMN_TABLE_NAME} = '${GpkgWebService.TABLE_NAME}' " +
                    "WHERE ${GpkgExtension.COLUMN_EXTENSION_NAME} = '$WW_WEB_SERVICE_EXTENSION' " +
                    "AND ${GpkgExtension.COLUMN_TABLE_NAME} IS NULL"
        )
    }

    /**
     * Set up a 3D Tiles GeoPackage extension blob table for [tableName]. Idempotent:
     * re-opening an existing 3D Tiles content registers the same row + extension entry.
     *
     * Schema follows the OGC 3D Tiles GeoPackage Extension proposal — the per-dataset
     * blob table is created by the caller's DAO ([GpkgBlobRow] columns), and this method
     * stitches it into the GPKG content + extension registries so external readers see it
     * as a documented extension rather than an anonymous user table.
     */
    suspend fun setup3DTilesContent(
        tableName: String,
        displayName: String? = null,
    ): GpkgContent = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")
        contentDao.queryForId(tableName)?.let { existing ->
            check(existing.dataTypeName.equals(OGC_3D_TILES, ignoreCase = true)) {
                "Content '$tableName' exists but data_type is '${existing.dataTypeName}' " +
                    "(expected '$OGC_3D_TILES')"
            }
            // Make sure the extension row is still in place on the re-entry path.
            registerExtension(tableName, null, OGC_3D_TILES_EXTENSION)
            return@withContext existing
        }
        // Data table must exist before the gpkg_contents row: NGA's verifyCreate rejects
        // a content row whose target table isn't there. Idempotent on re-entry.
        TableUtils.createTableIfNotExists(
            connectionSource,
            DatabaseTableConfig(GpkgBlobRow::class.java, tableName, null),
        )
        val content = GpkgContent().also {
            it.tableName = tableName
            it.dataTypeName = OGC_3D_TILES
            it.identifier = displayName ?: tableName
        }
        val persisted = contentDao.createIfNotExists(content)
        registerExtension(tableName, null, OGC_3D_TILES_EXTENSION)
        persisted
    }

    /**
     * Register a row in `gpkg_extensions` per OGC spec. Idempotent — if the same (table, column,
     * extension) triple is already present, no-op. Lets external GPKG readers introspect our
     * custom extensions (`worldwind_web_service`, `worldwind_last_modified`) instead of seeing
     * them as anonymous extra columns / unknown tables.
     */
    protected open fun registerExtension(
        tableName: String?, columnName: String?, extensionName: String,
    ) {
        if (isReadOnly) return
        if (!extensionDao.isTableExists) TableUtils.createTable(extensionDao)
        val existing = extensionDao.queryBuilder().where()
            .let { w ->
                if (tableName == null) w.isNull(GpkgExtension.COLUMN_TABLE_NAME)
                else w.eq(GpkgExtension.COLUMN_TABLE_NAME, tableName)
            }
            .and()
            .let { w ->
                if (columnName == null) w.isNull(GpkgExtension.COLUMN_COLUMN_NAME)
                else w.eq(GpkgExtension.COLUMN_COLUMN_NAME, columnName)
            }
            .and().eq(GpkgExtension.COLUMN_EXTENSION_NAME, extensionName)
            .queryForFirst()
        if (existing != null) return
        extensionDao.create(GpkgExtension().also {
            it.tableName = tableName
            it.columnName = columnName
            it.extensionName = extensionName
            it.definition = WW_EXTENSION_DEFINITION
            it.scope = ExtensionScopeType.READ_WRITE
        })
    }

    protected open fun latToEPSG3857(lat: Angle) = ln(tan(PI / 4.0 + lat.inRadians / 2.0)) * Ellipsoid.WGS84.semiMajorAxis

    protected open fun lonToEPSG3857(lon: Angle) = lon.inRadians * Ellipsoid.WGS84.semiMajorAxis

    protected open fun latFromEPSG3857(y: Double) = (atan(exp(y / Ellipsoid.WGS84.semiMajorAxis)) * 2.0 - PI / 2.0).radians

    protected open fun lonFromEPSG3857(x: Double) = (x / Ellipsoid.WGS84.semiMajorAxis).radians

    protected open fun buildSector(
        minX: Double, minY: Double, maxX: Double, maxY: Double, srsId: Long
    ) = if (srsId == EPSG_3857) MercatorSector.fromSector(Sector(
        latFromEPSG3857(minY), latFromEPSG3857(maxY), lonFromEPSG3857(minX), lonFromEPSG3857(maxX)
    )) else Sector(minY.degrees, maxY.degrees, minX.degrees, maxX.degrees)

    protected open fun buildBoundingBox(sector: Sector, srsId: Long) = if (srsId == EPSG_3857) BoundingBox(
        lonToEPSG3857(sector.minLongitude), latToEPSG3857(sector.minLatitude),
        lonToEPSG3857(sector.maxLongitude), latToEPSG3857(sector.maxLatitude)
    ) else BoundingBox(
        sector.minLongitude.inDegrees, sector.minLatitude.inDegrees,
        sector.maxLongitude.inDegrees, sector.maxLatitude.inDegrees
    )

    /** Convert a [Geometry] into [Renderable]s using the given (optional) feature style.
     *  Public so the WFS cache replay path can call it directly; subclasses can still
     *  override to specialise rendering. */
    open fun geometryToRenderables(
        geometry: Geometry, style: FeatureStyle?, srsId: Long
    ): List<Renderable> = when(geometry) {
        is Point -> listOf(
            Placemark.createWithImage(
                buildPosition(geometry, srsId), ImageSource.fromResource(MR.images.kml_placemark)
            ).apply {
                altitudeMode = if (geometry.is3D) AltitudeMode.ABSOLUTE else AltitudeMode.CLAMP_TO_GROUND
                attributes.isDrawLeader = geometry.is3D
                style?.getIcon()?.let { icon ->
                    val x = icon.getAnchorU() ?: 0.5 // middle of icon
                    val y = icon.getAnchorV() ?: 1.0 // bottom of icon
                    attributes.apply {
                        imageSource = buildImageSource(icon)
                        imageOffset = Offset(OffsetMode.FRACTION, x, OffsetMode.FRACTION, y)
                    }
                }
            }
        )
        is LineString -> listOf(
            Path(geometry.points.map { buildPosition(it, srsId) }).apply {
                pathType = PathType.LINEAR
                altitudeMode = if (geometry.is3D) AltitudeMode.ABSOLUTE else AltitudeMode.CLAMP_TO_GROUND
                isFollowTerrain = !geometry.is3D
                isExtrude = geometry.is3D
                attributes.apply {
                    outlineWidth = style?.getStyle()?.getWidth()?.toFloat() ?: defaultOutlineWidth
                    outlineColor = style?.getStyle()?.getColor()?.let { earth.worldwind.render.Color(it.colorWithAlpha) }
                        ?: earth.worldwind.render.Color(defaultOutlineColor)
                    interiorColor = style?.getStyle()?.getFillColor()?.let { earth.worldwind.render.Color(it.colorWithAlpha) }
                        ?: earth.worldwind.render.Color(defaultInteriorColor)
                }
            }
        )
        is Polygon -> listOf(
            earth.worldwind.shape.Polygon().apply {
                geometry.rings.forEach { ring -> addBoundary(ring.points.map { buildPosition(it, srsId) }) }
                pathType = PathType.LINEAR
                altitudeMode = if (geometry.is3D) AltitudeMode.ABSOLUTE else AltitudeMode.CLAMP_TO_GROUND
                isFollowTerrain = !geometry.is3D
                isExtrude = geometry.is3D
                attributes.apply {
                    outlineWidth = style?.getStyle()?.getWidth()?.toFloat() ?: defaultOutlineWidth
                    outlineColor = style?.getStyle()?.getColor()?.let { earth.worldwind.render.Color(it.colorWithAlpha) }
                        ?: earth.worldwind.render.Color(defaultOutlineColor)
                    interiorColor = style?.getStyle()?.getFillColor()?.let { earth.worldwind.render.Color(it.colorWithAlpha) }
                        ?: earth.worldwind.render.Color(defaultInteriorColor)
                }
            }
        )
        is MultiPoint -> geometry.points.flatMap { point -> geometryToRenderables(point, style, srsId) }
        is MultiLineString -> geometry.lineStrings.flatMap { lineString -> geometryToRenderables(lineString, style, srsId) }
        is MultiPolygon -> geometry.polygons.flatMap { polygon -> geometryToRenderables(polygon, style, srsId) }
        is CompoundCurve -> geometry.lineStrings.flatMap { lineString -> geometryToRenderables(lineString, style, srsId) }
        is GeometryCollection<*> -> geometry.geometries.flatMap { child -> geometryToRenderables(child, style, srsId) }
        else -> emptyList() // TODO Add support af all other geometries
    }

    protected open fun buildPosition(point: Point, srsId: Long) = Position(
        latitude = if (srsId == EPSG_3857) latFromEPSG3857(point.y) else point.y.degrees,
        longitude = if (srsId == EPSG_3857) lonFromEPSG3857(point.x) else point.x.degrees,
        altitude = point.z ?: 0.0
    )

    @Synchronized
    protected open fun getOrCreateTileUserDataDao(tableName: String) = tileUserDataDao[tableName]
        ?: object : BaseDaoImpl<GpkgTileUserData, Int>(
            connectionSource, DatabaseTableConfig(GpkgTileUserData::class.java, tableName, null)
        ) {}.also {
            DaoManager.registerDaoWithTableConfig(connectionSource, it)
            tileUserDataDao[tableName] = it
        }

    @Synchronized
    protected open fun removeTileUserDataDao(tableName: String) {
        tileUserDataDao.remove(tableName)?.let { DaoManager.unregisterDao(connectionSource, it) }
    }

    protected open fun initializeTileMatrices(content: GpkgContent) {
        if (content.tileMatrix == null) {
            contentDao.assignEmptyForeignCollection(content, "tileMatrix")
            content.tileMatrix?.refreshCollection()
        }
    }

    companion object {
        const val EPSG = "EPSG"
        const val EPSG_3857 = ProjectionConstants.EPSG_WEB_MERCATOR.toLong()
        const val EPSG_4326 = ProjectionConstants.EPSG_WORLD_GEODETIC_SYSTEM.toLong()
        val TILES = ContentsDataType.TILES.name.lowercase()
        val FEATURES = ContentsDataType.FEATURES.name.lowercase()
        /** `gpkg_contents.data_type` value for the Image Matters Vector Tiles community extension. */
        const val VECTOR_TILES = "vector-tiles"
        const val COVERAGE = CoverageDataCore.GRIDDED_COVERAGE
        val FLOAT = GriddedCoverageDataType.FLOAT
        val INTEGER = GriddedCoverageDataType.INTEGER
        var defaultOutlineWidth = 1f
        var defaultOutlineColor = earth.worldwind.render.Color(0f, 0f, 0f, 1f)
        var defaultInteriorColor = earth.worldwind.render.Color(0f, 0f, 0f, 0f)

        /** Column names for the features cache schema. Public so JVM/Android actuals match the writer. */
        const val FEATURE_ID_COLUMN = "id"
        const val FEATURE_GEOM_COLUMN = "geom"
        const val FEATURE_PROPERTIES_COLUMN = "properties"
        /** Optional stable natural key (e.g. OSM `id`) for upsert/dedup; null for id-less sources. */
        const val FEATURE_UID_COLUMN = "feature_uid"

        /** Author-prefixed names registered in `gpkg_extensions` so external OGC tools see our
         *  custom additions (side tables, web-service metadata) as documented extensions rather
         *  than anonymous extra rows. */
        const val WW_WEB_SERVICE_EXTENSION = "worldwind_web_service"
        /** Pre-rename name of [GpkgWebService]'s table; migrated to ww_web_service on writable open. */
        const val LEGACY_WEB_SERVICE_TABLE = "gpkg_web_service"
        const val WW_TILE_REVALIDATION_EXTENSION = "worldwind_tile_revalidation"
        const val WW_FEATURE_COVERAGE_EXTENSION = "worldwind_feature_coverage"
        const val WW_EXTENSION_DEFINITION = "https://worldwind.earth"

        /**
         * `gpkg_contents.data_type` value for the OGC 3D Tiles GeoPackage Extension. Each
         * blob-cache table created by [setup3DTilesContent] registers its row with this
         * data_type so external GeoPackage tools (QGIS, GDAL, ogr2ogr) recognise the
         * 3D Tiles payload rather than seeing it as an unknown user table.
         */
        const val OGC_3D_TILES = "3d-tiles"

        /**
         * Extension name registered in `gpkg_extensions` for the 3D Tiles cache table.
         * Author prefix `gpkg_` per the OGC GeoPackage extension naming convention; the
         * row points at the [OGC_3D_TILES_DEFINITION] documentation URL.
         */
        const val OGC_3D_TILES_EXTENSION = "gpkg_3d_tiles"

        /** Documentation URL stored in the extension row for cross-tool introspection. */
        const val OGC_3D_TILES_DEFINITION = "https://docs.ogc.org/cs/22-025r4/22-025r4.html"

        init { installLenientDatePatterns() }

        // gpkg_contents.last_change is required by spec to be ISO 8601 with the T separator,
        // but tools like QGIS, older GDAL/ogr2ogr, and any script using SQLite's datetime('now')
        // emit the space-separated form ("2026-03-24 18:52:34"). mil.nga.geopackage's strict
        // parser then throws SQLException and the whole content list fails to load. Reach into
        // DatePersister's singleton DateConverter and append the space-separated patterns so
        // those files are readable — strict ISO is still tried first, so legitimate data is
        // unaffected. Read-only OK: this only mutates an in-memory formatter list.
        private fun installLenientDatePatterns() = runCatching {
            val field = DatePersister::class.java.getDeclaredField("dateConverter").apply { isAccessible = true }
            val converter = field.get(null) as DateConverter
            converter.addFormat("yyyy-MM-dd HH:mm:ss.SSS")
            converter.addFormat("yyyy-MM-dd HH:mm:ss")
            converter.addFormat("yyyy-MM-dd HH:mm")
        }.onFailure {
            logMessage(WARN, "GeoPackage", "installLenientDatePatterns",
                "Unable to install lenient last_change date patterns: ${it.message}")
        }
    }
}