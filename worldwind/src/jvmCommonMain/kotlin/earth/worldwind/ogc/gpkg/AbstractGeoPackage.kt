package earth.worldwind.ogc.gpkg

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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlin.math.*

// TODO verify its a GeoPackage container
abstract class AbstractGeoPackage(val pathName: String, val isReadOnly: Boolean) {
    companion object {
        const val EPSG_3857 = 3857
        const val EPSG_4326 = 4326
    }

    val spatialReferenceSystem = mutableMapOf<Int, GpkgSpatialReferenceSystem>()
    val content = mutableMapOf<String, GpkgContent>()
    val tileMatrixSet = mutableMapOf<String, GpkgTileMatrixSet>()
    val tileMatrix = mutableMapOf<String, MutableMap<Int, GpkgTileMatrix>>()
    val extensions = mutableListOf<GpkgExtension>()
    val griddedCoverages = mutableMapOf<String, GpkgGriddedCoverage>()
    val webServices = mutableMapOf<String, GpkgWebService>()

    init {
        runBlocking {
            initConnection(pathName, isReadOnly)
            readSpatialReferenceSystem()
            readContent()
            readTileMatrixSet()
            readTileMatrix()
            readExtension()
            readGriddedCoverage()
            readWebService()
        }
    }

    suspend fun readTileUserData(tiles: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int) =
        readTileUserData(tiles.tableName, zoomLevel, tileColumn, tileRow)

    @Throws(IllegalStateException::class)
    suspend fun writeTileUserData(tiles: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int, tileData: ByteArray) {
        if (isReadOnly) error("Tile cannot be saved. GeoPackage is read-only!")
        val tileUserData = readTileUserData(tiles.tableName, zoomLevel, tileColumn, tileRow)?.also { it.tileData = tileData }
            ?: GpkgTileUserData(this, -1, zoomLevel, tileColumn, tileRow, tileData)
        writeTileUserData(tiles.tableName, tileUserData)
        tiles.lastChange = Clock.System.now()
        updateContentsLastChangeDate(tiles)
    }

    suspend fun readGriddedTile(tiles: GpkgContent, tileUserData: GpkgTileUserData) =
        readGriddedTile(tiles.tableName, tileUserData.id)

    @Throws(IllegalStateException::class)
    suspend fun writeGriddedTile(
        tiles: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int, scale: Float = 1.0f, offset: Float = 0.0f,
        min: Float? = null, max: Float? = null, mean: Float? = null, stdDev: Float? = null
    ) {
        if (isReadOnly) error("Tile cannot be saved. GeoPackage is read-only!")
        readTileUserData(tiles.tableName, zoomLevel, tileColumn, tileRow)?.let { tileUserData ->
            val griddedTile = readGriddedTile(tiles.tableName, tileUserData.id)?.also {
                it.scale = scale
                it.offset = offset
                it.min = min
                it.max = max
                it.mean = mean
                it.stdDev = stdDev
            } ?: GpkgGriddedTile(this, -1, tiles.tableName, tileUserData.id, scale, offset, min, max, mean, stdDev)
            writeGriddedTile(griddedTile)
        }
    }

    @Throws(IllegalArgumentException::class)
    fun buildLevelSetConfig(content: GpkgContent): LevelSetConfig {
        require(content.dataType.equals("tiles", true)) {
            "Unsupported GeoPackage content data_type: " + content.dataType
        }
        val srs = spatialReferenceSystem[content.srsId]
        require(srs != null && srs.organization.equals("EPSG", true)
                && (srs.organizationCoordSysId == EPSG_3857 || srs.organizationCoordSysId == EPSG_4326)) {
            "Unsupported GeoPackage spatial reference system: " + (srs?.srsName ?: "undefined")
        }
        val tms = tileMatrixSet[content.tableName]
        require(tms != null && tms.srsId == content.srsId) { "Unsupported GeoPackage tile matrix set" }
        val tm = tileMatrix[content.tableName]
        require(!tm.isNullOrEmpty()) { "Unsupported GeoPackage tile matrix" }
        // Determine tile matrix zoom range. Not the same as tile metrics min and max zoom level!
        val zoomLevels = tm.keys.sorted()
        val minZoom = zoomLevels.first()
        val maxZoom = zoomLevels.last()
        val minTileMatrix = tm[minZoom]!!
        val tmsSector = buildSector(tms.minX, tms.minY, tms.maxX, tms.maxY, tms.srsId)
        // Create layer config based on tile matrix set bounding box and available matrix zoom range
        return LevelSetConfig().apply {
            sector.copy(tmsSector)
            tileOrigin.set(tmsSector.minLatitude, tmsSector.minLongitude)
            firstLevelDelta = Location(
                tmsSector.deltaLatitude / minTileMatrix.matrixHeight * (1 shl minZoom),
                tmsSector.deltaLongitude / minTileMatrix.matrixWidth * (1 shl minZoom)
            )
            levelOffset = minZoom
            numLevels = maxZoom + 1
        }
    }

