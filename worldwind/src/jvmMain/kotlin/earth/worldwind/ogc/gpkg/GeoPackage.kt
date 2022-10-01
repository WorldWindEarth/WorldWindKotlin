package earth.worldwind.ogc.gpkg

actual open class GeoPackage actual constructor(pathName: String, isReadOnly: Boolean): AbstractGeoPackage(pathName, isReadOnly) {
    override fun initConnection(pathName: String, isReadOnly: Boolean) {
        TODO("Not yet implemented")
    }

    override fun createRequiredTables() {
        TODO("Not yet implemented")
    }

    override fun createGriddedCoverageTables() {
        TODO("Not yet implemented")
    }

    override fun createTilesTable(tableName: String) {
        TODO("Not yet implemented")
    }

    override fun writeSpatialReferenceSystem(srs: GpkgSpatialReferenceSystem) {
        TODO("Not yet implemented")
    }

    override fun writeContent(content: GpkgContent) {
        TODO("Not yet implemented")
    }

    override fun writeMatrixSet(matrixSet: GpkgTileMatrixSet) {
        TODO("Not yet implemented")
    }

    override fun writeMatrix(matrix: GpkgTileMatrix) {
        TODO("Not yet implemented")
    }

    override fun writeExtension(extension: GpkgExtension) {
        TODO("Not yet implemented")
    }

    override fun writeGriddedCoverage(griddedCoverage: GpkgGriddedCoverage) {
        TODO("Not yet implemented")
    }

    override fun writeGriddedTile(griddedTile: GpkgGriddedTile) {
        TODO("Not yet implemented")
    }

    override fun writeTileUserData(tableName: String, userData: GpkgTileUserData) {
        TODO("Not yet implemented")
    }

    override fun readSpatialReferenceSystem() {
        TODO("Not yet implemented")
    }

    override fun readContent() {
        TODO("Not yet implemented")
    }

    override fun readTileMatrixSet() {
        TODO("Not yet implemented")
    }

    override fun readTileMatrix() {
        TODO("Not yet implemented")
    }

    override fun readExtension() {
        TODO("Not yet implemented")
    }

    override fun readGriddedCoverage() {
        TODO("Not yet implemented")
    }

    override fun readGriddedTile(tableName: String, tileId: Int): GpkgGriddedTile? {
        TODO("Not yet implemented")
    }

    override fun readTileUserData(tableName: String, zoom: Int, column: Int, row: Int): GpkgTileUserData? {
        TODO("Not yet implemented")
    }
}