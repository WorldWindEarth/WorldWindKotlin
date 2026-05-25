package earth.worldwind.ogc.gpkg

import com.j256.ormlite.dao.BaseDaoImpl
import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import com.j256.ormlite.table.DatabaseTableConfig
import com.j256.ormlite.table.TableUtils
import earth.worldwind.MR
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.layer.cache.CacheEvictionPolicy
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
import mil.nga.color.Color
import mil.nga.geopackage.BoundingBox
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.contents.Contents
import mil.nga.geopackage.contents.ContentsDataType
import mil.nga.geopackage.db.DateConverter
import mil.nga.geopackage.db.GeoPackageDataType
import mil.nga.geopackage.extension.ExtensionScopeType
import mil.nga.geopackage.extension.WebPExtension
import mil.nga.geopackage.extension.im.vector_tiles.VectorTilesEncodingExtension
import mil.nga.geopackage.persister.DatePersister
import mil.nga.geopackage.extension.coverage.*
import mil.nga.geopackage.features.columns.GeometryColumns
import mil.nga.geopackage.features.user.FeatureColumn
import mil.nga.geopackage.features.user.FeatureTableMetadata
import mil.nga.geopackage.tiles.user.TileTable
import mil.nga.proj.ProjectionConstants
import mil.nga.sf.*
import java.util.*
import kotlin.math.*
import kotlin.time.Duration
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
expect fun getFeatureList(geoPackage: GeoPackageCore, tableName: String): List<Pair<Geometry, FeatureStyle?>>
/**
 * Read cached features written by the WFS cache pipeline — each row carries a geometry
 * plus a JSON string of the feature's original properties so callers can re-apply their
 * styling logic on replay. Wraps the per-platform cursor-vs-iterable difference.
 */
expect fun readCachedFeaturesWithProperties(
    geoPackage: GeoPackageCore, tableName: String,
): List<Pair<Geometry, String?>>
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
expect fun buildImageSource(iconRow: IconRow): ImageSource

/**
 * One row in a features cache table. Null [geometry] is the sentinel that marks a tile as
 * "fetched but empty"; [properties] is whatever JSON the caller chose to store.
 */
data class GpkgFeatureRow(val geometry: Geometry?, val properties: String?)

/** Read all rows for tile `(z, x, y)`. Empty list = never fetched. */
expect fun readFeatureTileRows(
    geoPackage: GeoPackageCore, tableName: String, z: Int, x: Int, y: Int,
): List<GpkgFeatureRow>