    // TODO What if data already exists?
    @Throws(IllegalStateException::class)
    suspend fun setupTilesContent(
        layer: CacheableImageLayer, tableName: String, levelSet: LevelSet, boundingSector: Sector?, setupWebLayer: Boolean
    ): GpkgContent {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")
        createRequiredTables()
        writeDefaultSpatialReferenceSystems()
        val srsId = if (levelSet.sector is MercatorSector) {
            writeEPSG3857SpatialReferenceSystem()
            EPSG_3857
        } else {
            writeEPSG4326SpatialReferenceSystem()
            EPSG_4326
        }
        var box = buildBoundingBox(levelSet.sector, srsId)
        writeMatrixSet(GpkgTileMatrixSet(this, tableName, srsId, box[0], box[1], box[2], box[3]))
        setupTileMatrices(tableName, levelSet)
        createTilesTable(tableName)
        if (layer is WebImageLayer && layer.imageFormat.equals("image/webp", true)) writeExtension(
            GpkgExtension(
                this@AbstractGeoPackage, tableName, "tile_data", "gpkg_webp",
                "GeoPackage 1.0 Specification Annex P", "read-write"
            )
        )
        // Content bounding box can be smaller than matrix set bounding box and describes selected area on the lowest level
        if (boundingSector != null) box = buildBoundingBox(boundingSector, srsId)
        val content = GpkgContent(
            container = this,
            tableName = tableName,
            dataType = "tiles",
            identifier = layer.displayName ?: tableName,
            minX = box[0],
            minY = box[1],
            maxX = box[2],
            maxY = box[3],
            srsId = srsId
        )
        writeContent(content)
        if (setupWebLayer && layer is WebImageLayer) setupWebLayer(layer, tableName)
        return content
    }

    @Throws(IllegalStateException::class)
    suspend fun setupTileMatrices(tableName: String, levelSet: LevelSet) {
        if (isReadOnly) error("Content $tableName cannot be updated. GeoPackage is read-only!")
        val tms = tileMatrixSet[tableName] ?: error("Matrix set not found")
        val deltaX = tms.maxX - tms.minX
        val deltaY = tms.maxY - tms.minY
        val tm = tileMatrix[tableName]
        for (i in 0 until levelSet.numLevels) levelSet.level(i)?.run {
            tm?.get(levelNumber) ?: run {
                val matrixWidth = levelWidth / tileWidth
                val matrixHeight = levelHeight / tileHeight
                val pixelXSize = deltaX / levelWidth
                val pixelYSize = deltaY / levelHeight
                writeMatrix(
                    GpkgTileMatrix(
                        this@AbstractGeoPackage, tableName, levelNumber,
                        matrixWidth, matrixHeight, tileWidth, tileHeight, pixelXSize, pixelYSize
                    )
                )
            }
        }
    }

    @Throws(IllegalStateException::class)
    suspend fun setupWebLayer(layer: WebImageLayer, tableName: String) {
        if (isReadOnly) error("WebService $tableName cannot be updated. GeoPackage is read-only!")
        createWebServiceTable()
        writeWebService(
            GpkgWebService(
                this, tableName, layer.serviceType, layer.serviceAddress, layer.layerName,
                layer.imageFormat, layer.isTransparent
            )
        )
    }

