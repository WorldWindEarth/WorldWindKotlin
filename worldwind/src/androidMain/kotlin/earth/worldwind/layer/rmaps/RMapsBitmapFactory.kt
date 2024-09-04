package earth.worldwind.layer.rmaps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.j256.ormlite.dao.Dao
import earth.worldwind.layer.rmaps.RMapsTiles.Companion.S
import earth.worldwind.layer.rmaps.RMapsTiles.Companion.X
import earth.worldwind.layer.rmaps.RMapsTiles.Companion.Y
import earth.worldwind.layer.rmaps.RMapsTiles.Companion.Z
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.ResourcePostprocessor
import java.io.ByteArrayOutputStream

open class RMapsBitmapFactory(
    protected val tilesDao: Dao<RMapsTiles, *>,
    protected val isReadOnly: Boolean,
    protected val contentKey: String,
    protected val x: Int,
    protected val y: Int,
    protected val z: Int,
    protected val format: Bitmap.CompressFormat,
    protected val quality: Int = 100
): ImageSource.BitmapFactory, ResourcePostprocessor {
    override suspend fun createBitmap(): Bitmap? {
        // Attempt to read the RMap tile data
        val image = tilesDao.queryBuilder().where().eq(X, x).and().eq(Y, y).and().eq(Z, z).and().eq(S, 0)
            .queryForFirst()?.image ?: return null

        // Decode the tile data, either a PNG image or a JPEG image.
        return BitmapFactory.decodeByteArray(image, 0, image.size)
    }

    override suspend fun <Resource> process(resource: Resource): Resource {
        if (resource is Bitmap && !isReadOnly) {
            val stream = ByteArrayOutputStream()
            resource.compress(format, quality, stream)
            tilesDao.createOrUpdate(RMapsTiles().also {
                it.x = x
                it.y = y
                it.z = z
                it.s = 0
                it.image = stream.toByteArray()
            })
        }
        return resource
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RMapsBitmapFactory) return false
        if (contentKey != other.contentKey) return false
        if (x != other.x) return false
        if (y != other.y) return false
        if (z != other.z) return false
        return true
    }

    override fun hashCode(): Int {
        var result = contentKey.hashCode()
        result = 31 * result + x
        result = 31 * result + y
        result = 31 * result + z
        return result
    }

    override fun toString() = "RMapsBitmapFactory(contentKey=$contentKey, x=$x, y=$y, z=$z)"
}