/** Replace every row for tile `(z, x, y)` in one transaction; empty list writes a sentinel. */
expect fun replaceFeatureTileRows(
    geoPackage: GeoPackageCore, tableName: String, z: Int, x: Int, y: Int,
    rows: List<GpkgFeatureRow>,
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
    private val tileMatrixSetDao = geoPackage.tileMatrixSetDao
    private val tileMatrixDao = geoPackage.tileMatrixDao
    private val extensionDao = geoPackage.extensionsDao
    private val griddedCoverageDao = CoverageDataCore.getGriddedCoverageDao(geoPackage)
    private val griddedTileDao = CoverageDataCore.getGriddedTileDao(geoPackage)
    private val tileUserDataDao = mutableMapOf<String, Dao<GpkgTileUserData, Int>>()
    private val tileMatrixCache = mutableMapOf<String, Map<Int, GpkgTileMatrix>>()
    /** Tables known to have the `last_modified` column — populated by [ensureLastModifiedColumn]
     *  on success. Read on every cache write to gate the per-tile `UPDATE` (skip the SQL round-trip
     *  on pre-eviction / external GPKGs where the column is absent) and on every feature insert
     *  to skip the `setValue` that would otherwise throw `GeoPackageException`. */
    private val tablesWithLastModified = mutableSetOf<String>()
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1) // Single thread dispatcher

    val isShutdown get() = !connectionSource.isOpen("")

    fun shutdown() = geoPackage.close().also {
        tileUserDataDao.clear()
        tileMatrixCache.clear()
        tablesWithLastModified.clear()
    }

    suspend fun countContent(dataType: String) = withContext(Dispatchers.IO) {
        if (contentDao.isTableExists) contentDao.queryBuilder().where().eq(GpkgContent.COLUMN_DATA_TYPE, dataType).countOf() else 0L
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
                        // Geometry alone undercounts tag-heavy sources (OsmBuildings, WFS).
                        result = dao.queryRawValue(
                            "SELECT COALESCE(SUM(LENGTH(\"$columnName\")), 0) + " +
                                "COALESCE(SUM(LENGTH(\"$FEATURE_PROPERTIES_COLUMN\")), 0) " +
                                "FROM '$tableName'"
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

    @Throws(IllegalStateException::class)
    suspend fun writeTileUserData(
        content: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int, tileData: ByteArray
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile cannot be saved. GeoPackage is read-only!")
        val tileUserData = readTileUserData(content, zoomLevel, tileColumn, tileRow) ?: GpkgTileUserData().also {
            it.zoomLevel = zoomLevel
            it.tileColumn = tileColumn
            it.tileRow = tileRow
        }
        tileUserData.tileData = tileData
        getOrCreateTileUserDataDao(content.tableName).createOrUpdate(tileUserData)
        // last_modified is set via raw SQL because the column isn't on the ORM entity (see
        // GpkgTileUserData — declaring it would break reads of pre-eviction / third-party
        // GPKGs that don't have the column). Gated on the session cache so un-migrated tables
        // cost zero — no SQL prepare, no swallowed exception. WHERE uses the PK we just got
        // from createOrUpdate (PK lookup, no composite-index scan).
        if (content.tableName in tablesWithLastModified) {
            val escapedTable = content.tableName.replace("\"", "\"\"")
            geoPackage.database.execSQL(
                "UPDATE \"$escapedTable\" SET $LAST_MODIFIED_COLUMN = ${System.currentTimeMillis()} " +
                    "WHERE ${GpkgTileUserData.ID} = ${tileUserData.id}"
            )
        }
        // Update content last modified date
        content.lastChange = Date()
        contentDao.update(content)
    }

    /**
     * Evict image/elevation tiles per [policy]. Same algorithm as [evictFeatures] but operates
     * on the GPKG tile-user-data table (`zoom_level`, `tile_column`, `tile_row`, `tile_data`,
     * `last_modified`). No-op when read-only or unbounded.
     */
    suspend fun evictTiles(
        content: GpkgContent, policy: CacheEvictionPolicy,
    ) = withContext(writeDispatcher) {
        if (isReadOnly || policy.isUnbounded) return@withContext
        if (content.tableName !in tablesWithLastModified) {
            // Column missing (migration was skipped or failed). Eviction needs it, so degrade
            // gracefully — log once and skip instead of throwing on the DELETE.
            logMessage(WARN, "GeoPackage", "evictTiles",
                "Skipped eviction for '${content.tableName}': last_modified column unavailable")
            return@withContext
        }
        val escapedTable = content.tableName.replace("\"", "\"\"")
        val q = "\"$escapedTable\""

        if (policy.maxAge != Duration.INFINITE) {
            val cutoff = System.currentTimeMillis() - policy.maxAge.inWholeMilliseconds
            geoPackage.database.execSQL("DELETE FROM $q WHERE COALESCE($LAST_MODIFIED_COLUMN, 0) < $cutoff")
        }

        if (policy.maxEntries < Long.MAX_VALUE) {
            geoPackage.database.execSQL(
                "DELETE FROM $q WHERE id IN (" +
                        "SELECT id FROM $q " +
                        "ORDER BY COALESCE($LAST_MODIFIED_COLUMN, 0) ASC, id ASC " +
                        "LIMIT MAX(0, (SELECT COUNT(*) FROM $q) - ${policy.maxEntries})" +
                        ")"
            )
        }
        // maxBytes: same multi-column-cursor limitation as features. Use maxEntries as proxy.
    }

    /**
     * HTTP revalidation metadata (`ETag`, `Last-Modified`) for the tile at `(z, x, y)` in
     * [content]. Returns null when no revalidation has been stored for this tile.
     *
     * Lives in a worldwind-private side table; the OGC tile-user-data table stays clean for
     * external readers. Any HTTP-fetched tile cache (image, vector, elevation) can use this.
     */
    suspend fun readTileRevalidation(
        content: GpkgContent, z: Int, x: Int, y: Int,
    ): GpkgTileRevalidation? = withContext(Dispatchers.IO) {
        if (!tileRevalidationDao.isTableExists) return@withContext null
        tileRevalidationDao.queryForId(GpkgTileRevalidation.keyOf(content.tableName, z, x, y))
    }

    /** Upsert revalidation headers for the tile at `(z, x, y)`. Creates the side table on first use. */
    @Throws(IllegalStateException::class)
    suspend fun writeTileRevalidation(
        content: GpkgContent, z: Int, x: Int, y: Int,
        etag: String?, httpLastModified: String?,
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile revalidation cannot be saved. GeoPackage is read-only!")
        if (etag == null && httpLastModified == null) return@withContext
        if (!tileRevalidationDao.isTableExists) {
            TableUtils.createTableIfNotExists(connectionSource, GpkgTileRevalidation::class.java)
        }
        val row = GpkgTileRevalidation().apply {
            id = GpkgTileRevalidation.keyOf(content.tableName, z, x, y)
            tableName = content.tableName
            zoomLevel = z
            tileColumn = x
            tileRow = y
            this.etag = etag
            this.httpLastModified = httpLastModified
        }
        tileRevalidationDao.createOrUpdate(row)
    }

    /** Drop every revalidation row tied to [content]. Called when the content table is cleared. */
    @Throws(IllegalStateException::class)
    suspend fun clearTileRevalidation(content: GpkgContent): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile revalidation cannot be cleared. GeoPackage is read-only!")
        if (!tileRevalidationDao.isTableExists) return@withContext
        tileRevalidationDao.deleteBuilder().apply {
            where().eq(GpkgTileRevalidation.COLUMN_TABLE_NAME, content.tableName)
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
    suspend fun getRenderables(content: GpkgContent) = withContext(Dispatchers.IO) {
        require(content.dataTypeName.equals(FEATURES, ignoreCase = true)) {
            "Unsupported GeoPackage content data_type: ${content.dataTypeName}"
        }
        val srs = content.srs?.also { srsDao.refresh(it) }
        require(srs != null && srs.organization.equals(EPSG, ignoreCase = true)
                && (srs.organizationCoordsysId == EPSG_3857 || srs.organizationCoordsysId == EPSG_4326)) {
            "Unsupported GeoPackage spatial reference system: ${srs?.srsName ?: "undefined"}"
        }
        getFeatureList(geoPackage, content.tableName).flatMap { (geometry, style) ->
            geometryToRenderables(geometry, style, srs.srsId)
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
        ensureLastModifiedColumn(persistedContent.tableName)
        persistedContent
    }

    /** Refresh the [GpkgContent] identifier + bbox to match the layer's current
     *  [LevelSet.sector] and [displayName]. The level-set sector is the in-memory
     *  source of truth for the layer's data extent; this call persists it back to gpkg
     *  so the round-trip is safe — open via [tryRecoverLevelSet] copies the persisted
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
        ensureLastModifiedColumn(persistedContent.tableName)
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
        ensureLastModifiedColumn(content.tableName)
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
            // Pre-eviction features tables may not have last_modified yet; migrate on reopen.
            if (!isReadOnly) ensureLastModifiedColumn(tableName)
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
        // List is passed to FeatureTableMetadata as `additionalColumns`, not `columns` —
        // buildColumns() auto-prepends the primary-key + geometry columns from
        // GeometryColumns, so adding them here would duplicate (NGA 6.6.7+ rejects with
        // "Duplicate column found at index: 2, Name: id"). Keep only the extras.
        val columns = listOf(
            FeatureColumn.createColumn(TILE_Z_COLUMN, GeoPackageDataType.INTEGER),
            FeatureColumn.createColumn(TILE_X_COLUMN, GeoPackageDataType.INTEGER),
            FeatureColumn.createColumn(TILE_Y_COLUMN, GeoPackageDataType.INTEGER),
            FeatureColumn.createColumn(FEATURE_PROPERTIES_COLUMN, GeoPackageDataType.TEXT),
            FeatureColumn.createColumn(LAST_MODIFIED_COLUMN, GeoPackageDataType.INTEGER),
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
        val indexName = "${tableName}_$TILE_INDEX_SUFFIX"
        val escapedTable = tableName.replace("\"", "\"\"")
        val escapedIndex = indexName.replace("\"", "\"\"")
        // Critical for tile-pyramid sources; harmless NULL entry for bulk-refresh sources.
        geoPackage.database.execSQL(
            "CREATE INDEX IF NOT EXISTS \"$escapedIndex\" ON \"$escapedTable\" " +
                    "($TILE_Z_COLUMN, $TILE_X_COLUMN, $TILE_Y_COLUMN)"
        )
        val persisted = contentDao.queryForId(tableName) ?: error(
            "Features content '$tableName' missing after createFeatureTable — the .gpkg file " +
                "may be in a half-created state. Call `deleteEntry(\"$tableName\")` and retry."
        )
        ensureLastModifiedColumn(tableName)
        persisted
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

    /** Read rows for tile `(z, x, y)`. Empty = never fetched; one null-geom row = fetched-and-empty. */
    suspend fun readFeatureTile(
        content: GpkgContent, z: Int, x: Int, y: Int,
    ): List<GpkgFeatureRow> = withContext(Dispatchers.IO) {
        readFeatureTileRows(geoPackage, content.tableName, z, x, y)
    }

    /** Replace every row for one tile and bump `last_change`. */
    @Throws(IllegalStateException::class)
    suspend fun writeFeatureTile(
        content: GpkgContent, z: Int, x: Int, y: Int, rows: List<GpkgFeatureRow>,
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Feature tile ${content.tableName}/$z/$x/$y cannot be saved. GeoPackage is read-only!")
        // Migration ran in setupFeaturesContent; per-call probes in the feature-row actuals
        // skip last_modified when absent.
        replaceFeatureTileRows(geoPackage, content.tableName, z, x, y, rows)
        content.lastChange = Date()
        contentDao.update(content)
    }

    /**
     * Add the `last_modified` column to [tableName] if missing. Upgrades GPKG files that pre-date
     * the eviction feature. No-op on read-only or when the column is already present. ALTER TABLE
     * ADD COLUMN is non-destructive — existing rows get NULL, treated as epoch 0 by [evictFeatures]
     * so external / pre-migration rows age out first.
     *
     * Called once per content during setup / reopen from every [setupTilesContent],
     * [setupVectorTilesContent], [setupGriddedCoverageContent], [setupFeaturesContent], and the
     * reopen branches in `GpkgContentManager`. **Never throws** — any migration failure (read-only
     * filesystem, schema locks, extension-table write errors, etc.) is logged at WARN and the
     * call returns, so layer instantiation cannot be blocked by an unmigratable cache. Eviction
     * and last-modified stamping then degrade gracefully: the raw-SQL writes in
     * [writeTileUserData] / [evictTiles] swallow missing-column errors.
     */
    suspend fun ensureLastModifiedColumn(tableName: String): Unit = withContext(writeDispatcher) {
        if (isReadOnly || tableName in tablesWithLastModified) return@withContext
        runCatching {
            // Probe schema first so a GPKG that already has the column never reaches ALTER and
            // never logs anything. SQLite has no IF NOT EXISTS for ADD COLUMN, so the inner
            // try/catch around ALTER is a belt-and-suspenders for Android's WAL connection pool,
            // where columnExists can read stale schema and report "no" for a column that's
            // present. Anything other than duplicate-column propagates to the outer runCatching.
            if (!geoPackage.database.columnExists(tableName, LAST_MODIFIED_COLUMN)) {
                val escapedTable = tableName.replace("\"", "\"\"")
                try {
                    geoPackage.database.execSQL(
                        "ALTER TABLE \"$escapedTable\" ADD COLUMN $LAST_MODIFIED_COLUMN INTEGER"
                    )
                } catch (e: Exception) {
                    if (e.message?.contains("duplicate column", ignoreCase = true) != true) throw e
                }
            }
            registerLastModifiedExtension(tableName)
            tablesWithLastModified += tableName
        }.onFailure {
            logMessage(WARN, "GeoPackage", "ensureLastModifiedColumn",
                "Skipped last_modified migration for '$tableName': ${it.message}")
        }
    }

    /**
     * True when [ensureLastModifiedColumn] has successfully verified or added `last_modified` on
     * [tableName] this session. Cache-write paths (`writeTileUserData`, the feature-row actuals)
     * consult this before issuing any `last_modified`-touching SQL so an un-migrated table costs
     * nothing instead of a wasted statement + caught exception per row.
     */
    fun hasLastModifiedColumn(tableName: String): Boolean = tableName in tablesWithLastModified

    /**
     * Evict rows from a features cache table per [policy]. Honors all three caps:
     *  - [CacheEvictionPolicy.maxAge]: deletes rows where `last_modified < (now - maxAge)`
     *  - [CacheEvictionPolicy.maxEntries]: keeps the N most-recently-modified
     *  - [CacheEvictionPolicy.maxBytes]: deletes oldest rows until under cap (best-effort)
     *
     * No-op when GeoPackage is read-only or the policy is unbounded.
     */
    suspend fun evictFeatures(
        content: GpkgContent, policy: CacheEvictionPolicy,
    ) = withContext(writeDispatcher) {
        if (isReadOnly || policy.isUnbounded) return@withContext
        if (content.tableName !in tablesWithLastModified) {
            logMessage(WARN, "GeoPackage", "evictFeatures",
                "Skipped eviction for '${content.tableName}': last_modified column unavailable")
            return@withContext
        }
        val escapedTable = content.tableName.replace("\"", "\"\"")
        val q = "\"$escapedTable\""

        if (policy.maxAge != Duration.INFINITE) {
            val cutoff = System.currentTimeMillis() - policy.maxAge.inWholeMilliseconds
            geoPackage.database.execSQL(
                "DELETE FROM $q WHERE COALESCE($LAST_MODIFIED_COLUMN, 0) < $cutoff"
            )
        }

        if (policy.maxEntries < Long.MAX_VALUE) {
            geoPackage.database.execSQL(
                "DELETE FROM $q WHERE $FEATURE_ID_COLUMN IN (" +
                        "SELECT $FEATURE_ID_COLUMN FROM $q " +
                        "ORDER BY COALESCE($LAST_MODIFIED_COLUMN, 0) ASC, $FEATURE_ID_COLUMN ASC " +
                        "LIMIT MAX(0, (SELECT COUNT(*) FROM $q) - ${policy.maxEntries})" +
                        ")"
            )
        }

        // maxBytes is intentionally not implemented for GPKG — would require multi-column cursor
        // walks that aren't on the public mil.nga.geopackage connection surface. Use maxEntries
        // as a proxy ("N tiles" ≈ "N × typical_tile_size bytes"). IDB / Cache API honor maxBytes
        // directly since their cursor APIs expose size cheaply.
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
        registerExtension(tableName = null, columnName = null, extensionName = WW_WEB_SERVICE_EXTENSION)
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

    /** Register the `worldwind_last_modified` extension for [tableName]. Idempotent. */
    protected open fun registerLastModifiedExtension(tableName: String) {
        registerExtension(tableName = tableName, columnName = LAST_MODIFIED_COLUMN,
            extensionName = WW_LAST_MODIFIED_EXTENSION)
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
        const val TILE_Z_COLUMN = "tile_z"
        const val TILE_X_COLUMN = "tile_x"
        const val TILE_Y_COLUMN = "tile_y"
        const val TILE_INDEX_SUFFIX = "tile_idx"
        /** Epoch-ms timestamp written on every insert. NULL = row written by an external tool
         *  that doesn't populate the column — treated as epoch 0 by the TTL sweep, so external
         *  data ages out first. */
        const val LAST_MODIFIED_COLUMN = "last_modified"

        /** Author-prefixed names registered in `gpkg_extensions` so external OGC tools see our
         *  custom additions (the worldwind web-service table + per-row last_modified column) as
         *  documented extensions rather than anonymous extra rows / columns. */
        const val WW_WEB_SERVICE_EXTENSION = "worldwind_web_service"
        const val WW_LAST_MODIFIED_EXTENSION = "worldwind_last_modified"
        const val WW_EXTENSION_DEFINITION = "https://worldwind.earth"

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