    @Throws(IllegalArgumentException::class)
    fun buildTileMatrixSet(content: GpkgContent): TileMatrixSet {
        require(content.dataType.equals("2d-gridded-coverage", true)) {
            "Unsupported GeoPackage content data_type: " + content.dataType
        }
        val srs = spatialReferenceSystem[content.srsId]
        require(srs != null && srs.organization.equals("EPSG", true) && srs.organizationCoordSysId == EPSG_4326) {
            "Unsupported GeoPackage spatial reference system: " + (srs?.srsName ?: "undefined")
        }
        val tms = tileMatrixSet[content.tableName]
        require(tms != null && tms.srsId == content.srsId) { "Unsupported GeoPackage tile matrix set" }
        val tm = tileMatrix[content.tableName]
        require(!tm.isNullOrEmpty()) { "Unsupported GeoPackage tile matrix" }
        val sector = buildSector(tms.minX, tms.minY, tms.maxX, tms.maxY, tms.srsId)
        val tileMatrixList = tm.values.sortedBy { m -> m.zoomLevel }.map { m ->
            TileMatrix(sector, m.zoomLevel, m.matrixWidth, m.matrixHeight, m.tileWidth, m.tileHeight)
        }.toList()
        return TileMatrixSet(sector, tileMatrixList)
    }

    // TODO What if data already exists?
    @Throws(IllegalStateException::class)
    suspend fun setupGriddedCoverageContent(
        coverage: CacheableElevationCoverage, tableName: String, boundingSector: Sector?, setupWebCoverage: Boolean, isFloat: Boolean
    ): GpkgContent {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")
        createRequiredTables()
        createGriddedCoverageTables()
        writeDefaultSpatialReferenceSystems()
        writeEPSG4326SpatialReferenceSystem()
        val srsId = EPSG_4326
        var box = buildBoundingBox(coverage.tileMatrixSet.sector, srsId)
        writeMatrixSet(GpkgTileMatrixSet(this, tableName, srsId, box[0], box[1], box[2], box[3]))
        setupTileMatrices(tableName, coverage.tileMatrixSet)
        createTilesTable(tableName)
        writeGriddedCoverage(
            GpkgGriddedCoverage(
                container = this,
                tileMatrixSetName = tableName,
                datatype = if (isFloat) "float" else "integer",
                dataNull = if (isFloat) Float.MAX_VALUE else Short.MIN_VALUE.toFloat()
            )
        )
        writeExtension(
            GpkgExtension(
                this, "gpkg_2d_gridded_coverage_ancillary", null, "gpkg_2d_gridded_coverage",
                "http://docs.opengeospatial.org/is/17-066r1/17-066r1.html", "read-write"
            )
        )
        writeExtension(
            GpkgExtension(
                this, "gpkg_2d_gridded_tile_ancillary", null, "gpkg_2d_gridded_coverage",
                "http://docs.opengeospatial.org/is/17-066r1/17-066r1.html", "read-write"
            )
        )
        writeExtension(
            GpkgExtension(
                this, tableName, "tile_data", "gpkg_2d_gridded_coverage",
                "http://docs.opengeospatial.org/is/17-066r1/17-066r1.html", "read-write"
            )
        )
        // Content bounding box can be smaller than matrix set bounding box and describes selected area on the lowest level
        if (boundingSector != null) box = buildBoundingBox(boundingSector, srsId)
        val content = GpkgContent(
            container = this,
            tableName = tableName,
            dataType = "2d-gridded-coverage",
            identifier = coverage.displayName ?: tableName,
            minX = box[0],
            minY = box[1],
            maxX = box[2],
            maxY = box[3],
            srsId = srsId
        )
        writeContent(content)
        if (setupWebCoverage && coverage is WebElevationCoverage) setupWebCoverage(coverage, tableName)
        return content
    }

    @Throws(IllegalStateException::class)
    suspend fun setupTileMatrices(tableName: String, tileMatrixSet: TileMatrixSet) {
        if (isReadOnly) error("Content $tableName cannot be updated. GeoPackage is read-only!")
        val tms = this.tileMatrixSet[tableName] ?: error("Matrix set not found")
        val deltaX = tms.maxX - tms.minX
        val deltaY = tms.maxY - tms.minY
        val tm = this.tileMatrix[tableName]
        for (tileMatrix in tileMatrixSet.entries) tm?.get(tileMatrix.ordinal) ?: tileMatrix.run {
            val pixelXSize = deltaX / matrixWidth / tileWidth
            val pixelYSize = deltaY / matrixHeight / tileHeight
            writeMatrix(GpkgTileMatrix(
                this@AbstractGeoPackage, tableName, ordinal, matrixWidth, matrixHeight,
                tileWidth, tileHeight, pixelXSize, pixelYSize
            ))
        }
    }

