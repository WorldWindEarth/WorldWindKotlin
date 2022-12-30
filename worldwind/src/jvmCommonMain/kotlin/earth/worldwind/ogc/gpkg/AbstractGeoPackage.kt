package earth.worldwind.ogc.gpkg

import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.geom.TileMatrix
import earth.worldwind.geom.TileMatrixSet
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.util.LevelSet
import earth.worldwind.util.LevelSetConfig
import kotlinx.coroutines.runBlocking

// TODO verify its a GeoPackage container
abstract class AbstractGeoPackage(pathName: String, val isReadOnly: Boolean) {
    companion object {
        const val EPSG_3857 = 3857
        const val EPSG_4326 = 4326
    }

    val spatialReferenceSystem = mutableListOf<GpkgSpatialReferenceSystem>()
    val content = mutableListOf<GpkgContent>()
    val tileMatrixSet = mutableListOf<GpkgTileMatrixSet>()
    val tileMatrix = mutableListOf<GpkgTileMatrix>()
    val extensions = mutableListOf<GpkgExtension>()
    val griddedCoverages = mutableListOf<GpkgGriddedCoverage>()
    protected val srsIdIndex = mutableMapOf<Int, Int>()
    protected val tileMatrixSetIndex = mutableMapOf<String, Int>()
    protected val tileMatrixIndex = mutableMapOf<String, MutableMap<Int, GpkgTileMatrix>>()

    init {
        runBlocking {
            initConnection(pathName, isReadOnly)
            readSpatialReferenceSystem()
            readContent()
            readTileMatrixSet()
            readTileMatrix()
            readExtension()
            readGriddedCoverage()
        }
    }

    fun getSpatialReferenceSystem(id: Int?) = srsIdIndex[id]?.let { spatialReferenceSystem[it] }

    fun getTileMatrixSet(tableName: String) = tileMatrixSetIndex[tableName]?.let { tileMatrixSet[it] }

    fun getTileMatrix(tableName: String) = tileMatrixIndex[tableName]

    suspend fun readTileUserData(tiles: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int) =
        readTileUserData(tiles.tableName, zoomLevel, tileColumn, tileRow)

    suspend fun writeTileUserData(tiles: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int, tileData: ByteArray) {
        if (isReadOnly) error("Tile cannot be saved. GeoPackage is read-only!")
        val tileUserData = readTileUserData(tiles.tableName, zoomLevel, tileColumn, tileRow)?.also { it.tileData = tileData }
            ?: GpkgTileUserData(this, -1, zoomLevel, tileColumn, tileRow, tileData)
        writeTileUserData(tiles.tableName, tileUserData)
    }

