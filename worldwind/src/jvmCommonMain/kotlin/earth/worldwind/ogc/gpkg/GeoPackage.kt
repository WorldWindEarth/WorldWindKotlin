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
import earth.worldwind.layer.CacheableImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.PathType
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mil.nga.color.Color
import mil.nga.geopackage.GeoPackageCore
import mil.nga.geopackage.contents.ContentsDataType
import mil.nga.geopackage.dgiwg.CoordinateReferenceSystem
import mil.nga.geopackage.extension.ExtensionScopeType
import mil.nga.geopackage.extension.WebPExtension
import mil.nga.geopackage.extension.coverage.CoverageDataCore
import mil.nga.geopackage.extension.coverage.GriddedCoverage
import mil.nga.geopackage.extension.coverage.GriddedCoverageDataType
import mil.nga.geopackage.extension.coverage.GriddedTile
import mil.nga.geopackage.features.columns.GeometryColumns
import mil.nga.geopackage.tiles.user.TileTable
import mil.nga.sf.*
import java.util.*
import kotlin.math.*
import mil.nga.geopackage.contents.Contents as GpkgContent
import mil.nga.geopackage.extension.Extensions as GpkgExtension
import mil.nga.geopackage.extension.coverage.GriddedCoverage as GpkgGriddedCoverage
import mil.nga.geopackage.extension.coverage.GriddedTile as GpkgGriddedTile
import mil.nga.geopackage.tiles.matrix.TileMatrix as GpkgTileMatrix
import mil.nga.geopackage.tiles.matrixset.TileMatrixSet as GpkgTileMatrixSet

