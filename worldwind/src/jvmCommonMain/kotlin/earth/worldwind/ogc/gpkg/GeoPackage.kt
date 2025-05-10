package earth.worldwind.ogc.gpkg

import com.j256.ormlite.dao.BaseDaoImpl
import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import com.j256.ormlite.table.DatabaseTableConfig
import com.j256.ormlite.table.TableUtils
import earth.worldwind.geom.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.globe.elevation.coverage.CacheableElevationCoverage
import earth.worldwind.globe.elevation.coverage.WebElevationCoverage
import earth.worldwind.layer.CacheableImageLayer
import earth.worldwind.layer.WebImageLayer
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig
import earth.worldwind.util.ormlite.initConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.*

// TODO Verify its a GeoPackage container
open class GeoPackage(val pathName: String, val isReadOnly: Boolean = true) {
    private val connectionSource = initConnection(pathName, isReadOnly)
    private val srsDao: Dao<GpkgSpatialReferenceSystem, Int> =
        DaoManager.createDao(connectionSource, GpkgSpatialReferenceSystem::class.java)
    private val contentDao: Dao<GpkgContent, String> = DaoManager.createDao(connectionSource, GpkgContent::class.java)
    private val webServiceDao: Dao<GpkgWebService, String> =
        DaoManager.createDao(connectionSource, GpkgWebService::class.java)
    private val tileMatrixSetDao: Dao<GpkgTileMatrixSet, String> =
        DaoManager.createDao(connectionSource, GpkgTileMatrixSet::class.java)
    private val tileMatrixDao = DaoManager.createDao(connectionSource, GpkgTileMatrix::class.java)
    private val extensionDao = DaoManager.createDao(connectionSource, GpkgExtension::class.java)
    private val griddedCoverageDao: Dao<GpkgGriddedCoverage, Int> =
        DaoManager.createDao(connectionSource, GpkgGriddedCoverage::class.java)
    private val griddedTileDao: Dao<GpkgGriddedTile, Int> =
        DaoManager.createDao(connectionSource, GpkgGriddedTile::class.java)
    private val tileUserDataDao = mutableMapOf<String, Dao<GpkgTileUserData, Int>>()
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1) // Single thread dispatcher

    val isShutdown get() = !connectionSource.isOpen("")

    fun shutdown() = connectionSource.close()

    suspend fun countContent(dataType: String) = withContext(Dispatchers.IO) {
        if (contentDao.isTableExists) contentDao.queryBuilder().where().eq(GpkgContent.DATA_TYPE, dataType).countOf() else 0L
    }

    suspend fun getContent(tableName: String): GpkgContent? = withContext(Dispatchers.IO) {
        if (contentDao.isTableExists) contentDao.queryForId(tableName) else null
    }

    suspend fun getContent(dataType: String, tableNames: List<String>?): List<GpkgContent> = withContext(Dispatchers.IO) {
        if (contentDao.isTableExists) {
            val builder = contentDao.queryBuilder()
            val where = builder.where().eq(GpkgContent.DATA_TYPE, dataType)
            if (tableNames != null) where.and().`in`(GpkgContent.TABLE_NAME, tableNames)
            where.query()
        } else emptyList()
    }

    suspend fun getWebService(content: GpkgContent): GpkgWebService? = withContext(Dispatchers.IO) {
        if (webServiceDao.isTableExists) {
            webServiceDao.queryBuilder().where().eq(GpkgWebService.TABLE_NAME, content.tableName).queryForFirst()
        } else null
    }

    suspend fun getGriddedCoverage(content: GpkgContent): GpkgGriddedCoverage? = withContext(Dispatchers.IO) {
        if (griddedCoverageDao.isTableExists) {
            griddedCoverageDao.queryBuilder()
                .where().eq(GpkgGriddedCoverage.TILE_MATRIX_SET_NAME, content.tableName).queryForFirst()
        } else null
    }

    suspend fun getExtension(
        tableName: String, columnName: String, extensionName: String
    ): GpkgExtension? = withContext(Dispatchers.IO) {
        if (extensionDao.isTableExists){
            extensionDao.queryBuilder().where().eq(GpkgExtension.TABLE_NAME, tableName)
                .and().eq(GpkgExtension.COLUMN_NAME, columnName).and().eq(GpkgExtension.EXTENSION_NAME, extensionName)
                .queryForFirst()
        } else null
    }

    suspend fun readTilesDataSize(tableName: String) = withContext(Dispatchers.IO) {
        getTileUserDataDao(tableName).queryRawValue("SELECT SUM(LENGTH(tile_data)) FROM '$tableName'")
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
        griddedTileDao.queryBuilder().where().eq(GpkgGriddedTile.CONTENT, content)
            .and().eq(GpkgGriddedTile.TILE_ID, tileUserData.id).queryForFirst()
    }

    @Throws(IllegalStateException::class)
    suspend fun writeGriddedTile(
        content: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int, scale: Float = 1.0f, offset: Float = 0.0f,
        min: Float? = null, max: Float? = null, mean: Float? = null, stdDev: Float? = null
    ) = withContext(writeDispatcher) {
        if (isReadOnly) error("Tile cannot be saved. GeoPackage is read-only!")
        readTileUserData(content, zoomLevel, tileColumn, tileRow)?.let { tileUserData ->
            val griddedTile = readGriddedTile(content, tileUserData) ?: GpkgGriddedTile().also {
                it.content = content
                it.tileId = tileUserData.id
            }
            // Replace tile attributes
            griddedTile.scale = scale
            griddedTile.offset = offset
            griddedTile.min = min
            griddedTile.max = max
            griddedTile.mean = mean
            griddedTile.stdDev = stdDev
            griddedTileDao.createOrUpdate(griddedTile)
        }
    }

    @Throws(IllegalArgumentException::class)
    suspend fun buildLevelSetConfig(content: GpkgContent) = withContext(Dispatchers.IO) {
        require(content.dataType.equals(TILES, ignoreCase = true)) {
            "Unsupported GeoPackage content data_type: ${content.dataType}"
        }
        val srs = content.srs?.also { srsDao.refresh(it) }
        require(srs != null && srs.organization.equals(EPSG, ignoreCase = true)
                && (srs.organizationSysId == EPSG_3857 || srs.organizationSysId == EPSG_4326)) {
            "Unsupported GeoPackage spatial reference system: ${srs?.name ?: "undefined"}"
        }
        val tms = tileMatrixSetDao.queryForId(content.tableName)
        require(tms != null && tms.srs.id == srs.id) { "Unsupported GeoPackage tile matrix set" }
        val tm = content.tileMatrices?.associateBy { it.zoomLevel }
        require(!tm.isNullOrEmpty()) { "Unsupported GeoPackage tile matrix" }
        // Determine tile matrix zoom range. Not the same as tile metrics min and max zoom level!
        val zoomLevels = tm.keys.sorted()
        val minZoom = zoomLevels.first()
        val maxZoom = zoomLevels.last()
        val minTileMatrix = tm[minZoom]!!
        val tmsSector = buildSector(tms.minX, tms.minY, tms.maxX, tms.maxY, tms.srs.id)
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
        val srs = if (levelSet.sector is MercatorSector) writeEPSG3857SpatialReferenceSystem()
        else writeEPSG4326SpatialReferenceSystem()

        // Define bounding boxes. Content bounding box can be smaller than matrix set bounding box.
        val matrixBox = buildBoundingBox(levelSet.tileOrigin, srs.id)
        val contentBox = if (levelSet.sector != levelSet.tileOrigin) buildBoundingBox(levelSet.sector, srs.id) else matrixBox

        // Create or update content metadata
        val content = GpkgContent().also {
            it.tableName = tableName
            it.dataType = TILES
            it.identifier = layer.displayName ?: tableName
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
            if (layer.imageFormat.equals("image/webp", true)) extensionDao.create(
                GpkgExtension().also {
                    it.tableName = tableName
                    it.columnName = "tile_data"
                    it.extensionName = "gpkg_webp"
                    it.definition = "GeoPackage 1.0 Specification Annex P"
                    it.scope = "read-write"
                }
            )
        }

        // Write tile matrix set
        val tms = GpkgTileMatrixSet().also {
            it.tableName = content.tableName
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
        val box = buildBoundingBox(levelSet.sector, srs.id)
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
        val tm = content.tileMatrices?.associateBy { it.zoomLevel }
        for (i in 0 until levelSet.numLevels) levelSet.level(i)?.run {
            tm?.get(levelNumber) ?: run {
                val matrixWidth = levelWidth / tileWidth
                val matrixHeight = levelHeight / tileHeight
                val pixelXSize = deltaX / levelWidth
                val pixelYSize = deltaY / levelHeight
                content.tileMatrices?.add(GpkgTileMatrix().also {
                    it.content = content
                    it.zoomLevel = levelNumber
                    it.matrixWidth = matrixWidth
                    it.matrixHeight = matrixHeight
                    it.tileWidth = tileWidth
                    it.tileHeight = tileHeight
                    it.pixelXSize = pixelXSize
                    it.pixelYSize = pixelYSize
                })
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
        require(content.dataType.equals(COVERAGE, ignoreCase = true)) {
            "Unsupported GeoPackage content data_type: ${content.dataType}"
        }
        val srs = content.srs?.also { srsDao.refresh(it) }
        require(srs != null && srs.organization.equals(EPSG, ignoreCase = true) && srs.organizationSysId == EPSG_4326) {
            "Unsupported GeoPackage spatial reference system: ${srs?.name ?: "undefined"}"
        }
        val tms = tileMatrixSetDao.queryForId(content.tableName)
        require(tms != null && tms.srs.id == srs.id) { "Unsupported GeoPackage tile matrix set" }
        val tm = content.tileMatrices
        require(!tm.isNullOrEmpty()) { "Unsupported GeoPackage tile matrix" }
        val sector = buildSector(tms.minX, tms.minY, tms.maxX, tms.maxY, tms.srs.id)
        val entries = tm.sortedBy { it.zoomLevel }.map {
            TileMatrix(sector, it.zoomLevel, it.matrixWidth, it.matrixHeight, it.tileWidth, it.tileHeight)
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
        val srs = writeEPSG4326SpatialReferenceSystem()

        // Define bounding boxes. Content bounding box can be smaller than matrix set bounding box.
        val matrixBox = buildBoundingBox(coverage.tileMatrixSet.sector, srs.id)
        val contentBox = if (coverage.sector != coverage.tileMatrixSet.sector)
            buildBoundingBox(coverage.sector, srs.id) else matrixBox

        // Create or update content metadata
        val content = GpkgContent().also {
            it.tableName = tableName
            it.dataType = COVERAGE
            it.identifier = coverage.displayName ?: tableName
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
            it.tableName = content.tableName
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
                it.tileMatrixSetName = tms.tableName
                it.datatype = if (isFloat) "float" else "integer"
                it.dataNull = if (isFloat) Float.MAX_VALUE else Short.MIN_VALUE.toFloat()
            }
        )

        // Write the necessary extensions
        extensionDao.create(
            GpkgExtension().also {
                it.tableName = "gpkg_2d_gridded_coverage_ancillary"
                it.extensionName = "gpkg_2d_gridded_coverage"
                it.definition = "http://docs.opengeospatial.org/is/17-066r1/17-066r1.html"
                it.scope = "read-write"
            }
        )
        extensionDao.create(
            GpkgExtension().also {
                it.tableName = "gpkg_2d_gridded_tile_ancillary"
                it.extensionName = "gpkg_2d_gridded_coverage"
                it.definition ="http://docs.opengeospatial.org/is/17-066r1/17-066r1.html"
                it.scope = "read-write"
            }
        )
        extensionDao.create(
            GpkgExtension().also {
                it.tableName = tableName
                it.columnName = "tile_data"
                it.extensionName = "gpkg_2d_gridded_coverage"
                it.definition = "http://docs.opengeospatial.org/is/17-066r1/17-066r1.html"
                it.scope = "read-write"
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
        val tm = content.tileMatrices?.associateBy { it.zoomLevel }
        for (tileMatrix in tileMatrixSet.entries) tm?.get(tileMatrix.ordinal) ?: tileMatrix.run {
            val pixelXSize = deltaX / matrixWidth / tileWidth
            val pixelYSize = deltaY / matrixHeight / tileHeight
            content.tileMatrices?.add(GpkgTileMatrix().also {
                it.content = content
                it.zoomLevel = ordinal
                it.matrixWidth = matrixWidth
                it.matrixHeight = matrixHeight
                it.tileWidth = tileWidth
                it.tileHeight = tileHeight
                it.pixelXSize = pixelXSize
                it.pixelYSize = pixelYSize
            })
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
            where().eq(GpkgGriddedTile.CONTENT, content)
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
        tileUserDataDao[tableName]?.let { TableUtils.dropTable(it, true) }
        tileUserDataDao -= tableName
        if (griddedTileDao.isTableExists) griddedTileDao.deleteBuilder().apply {
            where().eq(GpkgGriddedTile.CONTENT, content)
        }.delete()

        if (tileMatrixSetDao.isTableExists) tileMatrixSetDao.queryForId(content.tableName)?.let { tileMatrixSet ->
            // Remove tile matrix set related to specified content table
            tileMatrixSetDao.delete(tileMatrixSet)

            // Remove gridded coverage metadata if exists
            if (griddedCoverageDao.isTableExists) griddedCoverageDao.deleteBuilder().apply {
                where().eq(GpkgGriddedCoverage.TILE_MATRIX_SET_NAME, tileMatrixSet.tableName)
            }.delete()
        }

        // Remove all tile matrices related to specified content table
        if (tileMatrixDao.isTableExists) tileMatrixDao.deleteBuilder().apply {
            where().eq(GpkgTileMatrix.CONTENT, content)
        }.delete()

        // Remove all extensions related to specified content table
        if (extensionDao.isTableExists) extensionDao.deleteBuilder().apply {
            where().eq(GpkgExtension.TABLE_NAME, content.tableName)
        }.delete()

        // Remove web service settings if exists
        if (webServiceDao.isTableExists) webServiceDao.deleteById(content.tableName)

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

    protected open suspend fun createBaseTables() = withContext(writeDispatcher) {
        TableUtils.createTableIfNotExists(connectionSource, GpkgSpatialReferenceSystem::class.java)
        TableUtils.createTableIfNotExists(connectionSource, GpkgContent::class.java)
        TableUtils.createTableIfNotExists(connectionSource, GpkgTileMatrixSet::class.java)
        TableUtils.createTableIfNotExists(connectionSource, GpkgTileMatrix::class.java)
        TableUtils.createTableIfNotExists(connectionSource, GpkgExtension::class.java)
    }

    protected open suspend fun createGriddedCoverageTables() = withContext(writeDispatcher) {
        TableUtils.createTableIfNotExists(connectionSource, GpkgGriddedCoverage::class.java)
        TableUtils.createTableIfNotExists(connectionSource, GpkgGriddedTile::class.java)
    }

    protected open suspend fun createWebServiceTable() = withContext(writeDispatcher) {
        TableUtils.createTableIfNotExists(connectionSource, GpkgWebService::class.java)
    }

    protected open suspend fun createTileTable(tableName: String) = withContext(writeDispatcher) {
        getTileUserDataDao(tableName).let { if (!it.isTableExists) TableUtils.createTable(it) }
    }

    /**
     * Undefined cartesian and geographic SRS - Requirement 11 http://www.geopackage.org/spec131/index.html
     */
    protected open suspend fun writeDefaultSpatialReferenceSystems(): Unit = withContext(writeDispatcher) {
        GpkgSpatialReferenceSystem().also {
            it.name = "Undefined cartesian SRS"
            it.id = -1
            it.organization = "NONE"
            it.organizationSysId = -1
            it.definition = "undefined"
            it.description = "undefined cartesian coordinate reference system"
            srsDao.createOrUpdate(it)
        }
        GpkgSpatialReferenceSystem().also {
            it.name = "Undefined geographic SRS"
            it.id = 0
            it.organization = "NONE"
            it.organizationSysId = 0
            it.definition = "undefined"
            it.description = "undefined geographic coordinate reference system"
            srsDao.createOrUpdate(it)
        }
    }

    protected open suspend fun writeEPSG3857SpatialReferenceSystem() = withContext(writeDispatcher) {
        GpkgSpatialReferenceSystem().also {
            it.name = "Web Mercator"
            it.id = EPSG_3857
            it.organization = "EPSG"
            it.organizationSysId = EPSG_3857
            it.definition = """PROJCS["WGS 84 / Pseudo-Mercator",GEOGCS["Popular Visualisation CRS",DATUM["Popular_Visualisation_Datum",SPHEROID["Popular Visualisation Sphere",6378137,0,AUTHORITY["EPSG","7059"]],TOWGS84[0,0,0,0,0,0,0],AUTHORITY["EPSG","6055"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.01745329251994328,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4055"]],UNIT["metre",1,AUTHORITY["EPSG","9001"]],PROJECTION["Mercator_1SP"],PARAMETER["central_meridian",0],PARAMETER["scale_factor",1],PARAMETER["false_easting",0],PARAMETER["false_northing",0],AUTHORITY["EPSG","3785"],AXIS["X",EAST],AXIS["Y",NORTH]]"""
            it.description = "Popular Visualisation Sphere"
            srsDao.createOrUpdate(it)
        }
    }

    protected open suspend fun writeEPSG4326SpatialReferenceSystem() = withContext(writeDispatcher) {
        GpkgSpatialReferenceSystem().also {
            it.name = "WGS 84 geodetic"
            it.id = EPSG_4326
            it.organization = "EPSG"
            it.organizationSysId = EPSG_4326
            it.definition = """GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.01745329251994328,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]]"""
            it.description = "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid"
            srsDao.createOrUpdate(it)
        }
    }

    protected open fun latToEPSG3857(lat: Angle) = ln(tan(PI / 4.0 + lat.inRadians / 2.0)) * Ellipsoid.WGS84.semiMajorAxis

    protected open fun lonToEPSG3857(lon: Angle) = lon.inRadians * Ellipsoid.WGS84.semiMajorAxis

    protected open fun latFromEPSG3857(y: Double) = (atan(exp(y / Ellipsoid.WGS84.semiMajorAxis)) * 2.0 - PI / 2.0).radians

    protected open fun lonFromEPSG3857(x: Double) = (x / Ellipsoid.WGS84.semiMajorAxis).radians

    protected open fun buildSector(
        minX: Double, minY: Double, maxX: Double, maxY: Double, srsId: Int
    ) = if (srsId == EPSG_3857) MercatorSector.fromSector(Sector(
        latFromEPSG3857(minY), latFromEPSG3857(maxY), lonFromEPSG3857(minX), lonFromEPSG3857(maxX)
    )) else Sector(minY.degrees, maxY.degrees, minX.degrees, maxX.degrees)

    protected open fun buildBoundingBox(sector: Sector, srsId: Int) = if (srsId == EPSG_3857) arrayOf(
        lonToEPSG3857(sector.minLongitude), latToEPSG3857(sector.minLatitude),
        lonToEPSG3857(sector.maxLongitude), latToEPSG3857(sector.maxLatitude)
    ) else arrayOf(
        sector.minLongitude.inDegrees, sector.minLatitude.inDegrees,
        sector.maxLongitude.inDegrees, sector.maxLatitude.inDegrees
    )

    @Synchronized
    protected open fun getTileUserDataDao(tableName: String) = tileUserDataDao[tableName] ?: object : BaseDaoImpl<GpkgTileUserData, Int>(
        connectionSource, DatabaseTableConfig(GpkgTileUserData::class.java, tableName, null)
    ) {}.also {
        DaoManager.registerDaoWithTableConfig(connectionSource, it)
        tileUserDataDao[tableName] = it
    }

    protected open fun initializeTileMatrices(content: GpkgContent) {
        if (content.tileMatrices == null) {
            contentDao.assignEmptyForeignCollection(content, GpkgContent.TILE_MATRICES)
            content.tileMatrices?.refreshCollection()
        }
    }

    companion object {
        const val EPSG = "EPSG"
        const val EPSG_3857 = 3857
        const val EPSG_4326 = 4326
        const val TILES = "tiles"
        const val COVERAGE = "2d-gridded-coverage"
    }
}