    @Throws(IllegalStateException::class)
    suspend fun setupWebCoverage(coverage: WebElevationCoverage, tableName: String) {
        if (isReadOnly) error("WebService $tableName cannot be updated. GeoPackage is read-only!")
        createWebServiceTable()
        writeWebService(
            GpkgWebService(
                this, tableName, coverage.serviceType, coverage.serviceAddress, coverage.coverageName, coverage.outputFormat
            )
        )
    }

    /**
     * Clear specified content table and keep its related metadata
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun clearContent(tableName: String) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")

        // Remove all tiles in specified content table and gridded tile data
        clearTilesTable(tableName)
        deleteGriddedTiles(tableName)
    }

    /**
     * Delete specified content table and its related metadata
     *
     * @throws IllegalStateException In case of read-only database.
     */
    @Throws(IllegalStateException::class)
    suspend fun deleteContent(tableName: String) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")

        // Remove specified content table and gridded tile data
        dropTilesTable(tableName)
        deleteGriddedTiles(tableName)

        // Remove metadata of specified content table
        content.remove(tableName)?.let { deleteContent(it) }

        // Remove tile matrix set related to specified content table
        tileMatrixSet.remove(tableName)?.let { deleteMatrixSet(it) }

        // Remove all tile matrices related to specified content table
        tileMatrix.remove(tableName)?.values?.forEach { deleteMatrix(it) }

        // Remove all extensions related to specified content table
        extensions.iterator().let {
            while (it.hasNext()) {
                val extension = it.next()
                if (extension.tableName == tableName) {
                    deleteExtension(extension)
                    it.remove()
                }
            }
        }

        // Remove gridded coverage metadata if exists
        griddedCoverages.remove(tableName)?.let { deleteGriddedCoverage(it) }