typealias GpkgContent = mil.nga.geopackage.contents.Contents

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
expect fun getFeatureList(geoPackage: GeoPackageCore, tableName: String): List<Pair<Geometry, FeatureStyle?>>
expect fun buildImageSource(iconRow: IconRow): ImageSource

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
        val dao = getTileUserDataDao(tableName)
        if (dao.isTableExists) dao.queryRawValue("SELECT SUM(LENGTH(tile_data)) FROM '$tableName'") else 0L
    }

    suspend fun readTileUserData(
        content: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int
    ): GpkgTileUserData? = withContext(Dispatchers.IO) {
        getTileUserDataDao(content.tableName).queryBuilder().where().eq(GpkgTileUserData.ZOOM_LEVEL, zoomLevel)
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
        tileUserData.tileData = tileData // Replace tile data
        getTileUserDataDao(content.tableName).createOrUpdate(tileUserData)
        // Update content last modified date
        content.lastChange = Date()
        contentDao.update(content)
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
        val minZoom = zoomLevels.first().toInt()
        val maxZoom = zoomLevels.last().toInt()
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
            levelOffset = minZoom
            numLevels = maxZoom + 1
        }
    }

    @Throws(IllegalStateException::class)
    suspend fun setupTilesContent(
        layer: CacheableImageLayer, tableName: String, levelSet: LevelSet, setupWebLayer: Boolean
    ): GpkgContent = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")

        // Ensure the necessary tables created
        createBaseTables()
        createTileTable(tableName)

        // Write the necessary SRS data
        writeDefaultSpatialReferenceSystems()
        val srs = if (levelSet.sector is MercatorSector) CoordinateReferenceSystem.EPSG_3857.createSpatialReferenceSystem()
        else CoordinateReferenceSystem.EPSG_4326.createSpatialReferenceSystem()
        srsDao.createOrUpdate(srs)

        // Define bounding boxes. Content bounding box can be smaller than matrix set bounding box.
        val matrixBox = buildBoundingBox(levelSet.tileOrigin, srs.id)
        val contentBox = if (levelSet.sector != levelSet.tileOrigin) buildBoundingBox(levelSet.sector, srs.id) else matrixBox

        // Create or update content metadata
        val content = GpkgContent().also {
            it.tableName = tableName
            it.dataTypeName = TILES
            it.identifier = layer.displayName ?: tableName
            it.lastChange = Date()
            it.minX = contentBox[0]
            it.minY = contentBox[1]
            it.maxX = contentBox[2]
            it.maxY = contentBox[3]
            it.srs = srs
        }
        contentDao.createOrUpdate(content)

        // Process WebLayer
        if (layer is WebImageLayer) {
            // Write web service metadata
            if (setupWebLayer) setupWebLayer(layer, content)

            // Write WEBP extension if necessary
            if (layer.imageFormat.equals("image/webp", ignoreCase = true)) {
                WebPExtension(geoPackage).getOrCreate(tableName)
            }
        }

        // Write tile matrix set
        val tms = GpkgTileMatrixSet().also {
            it.contents = content
            it.srs = srs
            it.minX = matrixBox[0]
            it.minY = matrixBox[1]
            it.maxX = matrixBox[2]
            it.maxY = matrixBox[3]
        }
        tileMatrixSetDao.createOrUpdate(tms)
        setupTileMatrices(content, levelSet)

        content
    }

    suspend fun updateTilesContent(
        layer: CacheableImageLayer, tableName: String, levelSet: LevelSet, content: GpkgContent
    ): Unit = withContext(writeDispatcher) {
        val srs = srsDao.queryForId(if (levelSet.sector is MercatorSector) EPSG_3857 else EPSG_4326)
        val box = buildBoundingBox(levelSet.sector, srs.srsId)
        with(content) {
            identifier = layer.displayName ?: tableName
            minX = box[0]
            minY = box[1]
            maxX = box[2]
            maxY = box[3]
        }
        contentDao.update(content)
    }

    @Throws(IllegalStateException::class)
    suspend fun setupTileMatrices(content: GpkgContent, levelSet: LevelSet) = withContext(writeDispatcher) {
        if (isReadOnly) error("Content ${content.tableName} cannot be updated. GeoPackage is read-only!")
        val tms = tileMatrixSetDao.queryForId(content.tableName) ?: error("Matrix set not found")
        val deltaX = tms.maxX - tms.minX
        val deltaY = tms.maxY - tms.minY
        initializeTileMatrices(content) // Ensure foreign collection exists
        val ctm = content.tileMatrix ?: error("Tile Matrix foreign collection must not be empty at this point")
        val tm = ctm.associateBy { it.zoomLevel.toInt() }.toMutableMap().also { tileMatrixCache[content.tableName] = it }
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
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")

        // Ensure the necessary tables created
        createBaseTables()
        createGriddedCoverageTables()
        createTileTable(tableName)

        // Write the necessary SRS data
        writeDefaultSpatialReferenceSystems()
        val srs = CoordinateReferenceSystem.EPSG_4326.createSpatialReferenceSystem()
        srsDao.createOrUpdate(srs)

        // Define bounding boxes. Content bounding box can be smaller than matrix set bounding box.
        val matrixBox = buildBoundingBox(coverage.tileMatrixSet.sector, srs.id)
        val contentBox = if (coverage.sector != coverage.tileMatrixSet.sector)
            buildBoundingBox(coverage.sector, srs.id) else matrixBox

        // Create or update content metadata
        val content = GpkgContent().also {
            it.tableName = tableName
            it.dataTypeName = COVERAGE
            it.identifier = coverage.displayName ?: tableName
            it.lastChange = Date()
            it.minX = contentBox[0]
            it.minY = contentBox[1]
            it.maxX = contentBox[2]
            it.maxY = contentBox[3]
            it.srs = srs
        }
        contentDao.createOrUpdate(content)

        // Write web service metadata
        if (setupWebCoverage && coverage is WebElevationCoverage) setupWebCoverage(coverage, content)

        // Write tile matrix set
        val tms = GpkgTileMatrixSet().also {
            it.contents = content
            it.srs = srs
            it.minX = matrixBox[0]
            it.minY = matrixBox[1]
            it.maxX = matrixBox[2]
            it.maxY = matrixBox[3]
        }
        tileMatrixSetDao.createOrUpdate(tms)
        setupTileMatrices(content, coverage.tileMatrixSet)

        // Write gridded coverage metadata
        griddedCoverageDao.create(
            GpkgGriddedCoverage().also {
                it.tileMatrixSet = tms
                it.dataType = if (isFloat) FLOAT else INTEGER
                it.dataNull = if (isFloat) Float.MAX_VALUE.toDouble() else Short.MIN_VALUE.toDouble()
            }
        )

        // Write the necessary extensions
        extensionDao.create(
            GpkgExtension().also {
                it.tableName = GriddedCoverage.TABLE_NAME
                it.extensionName = CoverageDataCore.EXTENSION_NAME
                it.definition = CoverageDataCore.EXTENSION_DEFINITION
                it.scope = ExtensionScopeType.READ_WRITE
            }
        )
        extensionDao.create(
            GpkgExtension().also {
                it.tableName = GriddedTile.TABLE_NAME
                it.extensionName = CoverageDataCore.EXTENSION_NAME
                it.definition = CoverageDataCore.EXTENSION_DEFINITION
                it.scope = ExtensionScopeType.READ_WRITE
            }
        )
        extensionDao.create(
            GpkgExtension().also {
                it.tableName = tableName
                it.columnName = TileTable.COLUMN_TILE_DATA
                it.extensionName = CoverageDataCore.EXTENSION_NAME
                it.definition = CoverageDataCore.EXTENSION_DEFINITION
                it.scope = ExtensionScopeType.READ_WRITE
            }
        )

        content
    }

    suspend fun updateGriddedCoverageContent(
        coverage: CacheableElevationCoverage, tableName: String, content: GpkgContent
    ) = withContext(writeDispatcher) {
        val srs = srsDao.queryForId(EPSG_4326)
        val box = buildBoundingBox(coverage.sector, srs.id)
        with(content) {
            identifier = coverage.displayName ?: tableName
            minX = box[0]
            minY = box[1]
            maxX = box[2]
            maxY = box[3]
        }
        contentDao.update(content)
    }

    @Throws(IllegalStateException::class)
    suspend fun setupTileMatrices(content: GpkgContent, tileMatrixSet: TileMatrixSet) = withContext(writeDispatcher) {
        if (isReadOnly) error("Content ${content.tableName} cannot be updated. GeoPackage is read-only!")
        val tms = tileMatrixSetDao.queryForId(content.tableName) ?: error("Matrix set not found")
        val deltaX = tms.maxX - tms.minX
        val deltaY = tms.maxY - tms.minY
        initializeTileMatrices(content) // Ensure foreign collection exists
        val ctm = content.tileMatrix ?: error("Tile Matrix foreign collection must not be empty at this point")
        val tm = ctm.associateBy { it.zoomLevel.toInt() }.toMutableMap().also { tileMatrixCache[content.tableName] = it }
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
     * Clear specified content table and keep its related metadata
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun clearContent(tableName: String) = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")
        if (!contentDao.isTableExists) return@withContext
        val content = contentDao.queryForId(tableName) ?: return@withContext

        // Remove all tiles in specified content table and gridded tile data but keep the table itself
        tileUserDataDao[tableName]?.let {
            TableUtils.dropTable(it, true)
            TableUtils.createTable(it)
        }
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
    suspend fun deleteContent(tableName: String): Unit = withContext(writeDispatcher) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")
        if (!contentDao.isTableExists) return@withContext
        val content = contentDao.queryForId(tableName) ?: return@withContext

        // Remove specified content table and gridded tile data
        if (griddedTileDao.isTableExists) griddedTileDao.deleteBuilder().apply {
            where().eq(GpkgGriddedTile.COLUMN_TABLE_NAME, tableName)
        }.delete()
        tileUserDataDao.remove(tableName)?.let { TableUtils.dropTable(it, true) }

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

    fun getBoundingSector(content: GpkgContent): Sector? {
        val minX = content.minX ?: return null
        val minY = content.minY ?: return null
        val maxX = content.maxX ?: return null
        val maxY = content.maxY ?: return null
        val srsId = content.srs?.id ?: return null
        return buildSector(minX, minY, maxX, maxY, srsId)
    }

    fun getTileMatrix(content: GpkgContent, zoomLevel: Int): GpkgTileMatrix? = tileMatrixCache[content.tableName]?.get(zoomLevel)

    protected open fun createBaseTables() {
        with(geoPackage.tableCreator) {
            if (!contentDao.isTableExists) createContents()
            if (!tileMatrixSetDao.isTableExists) createTileMatrixSet()
            if (!tileMatrixDao.isTableExists) createTileMatrix()
            if (!extensionDao.isTableExists) createExtensions()
        }
    }

    protected open fun createGriddedCoverageTables() {
        with(geoPackage.tableCreator) {
            if (!griddedCoverageDao.isTableExists) createGriddedCoverage()
            if (!griddedTileDao.isTableExists) createGriddedTile()
        }
    }

    protected open fun createWebServiceTable() {
        if (!webServiceDao.isTableExists) TableUtils.createTable(webServiceDao)
    }

    protected open fun createTileTable(tableName: String) {
        getTileUserDataDao(tableName).let { if (!it.isTableExists) TableUtils.createTable(it) }
    }

    /**
     * Undefined cartesian and geographic SRS - Requirement 11 http://www.geopackage.org/spec131/index.html
     */
    protected open fun writeDefaultSpatialReferenceSystems() {
        runCatching { srsDao.createUndefinedCartesian() }
        runCatching { srsDao.createUndefinedGeographic() }
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

    protected open fun buildBoundingBox(sector: Sector, srsId: Long) = if (srsId == EPSG_3857) arrayOf(
        lonToEPSG3857(sector.minLongitude), latToEPSG3857(sector.minLatitude),
        lonToEPSG3857(sector.maxLongitude), latToEPSG3857(sector.maxLatitude)
    ) else arrayOf(
        sector.minLongitude.inDegrees, sector.minLatitude.inDegrees,
        sector.maxLongitude.inDegrees, sector.maxLatitude.inDegrees
    )

    protected open fun geometryToRenderables(
        geometry: Geometry, style: FeatureStyle?, srsId: Long
    ): List<Renderable> = when(geometry) {
        is Point -> listOf(
            earth.worldwind.shape.Placemark.createWithImage(
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
            earth.worldwind.shape.Path(geometry.points.map { buildPosition(it, srsId) }).apply {
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
    protected open fun getTileUserDataDao(tableName: String) = tileUserDataDao[tableName] ?: object : BaseDaoImpl<GpkgTileUserData, Int>(
        connectionSource, DatabaseTableConfig(GpkgTileUserData::class.java, tableName, null)
    ) {}.also {
        DaoManager.registerDaoWithTableConfig(connectionSource, it)
        tileUserDataDao[tableName] = it
    }

    protected open fun initializeTileMatrices(content: GpkgContent) {
        if (content.tileMatrix == null) {
            contentDao.assignEmptyForeignCollection(content, "tileMatrix")
            content.tileMatrix?.refreshCollection()
        }
    }

    companion object {
        const val EPSG = "EPSG"
        val EPSG_3857 = CoordinateReferenceSystem.EPSG_3857.code
        val EPSG_4326 = CoordinateReferenceSystem.EPSG_4326.code
        val TILES = ContentsDataType.TILES.name.lowercase()
        val FEATURES = ContentsDataType.FEATURES.name.lowercase()
        const val COVERAGE = CoverageDataCore.GRIDDED_COVERAGE
        val FLOAT = GriddedCoverageDataType.FLOAT
        val INTEGER = GriddedCoverageDataType.INTEGER
        var defaultOutlineWidth = 1f
        var defaultOutlineColor = earth.worldwind.render.Color(0f, 0f, 0f, 1f)
        var defaultInteriorColor = earth.worldwind.render.Color(0f, 0f, 0f, 0f)
    }
}