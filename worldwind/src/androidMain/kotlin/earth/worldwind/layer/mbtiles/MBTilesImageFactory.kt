package earth.worldwind.layer.mbtiles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.j256.ormlite.dao.Dao
import earth.worldwind.layer.mbtiles.MBTiles.Companion.TILE_COLUMN
import earth.worldwind.layer.mbtiles.MBTiles.Companion.TILE_ROW
import earth.worldwind.layer.mbtiles.MBTiles.Companion.ZOOM_LEVEL
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.ResourcePostprocessor
import java.io.ByteArrayOutputStream

open class MBTilesImageFactory(
    protected val tilesDao: Dao<MBTiles, *>,
    protected val isReadOnly: Boolean,
    protected val contentKey: String,
    protected val zoom: Int,
    protected val column: Int,
    protected val row: Int,
    protected val format: Bitmap.CompressFormat,
    protected val quality: Int = 100
): ImageSource.ImageFactory, ResourcePostprocessor {
    override suspend fun createBitmap(): Bitmap? {
        // Attempt to read the MBTiles tile data
        val tile = tilesDao.queryBuilder().where().eq(ZOOM_LEVEL, zoom)
            .and().eq(TILE_COLUMN, column).and().eq(TILE_ROW, row).queryForFirst()?.tileData ?: return null

        // Decode the tile data, either a PNG image or a JPEG image.
        return BitmapFactory.decodeByteArray(tile, 0, tile.size)
    }

    override suspend fun <Resource> process(resource: Resource): Resource {
        if (resource is Bitmap && !isReadOnly) {
            val stream = ByteArrayOutputStream()
            resource.compress(format, quality, stream)
            tilesDao.createOrUpdate(MBTiles().also {
                it.zoomLevel = zoom
                it.tileColumn = column
                it.tileRow = row
                it.tileData = stream.toByteArray()
            })
        }
        return resource
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MBTilesImageFactory) return false
        if (contentKey != other.contentKey) return false
        if (zoom != other.zoom) return false
        if (column != other.column) return false
        if (row != other.row) return false
        return true
    }

    override fun hashCode(): Int {
        var result = contentKey.hashCode()
        result = 31 * result + zoom
        result = 31 * result + column
        result = 31 * result + row
        return result
    }

    override fun toString() = "MBTilesImageFactory(contentKey=$contentKey, zoom=$zoom, column=$column, row=$row)"
}