        // Remove web service settings if exists
        webServices.remove(tableName)?.let { deleteWebService(it) }
    }

    fun getBoundingSector(contentKey: String) = content[contentKey]?.let { content ->
        val minX = content.minX ?: return@let null
        val minY = content.minY ?: return@let null
        val maxX = content.maxX ?: return@let null
        val maxY = content.maxY ?: return@let null
        val srsId = content.srsId ?: return@let null
        buildSector(minX, minY, maxX, maxY, srsId)
    }

    /**
     * Undefined cartesian and geographic SRS - Requirement 11 http://www.geopackage.org/spec131/index.html
     */
    protected open suspend fun writeDefaultSpatialReferenceSystems() {
        writeSpatialReferenceSystem(
            GpkgSpatialReferenceSystem(
                this, "Undefined cartesian SRS", -1, "NONE", -1,
                "undefined", "undefined cartesian coordinate reference system"
            )
        )
        writeSpatialReferenceSystem(
            GpkgSpatialReferenceSystem(
                this, "Undefined geographic SRS", 0, "NONE", 0,
                "undefined", "undefined geographic coordinate reference system"
            )
        )
    }

    protected open suspend fun writeEPSG3857SpatialReferenceSystem() {
        writeSpatialReferenceSystem(
            GpkgSpatialReferenceSystem(
                this, "Web Mercator", EPSG_3857, "EPSG", EPSG_3857,
                """PROJCS["WGS 84 / Pseudo-Mercator",GEOGCS["Popular Visualisation CRS",DATUM["Popular_Visualisation_Datum",SPHEROID["Popular Visualisation Sphere",6378137,0,AUTHORITY["EPSG","7059"]],TOWGS84[0,0,0,0,0,0,0],AUTHORITY["EPSG","6055"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.01745329251994328,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4055"]],UNIT["metre",1,AUTHORITY["EPSG","9001"]],PROJECTION["Mercator_1SP"],PARAMETER["central_meridian",0],PARAMETER["scale_factor",1],PARAMETER["false_easting",0],PARAMETER["false_northing",0],AUTHORITY["EPSG","3785"],AXIS["X",EAST],AXIS["Y",NORTH]]""",
                "Popular Visualisation Sphere"
            )
        )
    }

    protected open suspend fun writeEPSG4326SpatialReferenceSystem() {
        writeSpatialReferenceSystem(
            GpkgSpatialReferenceSystem(
                this, "WGS 84 geodetic", EPSG_4326, "EPSG", EPSG_4326,
                """GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.01745329251994328,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]]""",
                "longitude/latitude coordinates in decimal degrees on the WGS 84 spheroid"
            )
        )
    }

    protected open fun addSpatialReferenceSystem(system: GpkgSpatialReferenceSystem) {
        spatialReferenceSystem[system.srsId] = system
    }

    protected open fun addContent(content: GpkgContent) { this.content[content.tableName] = content }

    protected open fun addMatrixSet(matrixSet: GpkgTileMatrixSet) {
        tileMatrixSet[matrixSet.tableName] = matrixSet
        tileMatrix[matrixSet.tableName] = mutableMapOf()
    }

    protected open fun addMatrix(matrix: GpkgTileMatrix) {
        tileMatrix[matrix.tableName]?.put(matrix.zoomLevel, matrix)
    }

    protected open fun addExtension(extension: GpkgExtension) { extensions += extension }

    protected open fun addGriddedCoverage(griddedCoverage: GpkgGriddedCoverage) {
        griddedCoverages[griddedCoverage.tileMatrixSetName] = griddedCoverage
    }

    protected open fun addWebService(service: GpkgWebService) { webServices[service.tableName] = service }

    protected open fun latToEPSG3857(lat: Angle) = ln(tan(PI / 4.0 + lat.inRadians / 2.0)) * Ellipsoid.WGS84.semiMajorAxis

    protected open fun lonToEPSG3857(lon: Angle) = lon.inRadians * Ellipsoid.WGS84.semiMajorAxis

    protected open fun latFromEPSG3857(y: Double) = (atan(exp(y / Ellipsoid.WGS84.semiMajorAxis)) * 2.0 - PI / 2.0).radians

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

    protected open fun lonFromEPSG3857(x: Double) = (x / Ellipsoid.WGS84.semiMajorAxis).radians

    protected abstract suspend fun initConnection(pathName: String, isReadOnly: Boolean)

    protected abstract suspend fun createRequiredTables()
    protected abstract suspend fun createGriddedCoverageTables()
    protected abstract suspend fun createWebServiceTable()
    protected abstract suspend fun createTilesTable(tableName: String)

    protected abstract suspend fun writeSpatialReferenceSystem(srs: GpkgSpatialReferenceSystem)
    protected abstract suspend fun writeContent(content: GpkgContent)
    protected abstract suspend fun writeWebService(service: GpkgWebService)
    protected abstract suspend fun writeMatrixSet(matrixSet: GpkgTileMatrixSet)
    protected abstract suspend fun writeMatrix(matrix: GpkgTileMatrix)
    protected abstract suspend fun writeExtension(extension: GpkgExtension)
    protected abstract suspend fun writeGriddedCoverage(griddedCoverage: GpkgGriddedCoverage)
    protected abstract suspend fun writeGriddedTile(griddedTile: GpkgGriddedTile)
    protected abstract suspend fun writeTileUserData(tableName: String, userData: GpkgTileUserData)

    protected abstract suspend fun updateContentsLastChangeDate(content: GpkgContent)

    protected abstract suspend fun readSpatialReferenceSystem()
    protected abstract suspend fun readContent()
    protected abstract suspend fun readWebService()
    protected abstract suspend fun readTileMatrixSet()
    protected abstract suspend fun readTileMatrix()
    protected abstract suspend fun readExtension()
    protected abstract suspend fun readGriddedCoverage()
    protected abstract suspend fun readGriddedTile(tableName: String, tileId: Int): GpkgGriddedTile?
    protected abstract suspend fun readTileUserData(tableName: String, zoom: Int, column: Int, row: Int): GpkgTileUserData?

    protected abstract suspend fun deleteContent(content: GpkgContent)
    protected abstract suspend fun deleteMatrixSet(matrixSet: GpkgTileMatrixSet)
    protected abstract suspend fun deleteMatrix(matrix: GpkgTileMatrix)
    protected abstract suspend fun deleteExtension(extension: GpkgExtension)
    protected abstract suspend fun deleteGriddedCoverage(griddedCoverage: GpkgGriddedCoverage)
    protected abstract suspend fun deleteGriddedTiles(tableName: String)
    protected abstract suspend fun deleteWebService(service: GpkgWebService)

    protected abstract suspend fun clearTilesTable(tableName: String)
    protected abstract suspend fun dropTilesTable(tableName: String)
}