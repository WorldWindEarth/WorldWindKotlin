package earth.worldwind.layer.mbtiles

import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import earth.worldwind.geom.Angle.Companion.degrees
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
import java.io.File
import kotlin.time.Instant

expect fun buildImageSource(
    tilesDao: Dao<MBTiles, *>, readOnly: Boolean, contentKey: String, zoom: Int, column: Int, row: Int, imageFormat: String?
): ImageSource

open class MBTileFactory(final override val contentPath: String, val isReadOnly: Boolean) : CacheTileFactory {
    protected val connectionSource = initConnection(contentPath, isReadOnly)
    protected val tilesDao = DaoManager.createDao(connectionSource, MBTiles::class.java)
    protected val metadataDao: Dao<MBTilesMetadata, String> = DaoManager.createDao(connectionSource, MBTilesMetadata::class.java)
    protected val contentFile = File(contentPath)
    override val contentType = if (metadataDao.isTableExists && tilesDao.isTableExists) "MBTiles" else error("Not an MBTiles map file")
    override val contentKey = metadataDao.queryForId("name")?.value ?: error("Empty name!")
    val boundingSector = metadataDao.queryForId("bounds")?.value?.let {
        val box = it.split(",")
        if (box.size < 4) return@let null
        val minLon = box[0].toDoubleOrNull() ?: return@let null
        val minLat = box[1].toDoubleOrNull() ?: return@let null
        val maxLon = box[2].toDoubleOrNull() ?: return@let null
        val maxLat = box[3].toDoubleOrNull() ?: return@let null
        Sector(minLat.degrees, maxLat.degrees, minLon.degrees, maxLon.degrees)
    }
    val type = metadataDao.queryForId("type")?.value
    val version = metadataDao.queryForId("version")?.value?.toIntOrNull() ?: 0
    val minZoom = metadataDao.queryForId("minzoom")?.value?.toIntOrNull() ?: 0
    val maxZoom = metadataDao.queryForId("maxzoom")?.value?.toIntOrNull() ?: 20
    protected val imageFormat = "image/" + (metadataDao.queryForId("format")?.value ?: error("Empty format!"))
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
            imageSource = buildImageSource(
                tilesDao, isReadOnly, contentKey, level.levelNumber, column, row, imageFormat
            ).also { it.postprocessor = this }
        }

    protected open fun buildTile(sector: Sector, level: Level, row: Int, column: Int) = if (sector is MercatorSector) {
        MercatorImageTile(sector, level, row, column)
    } else {
        ImageTile(sector, level, row, column)
    }
}