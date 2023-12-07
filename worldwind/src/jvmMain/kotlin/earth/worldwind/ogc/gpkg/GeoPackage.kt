package earth.worldwind.ogc.gpkg

actual open class GeoPackage actual constructor(pathName: String, isReadOnly: Boolean): AbstractGeoPackage(pathName, isReadOnly) {
    override suspend fun initConnection(pathName: String, isReadOnly: Boolean) {
        TODO("Not yet implemented")
    }

    override suspend fun createRequiredTables() {
        TODO("Not yet implemented")
    }

    override suspend fun createGriddedCoverageTables() {
        TODO("Not yet implemented")
    }

    override suspend fun createTilesTable(tableName: String) {
        TODO("Not yet implemented")
    }

    override suspend fun writeSpatialReferenceSystem(srs: GpkgSpatialReferenceSystem) {
        TODO("Not yet implemented")
    }

    override suspend fun writeContent(content: GpkgContent) {
        TODO("Not yet implemented")
    }

    override suspend fun writeWebService(service: GpkgWebService) {
        TODO("Not yet implemented")
    }

    override suspend fun writeMatrixSet(matrixSet: GpkgTileMatrixSet) {
        TODO("Not yet implemented")
    }

    override suspend fun writeMatrix(matrix: GpkgTileMatrix) {
        TODO("Not yet implemented")
    }

    override suspend fun writeExtension(extension: GpkgExtension) {
        TODO("Not yet implemented")
    }

    override suspend fun writeGriddedCoverage(griddedCoverage: GpkgGriddedCoverage) {
        TODO("Not yet implemented")
    }

    override suspend fun writeGriddedTile(griddedTile: GpkgGriddedTile) {
        TODO("Not yet implemented")
    }

    override suspend fun writeTileUserData(tableName: String, userData: GpkgTileUserData) {
        TODO("Not yet implemented")
    }

    override suspend fun readSpatialReferenceSystem() {
        TODO("Not yet implemented")
    }

    override suspend fun readContent() {
        TODO("Not yet implemented")
    }

    override suspend fun readWebService() {
        TODO("Not yet implemented")
    }

    override suspend fun readTileMatrixSet() {
        TODO("Not yet implemented")
    }

    override suspend fun readTileMatrix() {
        TODO("Not yet implemented")
    }

    override suspend fun readExtension() {
        TODO("Not yet implemented")
    }

    override suspend fun readGriddedCoverage() {
        TODO("Not yet implemented")
    }

    override suspend fun readGriddedTile(tableName: String, tileId: Int): GpkgGriddedTile? {
        TODO("Not yet implemented")
    }

    override suspend fun readTileUserData(tableName: String, zoom: Int, column: Int, row: Int): GpkgTileUserData? {
        TODO("Not yet implemented")
    }

    override suspend fun deleteContent(content: GpkgContent) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteMatrixSet(matrixSet: GpkgTileMatrixSet) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteMatrix(matrix: GpkgTileMatrix) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteExtension(extension: GpkgExtension) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteGriddedCoverage(griddedCoverage: GpkgGriddedCoverage) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteGriddedTiles(tableName: String) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteWebService(service: GpkgWebService) {
        TODO("Not yet implemented")
    }

    override suspend fun clearTilesTable(tableName: String) {
        TODO("Not yet implemented")
    }

    override suspend fun dropTilesTable(tableName: String) {
        TODO("Not yet implemented")
    }
}