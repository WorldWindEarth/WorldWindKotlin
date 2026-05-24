package earth.worldwind.ogc.gpkg

import com.j256.ormlite.dao.BaseDaoImpl
import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import com.j256.ormlite.misc.TransactionManager
import com.j256.ormlite.table.DatabaseTableConfig
import com.j256.ormlite.table.TableUtils
import earth.worldwind.MR
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.layer.CacheableImageLayer
import earth.worldwind.layer.WebFeatureLayer
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
import mil.nga.geopackage.extension.WebPExtension
import mil.nga.geopackage.extension.im.vector_tiles.VectorTilesEncodingExtension
import mil.nga.geopackage.persister.DatePersister
import mil.nga.geopackage.extension.coverage.*
import mil.nga.geopackage.features.columns.GeometryColumns
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
    private val connectionSource = geoPackage.database.connectionSource
    private val srsDao = geoPackage.spatialReferenceSystemDao
    private val contentDao = geoPackage.contentsDao
    private val webServiceDao: Dao<GpkgWebService, String> = DaoManager.createDao(connectionSource, GpkgWebService::class.java)
    private val tileMatrixSetDao = geoPackage.tileMatrixSetDao
    private val tileMatrixDao = geoPackage.tileMatrixDao
    private val extensionDao = geoPackage.extensionsDao
    private val griddedCoverageDao = CoverageDataCore.getGriddedCoverageDao(geoPackage)
    private val griddedTileDao = CoverageDataCore.getGriddedTileDao(geoPackage)
    private val tileUserDataDao = mutableMapOf<String, Dao<GpkgTileUserData, Int>>()
    private val tileMatrixCache = mutableMapOf<String, Map<Int, GpkgTileMatrix>>()
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1) // Single thread dispatcher

    val isShutdown get() = !connectionSource.isOpen("")

    fun shutdown() = geoPackage.close().also {
        tileUserDataDao.clear()
        tileMatrixCache.clear()
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

    suspend fun getWebService(content: GpkgContent): GpkgWebService? = withContext(Dispatchers.IO) {
        if (webServiceDao.isTableExists) {
            webServiceDao.queryBuilder().where().eq(GpkgWebService.COLUMN_TABLE_NAME, content.tableName).queryForFirst()
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
                        result = dao.queryRawValue("SELECT SUM(LENGTH($columnName)) FROM '$tableName'")
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
        ensureLastModifiedColumn(content.tableName)
        val tileUserData = readTileUserData(content, zoomLevel, tileColumn, tileRow) ?: GpkgTileUserData().also {
            it.zoomLevel = zoomLevel
            it.tileColumn = tileColumn
            it.tileRow = tileRow
        }
        tileUserData.tileData = tileData // Replace tile data
        tileUserData.lastModified = System.currentTimeMillis()
        getOrCreateTileUserDataDao(content.tableName).createOrUpdate(tileUserData)
        // Update content last modified date
        content.lastChange = Date()
        contentDao.update(content)
    }

    /**
     * Evict image/elevation tiles per [policy]. Same algorithm as [evictFeatures] but operates
     * on the GPKG tile-user-data table (`zoom_level`, `tile_column`, `tile_row`, `tile_data`,
     * `last_modified`). No-op when read-only or unbounded.
     */
    @Throws(IllegalStateException::class)
    suspend fun evictTiles(
        content: GpkgContent, policy: CacheEvictionPolicy,
    ) = withContext(writeDispatcher) {
        if (isReadOnly || policy.isUnbounded) return@withContext
        ensureLastModifiedColumn(content.tableName)
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
        require(content.dataTypeName.equals(TILES, ignoreCase = true)) {
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

    @Throws(IllegalStateException::class)
    suspend fun setupTilesContent(
        layer: CacheableImageLayer, tableName: String, levelSet: LevelSet, setupWebLayer: Boolean
    ): GpkgContent = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")

        TransactionManager.callInTransaction(connectionSource) {
            // Ensure the necessary tables created
            geoPackage.createTileMatrixSetTable()
            geoPackage.createTileMatrixTable()

            // Write WEBP extension if necessary
            if (layer is WebImageLayer && layer.imageFormat.equals("image/webp", ignoreCase = true)) {
                WebPExtension(geoPackage).getOrCreate(tableName)
            }

            // Write the necessary SRS data
            val srs = srsDao.getOrCreateFromEpsg(if (levelSet.sector is MercatorSector) EPSG_3857 else EPSG_4326)

            // Define bounding boxes. Content bounding box can be smaller than matrix set bounding box.
            val matrixBox = buildBoundingBox(levelSet.tileOrigin, srs.id)
            val contentBox = if (levelSet.sector != levelSet.tileOrigin)
                buildBoundingBox(levelSet.sector, srs.id) else matrixBox

            // Create tile data table
            val columns = TileTable.createRequiredColumns()
            val tileTable = TileTable(tableName, columns)
            geoPackage.createTileTable(tileTable)

            // Create or update content metadata
            val content = GpkgContent().also {
                it.tableName = tableName
                it.dataTypeName = TILES
                it.identifier = layer.displayName ?: tableName
                //it.lastChange = Date()
                it.minX = contentBox.minLongitude
                it.minY = contentBox.minLatitude
                it.maxX = contentBox.maxLongitude
                it.maxY = contentBox.maxLatitude
                it.srs = srs
            }
            contentDao.create(content)

            // Write tile matrix set
            val tms = GpkgTileMatrixSet().also {
                it.contents = content
                it.srs = srs
                it.minX = matrixBox.minLongitude
                it.minY = matrixBox.minLatitude
                it.maxX = matrixBox.maxLongitude
                it.maxY = matrixBox.maxLatitude
            }
            tileMatrixSetDao.create(tms)

            content
        }.also { content ->
            setupTileMatrices(content, levelSet)
            registerLastModifiedExtension(content.tableName)
            if (setupWebLayer && layer is WebImageLayer) setupWebLayer(layer, content)
        }
    }

    suspend fun updateTilesContent(
        layer: CacheableImageLayer, tableName: String, levelSet: LevelSet, content: GpkgContent
    ): Unit = withContext(writeDispatcher) {
        val srs = srsDao.queryForId(if (levelSet.sector is MercatorSector) EPSG_3857 else EPSG_4326)
        val box = buildBoundingBox(levelSet.sector, srs.srsId)
        with(content) {
            identifier = layer.displayName ?: tableName
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

        TransactionManager.callInTransaction(connectionSource) {
            geoPackage.createTileMatrixSetTable()
            geoPackage.createTileMatrixTable()

            val srs = srsDao.getOrCreateFromEpsg(if (levelSet.sector is MercatorSector) EPSG_3857 else EPSG_4326)

            val matrixBox = buildBoundingBox(levelSet.tileOrigin, srs.id)
            val contentBox = if (levelSet.sector != levelSet.tileOrigin)
                buildBoundingBox(levelSet.sector, srs.id) else matrixBox

            // Standard GeoPackage tile pyramid table — identical schema to raster tiles.
            val columns = TileTable.createRequiredColumns()
            val tileTable = TileTable(tableName, columns)
            geoPackage.createTileTable(tileTable)

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
            contentDao.create(content)

            val tms = GpkgTileMatrixSet().also {
                it.contents = content
                it.srs = srs
                it.minX = matrixBox.minLongitude
                it.minY = matrixBox.minLatitude
                it.maxX = matrixBox.maxLongitude
                it.maxY = matrixBox.maxLatitude
            }
            tileMatrixSetDao.create(tms)

            // Register the encoding extension in the same transaction so external readers see
            // consistent metadata. The encoding decides extension_name / scope / definition.
            encoding?.getOrCreate(tableName)

            content
        }.also { content -> setupTileMatrices(content, levelSet) }
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
        TransactionManager.callInTransaction(connectionSource) {
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
    }

    @Throws(IllegalStateException::class)
    suspend fun setupWebLayer(layer: WebImageLayer, content: GpkgContent): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("WebService $content cannot be updated. GeoPackage is read-only!")
        createWebServiceTable()
        webServiceDao.createOrUpdate(
            GpkgWebService().also {
                it.tableName = content.tableName
                it.type = layer.serviceType
                it.address = layer.serviceAddress
                it.metadata = layer.serviceMetadata
                it.layerName = layer.layerName
                it.outputFormat = layer.imageFormat
                it.isTransparent = layer.isTransparent
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

    @Throws(IllegalStateException::class)
    suspend fun setupGriddedCoverageContent(
        coverage: CacheableElevationCoverage, tableName: String, setupWebCoverage: Boolean, isFloat: Boolean
    ): GpkgContent = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")

        TransactionManager.callInTransaction(connectionSource) {
            val srs = srsDao.getOrCreateFromEpsg(EPSG_4326)
            val matrixBox = buildBoundingBox(coverage.tileMatrixSet.sector, srs.id)
            val contentBox = if (coverage.sector != coverage.tileMatrixSet.sector)
                buildBoundingBox(coverage.sector, srs.id) else matrixBox
            val coverageData = createCoverageData(
                geoPackage, tableName, coverage.displayName, contentBox, srs.id, matrixBox, srs.id, isFloat
            )
            val tms = coverageData.tileMatrixSet

            griddedCoverageDao.create(
                GpkgGriddedCoverage().also {
                    it.tileMatrixSet = tms
                    it.dataType = if (isFloat) FLOAT else INTEGER
                    it.dataNull = if (isFloat) Float.MAX_VALUE.toDouble() else Short.MIN_VALUE.toDouble()
                }
            )

            tms.contents
        }.also { content ->
            setupTileMatrices(content, coverage.tileMatrixSet)
            registerLastModifiedExtension(content.tableName)
            if (setupWebCoverage && coverage is WebElevationCoverage) setupWebCoverage(coverage, content)
        }
    }

    suspend fun updateGriddedCoverageContent(
        coverage: CacheableElevationCoverage, tableName: String, content: GpkgContent
    ) = withContext(writeDispatcher) {
        val srs = srsDao.queryForId(EPSG_4326)
        val box = buildBoundingBox(coverage.sector, srs.id)
        with(content) {
            identifier = coverage.displayName ?: tableName
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
        TransactionManager.callInTransaction(connectionSource) {
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
    }

    @Throws(IllegalStateException::class)
    suspend fun setupWebCoverage(coverage: WebElevationCoverage, content: GpkgContent): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("WebService ${content.tableName} cannot be updated. GeoPackage is read-only!")
        createWebServiceTable()
        webServiceDao.createOrUpdate(
            GpkgWebService().also {
                it.tableName = content.tableName
                it.type = coverage.serviceType
                it.address = coverage.serviceAddress
                it.metadata = coverage.serviceMetadata
                it.layerName = coverage.coverageName
                it.outputFormat = coverage.outputFormat
            }
        )
    }

    /**
     * Persist the WFS / OGC API - Features service association for a FEATURES content row
     * so the next time the GeoPackage is opened the layer can be rebuilt as a web-backed
     * [WebFeatureLayer] and refreshed from the server.
     */
    @Throws(IllegalStateException::class)
    suspend fun setupWebFeatureLayer(layer: WebFeatureLayer, content: GpkgContent): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("WebService ${content.tableName} cannot be updated. GeoPackage is read-only!")
        createWebServiceTable()
        webServiceDao.createOrUpdate(
            GpkgWebService().also {
                it.tableName = content.tableName
                it.type = layer.serviceType
                it.address = layer.serviceAddress
                it.metadata = layer.serviceMetadata
                it.layerName = layer.layerName
                it.outputFormat = layer.outputFormat
            }
        )
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
        TransactionManager.callInTransaction(connectionSource) {
            val srs = srsDao.getOrCreateFromEpsg(EPSG_4326)
            val box = mil.nga.geopackage.BoundingBox(-180.0, -90.0, 180.0, 90.0)
            val geometryColumns = GeometryColumns().also {
                it.tableName = tableName
                it.columnName = FEATURE_GEOM_COLUMN
                // GEOMETRY (not POLYGON) — MVT mixes points/lines/polygons in one payload.
                it.geometryType = mil.nga.sf.GeometryType.GEOMETRY
                it.srs = srs
                it.z = 0
                it.m = 0
            }
            val columns = listOf(
                mil.nga.geopackage.features.user.FeatureColumn.createPrimaryKeyColumn(FEATURE_ID_COLUMN),
                mil.nga.geopackage.features.user.FeatureColumn.createGeometryColumn(FEATURE_GEOM_COLUMN, mil.nga.sf.GeometryType.GEOMETRY),
                mil.nga.geopackage.features.user.FeatureColumn.createColumn(TILE_Z_COLUMN, mil.nga.geopackage.db.GeoPackageDataType.INTEGER),
                mil.nga.geopackage.features.user.FeatureColumn.createColumn(TILE_X_COLUMN, mil.nga.geopackage.db.GeoPackageDataType.INTEGER),
                mil.nga.geopackage.features.user.FeatureColumn.createColumn(TILE_Y_COLUMN, mil.nga.geopackage.db.GeoPackageDataType.INTEGER),
                mil.nga.geopackage.features.user.FeatureColumn.createColumn(FEATURE_PROPERTIES_COLUMN, mil.nga.geopackage.db.GeoPackageDataType.TEXT),
                mil.nga.geopackage.features.user.FeatureColumn.createColumn(LAST_MODIFIED_COLUMN, mil.nga.geopackage.db.GeoPackageDataType.INTEGER),
            )
            val metadata = mil.nga.geopackage.features.user.FeatureTableMetadata.create(geometryColumns, columns, box)
            metadata.identifier = displayName ?: tableName
            geoPackage.createFeatureTable(metadata)
            val indexName = "${tableName}_$TILE_INDEX_SUFFIX"
            val escapedTable = tableName.replace("\"", "\"\"")
            val escapedIndex = indexName.replace("\"", "\"\"")
            // Critical for tile-pyramid sources; harmless NULL entry for bulk-refresh sources.
            geoPackage.database.execSQL(
                "CREATE INDEX IF NOT EXISTS \"$escapedIndex\" ON \"$escapedTable\" " +
                        "($TILE_Z_COLUMN, $TILE_X_COLUMN, $TILE_Y_COLUMN)"
            )
            registerLastModifiedExtension(tableName)
            contentDao.queryForId(tableName) ?: error("Features content $tableName missing after createFeatureTable")
        }
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
        ensureLastModifiedColumn(content.tableName)
        TransactionManager.callInTransaction(connectionSource) {
            truncateFeatureTable(geoPackage, content.tableName)
            if (rows.isNotEmpty()) insertCachedFeatures(geoPackage, content.tableName, rows)
        }
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
        ensureLastModifiedColumn(content.tableName)
        replaceFeatureTileRows(geoPackage, content.tableName, z, x, y, rows)
        content.lastChange = Date()
        contentDao.update(content)
    }

    /**
     * Add the `last_modified` column to [tableName] if missing. Upgrades GPKG files that pre-date
     * the eviction feature. No-op on read-only or when the column is already present. ALTER TABLE
     * ADD COLUMN is non-destructive — existing rows get NULL, treated as epoch 0 by [evictFeatures]
     * so external / pre-migration rows age out first.
     */
    @Throws(IllegalStateException::class)
    suspend fun ensureLastModifiedColumn(tableName: String): Unit = withContext(writeDispatcher) {
        if (isReadOnly) return@withContext
        val escapedTable = tableName.replace("\"", "\"\"")
        // SQLite has no IF NOT EXISTS for ADD COLUMN; ALTER then catch only the duplicate-column
        // case. Schema locks / malformed SQL / etc. must propagate so silent migrations don't mask
        // real failures.
        try {
            geoPackage.database.execSQL("ALTER TABLE \"$escapedTable\" ADD COLUMN $LAST_MODIFIED_COLUMN INTEGER")
        } catch (e: Exception) {
            if (e.message?.contains("duplicate column", ignoreCase = true) != true) throw e
        }
        registerLastModifiedExtension(tableName)
    }

    /**
     * Evict rows from a features cache table per [policy]. Honors all three caps:
     *  - [CacheEvictionPolicy.maxAge]: deletes rows where `last_modified < (now - maxAge)`
     *  - [CacheEvictionPolicy.maxEntries]: keeps the N most-recently-modified
     *  - [CacheEvictionPolicy.maxBytes]: deletes oldest rows until under cap (best-effort)
     *
     * No-op when GeoPackage is read-only or the policy is unbounded.
     */
    @Throws(IllegalStateException::class)
    suspend fun evictFeatures(
        content: GpkgContent, policy: CacheEvictionPolicy,
    ) = withContext(writeDispatcher) {
        if (isReadOnly || policy.isUnbounded) return@withContext
        ensureLastModifiedColumn(content.tableName)
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


    /**
     * Clear specified content table and keep its related metadata
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun clearContent(tableName: String): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")
        if (!contentDao.isTableExists) return@withContext
        val content = contentDao.queryForId(tableName) ?: return@withContext

        // Remove all tiles in specified content table and gridded tile data but keep the table itself
        TransactionManager.callInTransaction(connectionSource) {
            val dao = getOrCreateTileUserDataDao(tableName)
            TableUtils.dropTable(dao, true)
            TableUtils.createTable(dao)
            if (griddedTileDao.isTableExists) griddedTileDao.deleteBuilder().apply {
                where().eq(GpkgGriddedTile.COLUMN_TABLE_NAME, content.tableName)
            }.delete()
        }
    }

    /**
     * Delete specified content table and its related metadata
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun deleteContent(tableName: String): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")
        if (!contentDao.isTableExists) return@withContext
        val content = contentDao.queryForId(tableName) ?: return@withContext

        TransactionManager.callInTransaction(connectionSource) {
            // Remove specified content table and gridded tile data
            if (griddedTileDao.isTableExists) griddedTileDao.deleteBuilder().apply {
                where().eq(GpkgGriddedTile.COLUMN_TABLE_NAME, tableName)
            }.delete()
            TableUtils.dropTable(getOrCreateTileUserDataDao(tableName), true)
            removeTileUserDataDao(tableName)

            // Remove all tile matrices related to specified content table
            if (tileMatrixDao.isTableExists) tileMatrixDao.deleteBuilder().apply {
                where().eq(GpkgTileMatrix.COLUMN_TABLE_NAME, tableName)
            }.delete()

            if (tileMatrixSetDao.isTableExists) tileMatrixSetDao.queryForId(tableName)?.let { tileMatrixSet ->
                // Remove gridded coverage metadata if exists
                if (griddedCoverageDao.isTableExists) griddedCoverageDao.deleteBuilder().apply {
                    where().eq(GpkgGriddedCoverage.COLUMN_TILE_MATRIX_SET_NAME, tableName)
                }.delete()

                // Remove tile matrix set related to specified content table
                tileMatrixSetDao.delete(tileMatrixSet)
            }

            // Remove all extensions related to specified content table
            if (extensionDao.isTableExists) extensionDao.deleteBuilder().apply {
                where().eq(GpkgExtension.COLUMN_TABLE_NAME, tableName)
            }.delete()

            // Remove web service settings if exists
            if (webServiceDao.isTableExists) webServiceDao.deleteById(tableName)

            // Remove metadata of specified content table
            contentDao.delete(content)
        }
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
            it.scope = mil.nga.geopackage.extension.ExtensionScopeType.READ_WRITE
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