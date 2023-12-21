package earth.worldwind.ogc.gpkg

import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase.*
import java.util.concurrent.TimeUnit

// TODO parameterize column names as constants or use ORMLite
actual open class GeoPackage actual constructor(pathName: String, isReadOnly: Boolean): AbstractGeoPackage(pathName, isReadOnly) {
    private lateinit var connection: SQLiteConnection

    override suspend fun initConnection(pathName: String, isReadOnly: Boolean) {
        connection = SQLiteConnection(
            pathName, if (isReadOnly) OPEN_READONLY else OPEN_READWRITE or CREATE_IF_NECESSARY, 60, TimeUnit.SECONDS
        )
    }

    override suspend fun createRequiredTables() {
        connection.openDatabase().use { database ->
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS $SPATIAL_REF_SYS (
                    srs_name TEXT NOT NULL,
                    srs_id INTEGER NOT NULL PRIMARY KEY,
                    organization TEXT NOT NULL,
                    organization_coordsys_id INTEGER NOT NULL,
                    definition  TEXT NOT NULL,
                    description TEXT
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS $CONTENTS (
                    table_name TEXT NOT NULL PRIMARY KEY,
                    data_type TEXT NOT NULL,
                    identifier TEXT UNIQUE,
                    description TEXT DEFAULT '',
                    last_change DATETIME NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                    min_x DOUBLE,
                    min_y DOUBLE,
                    max_x DOUBLE,
                    max_y DOUBLE,
                    srs_id INTEGER,
                    CONSTRAINT fk_gc_r_srs_id FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys(srs_id)
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS $TILE_MATRIX_SET (
                    table_name TEXT NOT NULL PRIMARY KEY,
                    srs_id INTEGER NOT NULL,
                    min_x DOUBLE NOT NULL,
                    min_y DOUBLE NOT NULL,
                    max_x DOUBLE NOT NULL,
                    max_y DOUBLE NOT NULL,
                    CONSTRAINT fk_gtms_table_name FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name),
                    CONSTRAINT fk_gtms_srs FOREIGN KEY (srs_id) REFERENCES gpkg_spatial_ref_sys (srs_id)
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS $TILE_MATRIX (
                    table_name TEXT NOT NULL,
                    zoom_level INTEGER NOT NULL,
                    matrix_width INTEGER NOT NULL,
                    matrix_height INTEGER NOT NULL,
                    tile_width INTEGER NOT NULL,
                    tile_height INTEGER NOT NULL,
                    pixel_x_size DOUBLE NOT NULL,
                    pixel_y_size DOUBLE NOT NULL,
                    CONSTRAINT pk_ttm PRIMARY KEY (table_name, zoom_level),
                    CONSTRAINT fk_tmm_table_name FOREIGN KEY (table_name) REFERENCES gpkg_contents(table_name)
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS $EXTENSIONS (
                    table_name TEXT,
                    column_name TEXT,
                    extension_name TEXT NOT NULL,
                    definition TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    UNIQUE (table_name, column_name, extension_name)
                )
            """.trimIndent())
        }
    }

    override suspend fun createGriddedCoverageTables() {
        connection.openDatabase().use { database ->
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS $GRIDDED_COVERAGE_ANCILLARY (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    tile_matrix_set_name TEXT NOT NULL UNIQUE,
                    datatype TEXT NOT NULL DEFAULT 'integer',
                    scale REAL NOT NULL DEFAULT 1.0,
                    'offset' REAL NOT NULL DEFAULT 0.0,
                    precision REAL DEFAULT 1.0,
                    data_null REAL,
                    grid_cell_encoding TEXT DEFAULT 'grid-value-is-center',
                    uom TEXT,
                    field_name TEXT DEFAULT 'Height',
                    quantity_definition TEXT DEFAULT 'Height',
                    CONSTRAINT fk_g2dgtct_name FOREIGN KEY('tile_matrix_set_name') REFERENCES gpkg_tile_matrix_set (table_name),
                    CHECK (datatype in ('integer','float'))
                )
            """.trimIndent())
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS $GRIDDED_TILE_ANCILLARY (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    tpudt_name TEXT NOT NULL,
                    tpudt_id INTEGER NOT NULL,
                    scale REAL NOT NULL DEFAULT 1.0,
                    'offset' REAL NOT NULL DEFAULT 0.0,
                    min REAL DEFAULT NULL,
                    max REAL DEFAULT NULL,
                    mean REAL DEFAULT NULL,
                    std_dev REAL DEFAULT NULL,
                    CONSTRAINT fk_g2dgtat_name FOREIGN KEY (tpudt_name) REFERENCES gpkg_contents(table_name),
                    UNIQUE (tpudt_name, tpudt_id)
                )
            """.trimIndent())
        }
    }

    override suspend fun createTilesTable(tableName: String) {
        connection.openDatabase().use { database ->
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS "$tableName" (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    zoom_level INTEGER NOT NULL,
                    tile_column INTEGER NOT NULL,
                    tile_row INTEGER NOT NULL,
                    tile_data BLOB NOT NULL,
                    UNIQUE (zoom_level, tile_column, tile_row)
                )
            """.trimIndent())
        }
    }

    override suspend fun writeSpatialReferenceSystem(srs: GpkgSpatialReferenceSystem) {
        connection.openDatabase().use { database -> srs.run {
            val values = ContentValues().apply {
                put("srs_name", srsName)
                put("srs_id", srsId)
                put("organization", organization)
                put("organization_coordsys_id", organizationCoordSysId)
                put("definition", definition)
                put("description", description)
            }
            try {
                database.insertOrThrow(SPATIAL_REF_SYS, null, values)
                addSpatialReferenceSystem(this)
            } catch (_: SQLException) {
                // Handle exception manually as -1 is a valid primary key field value for spatial reference system table
            }
        } }
    }

    override suspend fun writeContent(content: GpkgContent) {
        connection.openDatabase().use { database -> content.run {
            val values = ContentValues().apply {
                put("table_name", tableName)
                put("data_type", dataType)
                put("identifier", identifier)
                put("description", description)
                put("last_change", lastChange)
                put("min_x", minX)
                put("min_y", minY)
                put("max_x", maxX)
                put("max_y", maxY)
                put("srs_id", srsId)
            }
            if (database.insert(CONTENTS, null, values) >= 0) addContent(this)
        } }
    }

    override suspend fun writeMatrixSet(matrixSet: GpkgTileMatrixSet) {
        connection.openDatabase().use { database -> matrixSet.run {
            val values = ContentValues().apply {
                put("table_name", tableName)
                put("srs_id", srsId)
                put("min_x", minX)
                put("min_y", minY)
                put("max_x", maxX)
                put("max_y", maxY)
            }
            if (database.insert(TILE_MATRIX_SET, null, values) >= 0) addMatrixSet(this)
        } }
    }

    override suspend fun writeMatrix(matrix: GpkgTileMatrix) {
        connection.openDatabase().use { database -> matrix.run {
            val values = ContentValues().apply {
                put("table_name", tableName)
                put("zoom_level", zoomLevel)
                put("matrix_width", matrixWidth)
                put("matrix_height", matrixHeight)
                put("tile_width", tileWidth)
                put("tile_height", tileHeight)
                put("pixel_x_size", pixelXSize)
                put("pixel_y_size", pixelYSize)
            }
            if (database.insert(TILE_MATRIX, null, values) >= 0) addMatrix(this)
        } }
    }

    override suspend fun writeExtension(extension: GpkgExtension) {
        connection.openDatabase().use { database -> extension.run {
            val values = ContentValues().apply {
                put("table_name", tableName)
                put("column_name", columnName)
                put("extension_name", extensionName)
                put("definition", definition)
                put("scope", scope)
            }
            if (database.insert(EXTENSIONS, null, values) >= 0) addExtension(this)
        } }
    }

    override suspend fun writeGriddedCoverage(griddedCoverage: GpkgGriddedCoverage) {
        connection.openDatabase().use { database -> griddedCoverage.run {
            val values = ContentValues().apply {
                put("tile_matrix_set_name", tileMatrixSetName)
                put("datatype", datatype)
                put("scale", scale)
                put("offset", offset)
                put("precision", precision)
                put("data_null", dataNull)
                put("grid_cell_encoding", gridCellEncoding)
                put("uom", uom)
                put("field_name", fieldName)
                put("quantity_definition", quantityDefinition)
            }
            if (database.insert(GRIDDED_COVERAGE_ANCILLARY, null, values) >= 0) addGriddedCoverage(this)
        } }
    }

    override suspend fun writeGriddedTile(griddedTile: GpkgGriddedTile) {
        connection.openDatabase().use { database -> griddedTile.run {
            val values = ContentValues().apply {
                put("tpudt_name", tpudtName)
                put("tpudt_id", tpudtId)
                put("scale", scale)
                put("offset", offset)
                put("min", min)
                put("max", max)
                put("mean", mean)
                put("std_dev", stdDev)
            }
            if (id == -1) database.insert(GRIDDED_TILE_ANCILLARY, null, values)
            else database.update(GRIDDED_TILE_ANCILLARY, values, "id=?", arrayOf(id.toString()))
        } }
    }

    override suspend fun writeTileUserData(tableName: String, userData: GpkgTileUserData) {
        connection.openDatabase().use { database -> userData.run {
            val values = ContentValues().apply {
                put("zoom_level", zoomLevel)
                put("tile_column", tileColumn)
                put("tile_row", tileRow)
                put("tile_data", tileData)
            }
            if (id == -1) database.insert(tableName, null, values)
            else database.update(tableName, values, "id=?", arrayOf(id.toString()))
        } }
    }

    override suspend fun readSpatialReferenceSystem() {
        connection.openDatabase().use { database ->
            try {
                database.rawQuery("""
                    SELECT srs_name, srs_id, organization, organization_coordsys_id, definition, description 
                    FROM '$SPATIAL_REF_SYS'
                """.trimIndent(), null).use { it.run {
                    while (moveToNext()) addSpatialReferenceSystem(
                        GpkgSpatialReferenceSystem(
                            this@GeoPackage, getString(0), getInt(1), getString(2),
                            getInt(3), getString(4), getString(5)
                        )
                    )
                } }
            } catch (_: SQLException) {
                // Skip exception. Table will be created later
            }
        }
    }

    override suspend fun readContent() {
        connection.openDatabase().use { database ->
            try {
                database.rawQuery("""
                    SELECT table_name, data_type, identifier, description, last_change, min_x, min_y, max_x, max_y, srs_id
                    FROM '$CONTENTS'
                """.trimIndent(), null).use { it.run {
                    while (moveToNext()) addContent(
                        GpkgContent(
                            this@GeoPackage, getString(0), getString(1), getString(2),
                            getString(3), getString(4), getDouble(5), getDouble(6),
                            getDouble(7), getDouble(8), getInt(9)
                        )
                    )
                } }
            } catch (_: SQLException) {
                // Skip exception. Table will be created later
            }
        }
    }

    override suspend fun readTileMatrixSet() {
        connection.openDatabase().use { database ->
            try {
                database.rawQuery(
                    "SELECT table_name, srs_id, min_x, min_y, max_x, max_y FROM '$TILE_MATRIX_SET'", null
                ).use { it.run {
                    while (moveToNext()) addMatrixSet(
                        GpkgTileMatrixSet(
                            this@GeoPackage, getString(0), getInt(1), getDouble(2),
                            getDouble(3), getDouble(4), getDouble(5)
                        )
                    )
                } }
            } catch (_: SQLException) {
                // Skip exception. Table will be created later
            }
        }
    }

    override suspend fun readTileMatrix() {
        connection.openDatabase().use { database ->
            try {
                database.rawQuery("""
                    SELECT table_name, zoom_level, matrix_width, matrix_height, tile_width, tile_height, pixel_x_size, pixel_y_size 
                    FROM '$TILE_MATRIX'
                """.trimIndent(), null).use { it.run {
                    while (moveToNext()) addMatrix(
                        GpkgTileMatrix(
                            this@GeoPackage, getString(0), getInt(1), getInt(2),
                            getInt(3), getInt(4), getInt(5), getDouble(6), getDouble(7),
                        )
                    )
                } }
            } catch (_: SQLException) {
                // Skip exception. Table will be created later
            }
        }
    }

    override suspend fun readExtension() {
        connection.openDatabase().use { database ->
            try {
                database.rawQuery(
                    "SELECT table_name, column_name, extension_name, definition, scope FROM '$EXTENSIONS'", null
                ).use { it.run {
                    while (moveToNext()) addExtension(
                        GpkgExtension(
                            this@GeoPackage, getString(0), getString(1), getString(2),
                            getString(3), getString(4)
                        )
                    )
                } }
            } catch (_: SQLException) {
                // Skip exception. Table will be created later
            }
        }
    }

    override suspend fun readGriddedCoverage() {
        connection.openDatabase().use { database ->
            try {
                database.rawQuery("""
                    SELECT id, tile_matrix_set_name, datatype, scale, 'offset', precision, data_null, grid_cell_encoding,
                        uom, field_name, quantity_definition FROM '$GRIDDED_COVERAGE_ANCILLARY'
                """.trimIndent(), null).use { it.run {
                    while (moveToNext()) addGriddedCoverage(
                        GpkgGriddedCoverage(
                            this@GeoPackage, getInt(0), getString(1), getString(2),
                            getDouble(3), getDouble(4), getDouble(5), getDouble(6),
                            getString(7), getString(8), getString(9), getString(10)
                        )
                    )
                } }
            } catch (_: SQLException) {
                // Skip exception. Table will be created later
            }
        }
    }

    override suspend fun readGriddedTile(tableName: String, tileId: Int) =
        connection.openDatabase().use { database ->
            database.rawQuery("""
                SELECT id, tpudt_name, tpudt_id, scale, 'offset', min, max, mean, std_dev FROM '$GRIDDED_TILE_ANCILLARY' 
                WHERE tpudt_name=? AND tpudt_id=? LIMIT 1
            """.trimIndent(), arrayOf(tableName, tileId.toString())).use { it.run {
                if (moveToNext()) GpkgGriddedTile(
                    this@GeoPackage, getInt(0), getString(1), getInt(2),
                    getDouble(3), getDouble(4), getDouble(5), getDouble(6),
                    getDouble(7), getDouble(8)
                ) else null
            } }
        }

    override suspend fun readTileUserData(tableName: String, zoom: Int, column: Int, row: Int) =
        // TODO SQLiteDatabase is ambiguous on whether the call to rawQuery and Cursor usage are thread safe
        connection.openDatabase().use { database ->
            database.rawQuery("""
                SELECT id, zoom_level, tile_column, tile_row, tile_data FROM '$tableName' 
                WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1
            """.trimIndent(), arrayOf(zoom.toString(), column.toString(), row.toString())).use { it.run {
                if (moveToNext()) GpkgTileUserData(
                    this@GeoPackage, getInt(0), getInt(1), getInt(2),
                    getInt(3), getBlob(4)
                ) else null
            } }
        }

    override suspend fun deleteContent(content: GpkgContent) {
        connection.openDatabase().use { database ->
            try {
                database.delete(CONTENTS, "table_name=?", arrayOf(content.tableName))
            } catch (_: SQLException) {
                // Skip exception.
            }
        }
    }

    override suspend fun deleteMatrixSet(matrixSet: GpkgTileMatrixSet) {
        connection.openDatabase().use { database ->
            try {
                database.delete(TILE_MATRIX_SET, "table_name=?", arrayOf(matrixSet.tableName))
            } catch (_: SQLException) {
                // Skip exception.
            }
        }
    }

    override suspend fun deleteMatrix(matrix: GpkgTileMatrix) {
        connection.openDatabase().use { database ->
            try {
                val args = arrayOf(matrix.tableName, matrix.zoomLevel.toString())
                database.delete(TILE_MATRIX, "table_name=? AND zoom_level=?", args)
            } catch (_: SQLException) {
                // Skip exception.
            }
        }
    }

    override suspend fun deleteExtension(extension: GpkgExtension) {
        connection.openDatabase().use { database ->
            try {
                database.delete(EXTENSIONS, "table_name=?", arrayOf(extension.tableName))
            } catch (_: SQLException) {
                // Skip exception.
            }
        }
    }

    override suspend fun deleteGriddedCoverage(griddedCoverage: GpkgGriddedCoverage) {
        connection.openDatabase().use { database ->
            try {
                val args = arrayOf(griddedCoverage.tileMatrixSetName)
                database.delete(GRIDDED_COVERAGE_ANCILLARY, "tile_matrix_set_name=?", args)
            } catch (_: SQLException) {
                // Skip exception.
            }
        }
    }

    override suspend fun deleteGriddedTiles(tableName: String) {
        connection.openDatabase().use { database ->
            try {
                database.delete(GRIDDED_TILE_ANCILLARY, "tpudt_name=?", arrayOf(tableName))
            } catch (_: SQLException) {
                // Skip exception.
            }
        }
    }

    override suspend fun clearTilesTable(tableName: String) {
        connection.openDatabase().use { database ->
            try {
                database.delete(tableName, "", emptyArray())
            } catch (_: SQLException) {
                // Skip exception.
            }
        }
    }

    override suspend fun dropTilesTable(tableName: String) {
        connection.openDatabase().use { database -> database.execSQL("DROP TABLE IF EXISTS $tableName") }
    }

}