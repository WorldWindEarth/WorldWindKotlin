package earth.worldwind.ogc

import earth.worldwind.globe.elevation.ElevationDecoder
import earth.worldwind.globe.elevation.ElevationSource
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.util.ResourcePostprocessor
import java.nio.Buffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.roundToInt

open class GpkgElevationDataFactory(
    protected val tiles: GpkgContent,
    protected val zoomLevel: Int,
    protected val tileColumn: Int,
    protected val tileRow: Int,
    protected val isFloat: Boolean
): ElevationSource.ElevationDataFactory, ResourcePostprocessor {
    protected val elevationDecoder = ElevationDecoder()

    override suspend fun fetchElevationData(): Buffer? {
        // Attempt to read the GeoPackage tile user data and gridded tile metadata
        val tileUserData = tiles.container.readTileUserData(tiles, zoomLevel, tileColumn, tileRow) ?: return null
        val griddedTile = tiles.container.readGriddedTile(tiles, tileUserData) ?: return null
        val griddedCoverage = tiles.container.griddedCoverages[tiles.tableName] ?: return null

        // Decode the tile user data either as TIFF32 or PNG16
        return if (isFloat) elevationDecoder.decodeTiff(tileUserData.tileData)
        else elevationDecoder.decodePng(
            tileUserData.tileData, griddedTile.scale, griddedTile.offset,
            griddedCoverage.scale, griddedCoverage.offset, griddedCoverage.dataNull?.roundToInt()?.toShort()
        )
    }

    override suspend fun <Resource> process(resource: Resource): Resource {
        // Attempt to write tile user data only if container is not read-only
        if (resource is Buffer && !tiles.isReadOnly) encodeToImage(resource)?.let {
            tiles.container.writeTileUserData(tiles, zoomLevel, tileColumn, tileRow, it)
            // TODO Calculate and save gridded tile meta data, such as min and max altitude...
            tiles.container.writeGriddedTile(tiles, zoomLevel, tileColumn, tileRow)
        }
        return resource
    }

    protected open fun encodeToImage(resource: Buffer): ByteArray? {
        val matrix = tiles.container.tileMatrix[tiles.tableName]?.get(zoomLevel) ?: return null
        val tileWidth = matrix.tileWidth
        val tileHeight = matrix.tileHeight
        return when (resource) {
            is FloatBuffer -> if (isFloat) {
                elevationDecoder.encodeTiff(resource, tileWidth, tileHeight)
            } else {
                elevationDecoder.encodePng(ShortBuffer.wrap(
                    ShortArray(resource.remaining()) {
                        val value = resource.get()
                        // Consider converting null value from float to short
                        if (value == Float.MAX_VALUE) Short.MIN_VALUE else value.roundToInt().toShort()
                    }.also { resource.clear() }
                ), tileWidth, tileHeight)
            }

            is ShortBuffer -> if (isFloat) {
                elevationDecoder.encodeTiff(FloatBuffer.wrap(
                    FloatArray(resource.remaining()) { resource.get().toFloat() }.also { resource.clear() }
                ), tileWidth, tileHeight)
            } else {
                elevationDecoder.encodePng(resource, tileWidth, tileHeight)
            }

            else -> null // Do not save tile with incorrect datatype
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GpkgElevationDataFactory) return false
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

    override fun toString() = "GpkgElevationDataFactory(tableName=${tiles.tableName}, zoomLevel=$zoomLevel, tileColumn=$tileColumn, tileRow=$tileRow)"
}