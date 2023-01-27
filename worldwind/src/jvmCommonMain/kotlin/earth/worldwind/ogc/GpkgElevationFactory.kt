package earth.worldwind.ogc

import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.ResourcePostprocessor
import java.nio.*

// TODO Add support of greyscale PNG encoding for 16-bit integer data and TIFF encoding for 32-bit floating point data
open class GpkgElevationFactory(
    protected val tiles: GpkgContent,
    protected val zoomLevel: Int,
    protected val tileColumn: Int,
    protected val tileRow: Int,
    protected val isFloat: Boolean
): ElevationSource.ElevationFactory, ResourcePostprocessor<Buffer> {
    override suspend fun fetchTileData(): Buffer? {
        // Attempt to read the GeoPackage tile user data
        val tileUserData = tiles.container.readTileUserData(tiles, zoomLevel, tileColumn, tileRow) ?: return null

        // Decode the tile user data either as application/bil32 or application/bil16
        val buffer = ByteBuffer.wrap(tileUserData.tileData).order(ByteOrder.LITTLE_ENDIAN)
        return if (isFloat) buffer.asFloatBuffer() else buffer.asShortBuffer()
    }

    override suspend fun process(resource: Buffer): Buffer {
        // Attempt to write tile user data only if container is not read-only
        if (!tiles.container.isReadOnly) {
            when (resource) {
                is FloatBuffer -> {
                    if (isFloat) {
                        // Encode data as application/bil32
                        val byteBuffer = ByteBuffer.allocate(resource.remaining() * 4).order(ByteOrder.LITTLE_ENDIAN)
                        byteBuffer.asFloatBuffer().put(resource)
                        resource.clear()
                        byteBuffer.array()
                    } else {
                        logMessage(
                            ERROR, "GpkgElevationFactory", "process",
                            "Invalid data type configuration! Expected float type."
                        )
                        null // Do not save tile with incorrect datatype
                    }
                }
                is ShortBuffer -> {
                    if (!isFloat) {
                        // Encode data as application/bil16
                        val byteBuffer = ByteBuffer.allocate(resource.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                        byteBuffer.asShortBuffer().put(resource)
                        resource.clear()
                        byteBuffer.array()
                    } else {
                        logMessage(
                            ERROR, "GpkgElevationFactory", "process",
                            "Invalid data type configuration! Expected integer type."
                        )
                        null // Do not save tile with incorrect datatype
                    }
                }
                else -> {
                    logMessage(ERROR, "GpkgElevationFactory", "process", "Invalid buffer type")
                    null // Do not save tile with incorrect datatype
                }
            }?.let {
                tiles.container.writeTileUserData(tiles, zoomLevel, tileColumn, tileRow, it)
                // TODO Calculate and save gridded tile meta data, such as min and max altitude, scale and offset...
                tiles.container.writeGriddedTile(tiles, zoomLevel, tileColumn, tileRow)
            }
        }
        return resource
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GpkgElevationFactory) return false
        if (tiles.tableName != other.tiles.tableName) return false
        if (zoomLevel != other.zoomLevel) return false
        if (tileColumn != other.tileColumn) return false
        if (tileRow != other.tileRow) return false
        return true
    }

    override fun hashCode(): Int {
        var result = tiles.tableName.hashCode()
        result = 31 * result + zoomLevel
        result = 31 * result + tileColumn
        result = 31 * result + tileRow
        return result
    }

    override fun toString() = "GpkgElevationFactory(tableName=${tiles.tableName}, zoomLevel=$zoomLevel, tileColumn=$tileColumn, tileRow=$tileRow)"
}