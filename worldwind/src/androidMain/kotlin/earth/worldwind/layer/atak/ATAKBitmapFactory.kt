package earth.worldwind.layer.atak

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.j256.ormlite.dao.Dao
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.ResourcePostprocessor
import java.io.ByteArrayOutputStream

open class ATAKBitmapFactory(
    protected val tilesDao: Dao<ATAKTiles, Int>,
    protected val isReadOnly: Boolean,
    protected val contentKey: String,
    protected val key: Int,
    protected val format: Bitmap.CompressFormat,
    protected val quality: Int = 100
): ImageSource.BitmapFactory, ResourcePostprocessor {
    override suspend fun createBitmap(): Bitmap? {
        // Attempt to read the ATAK tile data
        val tile = tilesDao.queryForId(key)?.tile ?: return null

        // Decode the tile data, either a PNG image or a JPEG image.
        return BitmapFactory.decodeByteArray(tile, 0, tile.size)
    }

    override suspend fun <Resource> process(resource: Resource): Resource {
        if (resource is Bitmap && !isReadOnly) {
            val stream = ByteArrayOutputStream()
            resource.compress(format, quality, stream)
            tilesDao.createOrUpdate(ATAKTiles().also {
                it.key = key
                it.provider = contentKey
                it.tile = stream.toByteArray()
            })
        }
        return resource
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ATAKBitmapFactory) return false
        if (contentKey != other.contentKey) return false
        if (key != other.key) return false
        return true
    }

    override fun hashCode(): Int {
        var result = contentKey.hashCode()
        result = 31 * result + key
        return result
    }

    override fun toString() = "ATAKBitmapFactory(contentKey=$contentKey, key=$key)"
}