package earth.worldwind.layer.atak

import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import earth.worldwind.geom.Sector
import earth.worldwind.layer.mercator.MercatorImageTile
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.ImageTile
import earth.worldwind.util.CacheTileFactory
import earth.worldwind.util.Level
import earth.worldwind.util.ormlite.initConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.io.File

expect fun buildImageSource(
    tilesDao: Dao<ATAKTiles, Int>, readOnly: Boolean, contentKey: String, key: Int, imageFormat: String?
): ImageSource

open class ATAKTileFactory(
    final override val contentPath: String, val isReadOnly: Boolean, protected val imageFormat: String?
) : CacheTileFactory {
    protected val connectionSource = initConnection(contentPath, isReadOnly)
    protected val tilesDao: Dao<ATAKTiles, Int> = DaoManager.createDao(connectionSource, ATAKTiles::class.java)
    protected val metadataDao: Dao<ATAKMetadata, String> = DaoManager.createDao(connectionSource, ATAKMetadata::class.java)
    //protected val catalogDao: Dao<ATAKCatalog, Int> = DaoManager.createDao(connectionSource, ATAKCatalog::class.java)
    protected val contentFile = File(contentPath)
    override val contentType = if (tilesDao.isTableExists && metadataDao.isTableExists) "ATAK" else error("Not an ATAK map file")
    override val contentKey = tilesDao.queryForFirst()?.provider ?: error("Empty cache file")
    val srid = metadataDao.queryForId("srid")?.value?.toIntOrNull()
    val isShutdown get() = !connectionSource.isOpen("")

    fun shutdown() = connectionSource.close()

    override suspend fun lastModifiedDate() = withContext(Dispatchers.IO) {
        Instant.fromEpochMilliseconds(contentFile.lastModified())
    }

    override suspend fun contentSize() = withContext(Dispatchers.IO) { contentFile.length() } // One file should contain one map

    override suspend fun clearContent(deleteMetadata: Boolean) {
        withContext(Dispatchers.IO) {
            if (isReadOnly) error("Database is readonly!")
            if (deleteMetadata) {
                connectionSource.close()
                contentFile.delete()
            } else if (tilesDao.isTableExists) tilesDao.deleteBuilder().delete() else Unit
        }
    }

    override fun createTile(sector: Sector, level: Level, row: Int, column: Int) =
        buildTile(sector, level, row, column).apply {
            val x = column
            val y = level.levelHeight / level.tileHeight - row - 1
            val z = level.levelNumber
            val key = (z shl (z + z)) + (x shl z) + y
            imageSource = buildImageSource(tilesDao, isReadOnly, contentKey, key, imageFormat).also { it.postprocessor = this }
        }

    protected open fun buildTile(sector: Sector, level: Level, row: Int, column: Int) = if (sector is MercatorSector) {
        MercatorImageTile(sector, level, row, column)
    } else {
        ImageTile(sector, level, row, column)
    }

    companion object {
        const val EPSG_3857 = 3857
    }
}