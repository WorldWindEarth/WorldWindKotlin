package earth.worldwind.ogc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import earth.worldwind.ogc.gpkg.GpkgContent
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.ResourcePostprocessor
import java.io.ByteArrayOutputStream

open class GpkgBitmapFactory(
    protected val tiles: GpkgContent,
    protected val zoomLevel: Int,
    protected val tileColumn: Int,
    protected val tileRow: Int,
    protected val format: Bitmap.CompressFormat,
    protected val quality: Int = 100
): ImageSource.BitmapFactory, ResourcePostprocessor {
    override suspend fun createBitmap(): Bitmap? {
        // Attempt to read the GeoPackage tile user data
        val tileUserData = tiles.container.readTileUserData(tiles, zoomLevel, tileColumn, tileRow) ?: return null

        // Decode the tile user data, either a PNG image or a JPEG image.
        return BitmapFactory.decodeByteArray(tileUserData.tileData, 0, tileUserData.tileData.size)
    }

    override suspend fun <Resource> process(resource: Resource): Resource {
        if (resource is Bitmap && !tiles.isReadOnly) {
            val stream = ByteArrayOutputStream()
            resource.compress(format, quality, stream)
            tiles.container.writeTileUserData(tiles, zoomLevel, tileColumn, tileRow, stream.toByteArray())
        }
        return resource
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GpkgBitmapFactory) return false
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

    override fun toString() = "GpkgBitmapFactory(tableName=${tiles.tableName}, zoomLevel=$zoomLevel, tileColumn=$tileColumn, tileRow=$tileRow)"
}