    suspend fun writeGriddedTile(
        tiles: GpkgContent, zoomLevel: Int, tileColumn: Int, tileRow: Int, scale: Double = 1.0, offset: Double = 0.0,
        min: Double? = null, max: Double? = null, mean: Double? = null, stdDev: Double? = null
    ) {
        if (isReadOnly) error("Tile cannot be saved. GeoPackage is read-only!")
        readTileUserData(tiles.tableName, zoomLevel, tileColumn, tileRow)?.let{ tileUserData ->
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

    fun buildLevelSetConfig(content: GpkgContent): LevelSetConfig {
        require(content.dataType.equals("tiles", true)) {
            "Unsupported GeoPackage content data_type: " + content.dataType
        }
        val srs = getSpatialReferenceSystem(content.srsId)
        require(srs != null && srs.organization.equals("EPSG", true)
                && (srs.organizationCoordSysId == EPSG_3857 || srs.organizationCoordSysId == EPSG_4326)) {
            "Unsupported GeoPackage spatial reference system: " + (srs?.srsName ?: "undefined")
        }
        val tileMatrixSet = getTileMatrixSet(content.tableName)
        require(tileMatrixSet != null && tileMatrixSet.srsId == content.srsId) { "Unsupported GeoPackage tile matrix set" }
        val tileMatrix = getTileMatrix(content.tableName)
        require(!tileMatrix.isNullOrEmpty()) { "Unsupported GeoPackage tile matrix" }
        // Determine tile matrix zoom range. Not the same as tile metrics min and max zoom level!
        val zoomLevels = tileMatrix.keys.sorted()
        val minZoom = zoomLevels[0]
        val maxZoom = zoomLevels[zoomLevels.size - 1]
        // Create layer config based on tile matrix set bounding box and available matrix zoom range
        return LevelSetConfig().apply {
            sector.setDegrees(
                tileMatrixSet.minY, tileMatrixSet.minX,
                tileMatrixSet.maxY - tileMatrixSet.minY,
                tileMatrixSet.maxX - tileMatrixSet.minX
            )
            tileOrigin.setDegrees(tileMatrixSet.minY, tileMatrixSet.minX)
            firstLevelDelta = fromDegrees(
                (tileMatrixSet.maxY - tileMatrixSet.minY) / tileMatrix[minZoom]!!.matrixHeight,
                (tileMatrixSet.maxX - tileMatrixSet.minX) / tileMatrix[minZoom]!!.matrixWidth
            )
            numLevels = maxZoom - minZoom + 1
        }
    }

    // TODO What if data already exists?
    suspend fun setupTilesContent(
        tableName: String, identifier: String, levelSet: LevelSet, isWebp: Boolean = false
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
        val minX = levelSet.sector.minLongitude.inDegrees
        val minY = levelSet.sector.minLatitude.inDegrees
        val maxX = levelSet.sector.maxLongitude.inDegrees
        val maxY = levelSet.sector.maxLatitude.inDegrees
        writeMatrixSet(GpkgTileMatrixSet(this, tableName, srsId, minX, minY, maxX, maxY))
        setupTileMatrices(tableName, levelSet)
        createTilesTable(tableName)
        if (isWebp) writeExtension(
            GpkgExtension(
                this@AbstractGeoPackage, tableName, "tile_data", "gpkg_webp",
                "GeoPackage 1.0 Specification Annex P", "read-write"
            )
        )
        // Content bounding box can be smaller than matrix set bounding box and describes selected area on the lowest level
        val content = GpkgContent(this, tableName, "tiles", identifier, "", "", minX, minY, maxX, maxY, srsId)
        writeContent(content)
        return content
    }

    suspend fun setupTileMatrices(tableName: String, levelSet: LevelSet) {
        if (isReadOnly) error("Content $tableName cannot be updated. GeoPackage is read-only!")
        for (i in 0 until levelSet.numLevels) levelSet.level(i)?.run {
            getTileMatrix(tableName)?.get(levelNumber) ?: run {
                val matrixWidth = levelWidth / tileWidth
                val matrixHeight = levelHeight / tileHeight
                val pixelXSize = levelSet.sector.deltaLongitude.inDegrees / levelWidth
                val pixelYSize = levelSet.sector.deltaLatitude.inDegrees / levelHeight
                writeMatrix(
                    GpkgTileMatrix(
                        this@AbstractGeoPackage, tableName, levelNumber,
                        matrixWidth, matrixHeight, tileWidth, tileHeight, pixelXSize, pixelYSize
                    )
                )
            }
        }
    }

    fun buildTileMatrixSet(content: GpkgContent): TileMatrixSet {
        require(content.dataType.equals("2d-gridded-coverage", true)) {
            "Unsupported GeoPackage content data_type: " + content.dataType
        }
        val srs = getSpatialReferenceSystem(content.srsId)
        require(srs != null && srs.organization.equals("EPSG", true) && srs.organizationCoordSysId == EPSG_4326) {
            "Unsupported GeoPackage spatial reference system: " + (srs?.srsName ?: "undefined")
        }
        val tileMatrixSet = getTileMatrixSet(content.tableName)
        require(tileMatrixSet != null && tileMatrixSet.srsId == content.srsId) { "Unsupported GeoPackage tile matrix set" }
        val tileMatrix = getTileMatrix(content.tableName)
        require(!tileMatrix.isNullOrEmpty()) { "Unsupported GeoPackage tile matrix" }
        val sector = fromDegrees(
            tileMatrixSet.minY, tileMatrixSet.minX,
            tileMatrixSet.maxY - tileMatrixSet.minY,
            tileMatrixSet.maxX - tileMatrixSet.minX
        )
        val tileMatrixList = tileMatrix.values.sortedBy { m -> m.zoomLevel }.map { m ->
            TileMatrix(sector, m.zoomLevel, m.matrixWidth, m.matrixHeight, m.tileWidth, m.tileHeight)
        }.toList()
        return TileMatrixSet(sector, tileMatrixList)
    }

    // TODO What if data already exists?
    suspend fun setupGriddedCoverageContent(tableName: String, identifier: String, tileMatrixSet: TileMatrixSet, isFloat: Boolean = false): GpkgContent {
        if (isReadOnly) error("Content $tableName cannot be created. GeoPackage is read-only!")
        createRequiredTables()
        createGriddedCoverageTables()
        writeDefaultSpatialReferenceSystems()
        writeEPSG4326SpatialReferenceSystem()
        val srsId = EPSG_4326
        val minX = tileMatrixSet.sector.minLongitude.inDegrees
        val minY = tileMatrixSet.sector.minLatitude.inDegrees
        val maxX = tileMatrixSet.sector.maxLongitude.inDegrees
        val maxY = tileMatrixSet.sector.maxLatitude.inDegrees
        writeMatrixSet(GpkgTileMatrixSet(this, tableName, srsId, minX, minY, maxX, maxY))
        for (tileMatrix in tileMatrixSet.entries) tileMatrix.run {
            val pixelXSize = tileMatrixSet.sector.deltaLongitude.inDegrees / matrixWidth / tileWidth
            val pixelYSize = tileMatrixSet.sector.deltaLatitude.inDegrees / matrixHeight / tileHeight
            writeMatrix(GpkgTileMatrix(this@AbstractGeoPackage, tableName, ordinal, matrixWidth, matrixHeight, tileWidth, tileHeight, pixelXSize, pixelYSize))
        }
        createTilesTable(tableName)
        writeGriddedCoverage(
            GpkgGriddedCoverage(
                container = this,
                tileMatrixSetName = tableName,
                datatype = if (isFloat) "float" else "integer",
                dataNull = if (isFloat) 3.40282346638529e+38 else 65535.0
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
        val content = GpkgContent(this, tableName, "2d-gridded-coverage", identifier, "", "", minX, minY, maxX, maxY, srsId)
        writeContent(content)
        return content
    }

    /**
     * Delete specified content table and its related metadata
     */
    suspend fun deleteContent(tableName: String) {
        if (isReadOnly) error("Content $tableName cannot be deleted. GeoPackage is read-only!")

        // Remove all tiles of specified content table, including gridded tile data
        dropTilesTable(tableName)
        deleteGriddedTiles(tableName)

        // Remove metadata of specified content table
        content.iterator().let {
            while (it.hasNext()) {
                val content = it.next()
                if (content.tableName == tableName) {
                    deleteContent(content)
                    it.remove()
                    break
                }
            }
        }

        // Remove tile matrix set related to specified content table
        tileMatrixSetIndex.remove(tableName)
        tileMatrixSet.iterator().let {
            while (it.hasNext()) {
                val matrixSet = it.next()
                if (matrixSet.tableName == tableName) {
                    deleteMatrixSet(matrixSet)
                    it.remove()
                    break
                }
            }
        }

        // Remove all tile matrices related to specified content table
        tileMatrixIndex.remove(tableName)
        tileMatrix.iterator().let {
            while (it.hasNext()) {
                val matrix = it.next()
                if (matrix.tableName == tableName) {
                    deleteMatrix(matrix)
                    it.remove()
                }
            }
        }

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
        griddedCoverages.iterator().let {
            while (it.hasNext()) {
                val coverage = it.next()
                if (coverage.tileMatrixSetName == tableName) {
                    deleteGriddedCoverage(coverage)
                    it.remove()
                    break
                }
            }
        }
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
        val index = spatialReferenceSystem.size
        spatialReferenceSystem.add(system)
        srsIdIndex[system.srsId] = index
    }

    protected open fun addContent(content: GpkgContent) { this.content.add(content) }

    protected open fun addMatrixSet(matrixSet: GpkgTileMatrixSet) {
        val index = tileMatrixSet.size
        tileMatrixSet.add(matrixSet)
        tileMatrixSetIndex[matrixSet.tableName] = index
        tileMatrixIndex[matrixSet.tableName] = mutableMapOf()
    }

    protected open fun addMatrix(matrix: GpkgTileMatrix) {
        tileMatrix.add(matrix)
        tileMatrixIndex[matrix.tableName]?.put(matrix.zoomLevel, matrix)
    }

    protected open fun addExtension(extension: GpkgExtension) { extensions.add(extension) }

    protected open fun addGriddedCoverage(griddedCoverage: GpkgGriddedCoverage) { griddedCoverages.add(griddedCoverage) }

    protected abstract suspend fun initConnection(pathName: String, isReadOnly: Boolean)

    protected abstract suspend fun createRequiredTables()
    protected abstract suspend fun createGriddedCoverageTables()
    protected abstract suspend fun createTilesTable(tableName: String)

    protected abstract suspend fun writeSpatialReferenceSystem(srs: GpkgSpatialReferenceSystem)
    protected abstract suspend fun writeContent(content: GpkgContent)
    protected abstract suspend fun writeMatrixSet(matrixSet: GpkgTileMatrixSet)
    protected abstract suspend fun writeMatrix(matrix: GpkgTileMatrix)
    protected abstract suspend fun writeExtension(extension: GpkgExtension)
    protected abstract suspend fun writeGriddedCoverage(griddedCoverage: GpkgGriddedCoverage)
    protected abstract suspend fun writeGriddedTile(griddedTile: GpkgGriddedTile)
    protected abstract suspend fun writeTileUserData(tableName: String, userData: GpkgTileUserData)

    protected abstract suspend fun readSpatialReferenceSystem()
    protected abstract suspend fun readContent()
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

    protected abstract suspend fun dropTilesTable(tableName: String)
}