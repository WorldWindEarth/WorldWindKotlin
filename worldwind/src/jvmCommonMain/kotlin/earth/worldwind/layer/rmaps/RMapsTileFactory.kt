package earth.worldwind.layer.rmaps

import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import earth.worldwind.geom.Sector
import earth.worldwind.layer.cache.CachePolicy
import earth.worldwind.layer.cache.ContentEntry
import earth.worldwind.layer.mercator.MercatorImageTile
import earth.worldwind.layer.mercator.MercatorSector
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.Level
import earth.worldwind.util.TileFactory
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.ormlite.initConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Instant

expect fun buildImageSource(
    tilesDao: Dao<RMapsTiles, *>, readOnly: Boolean, contentKey: String, x: Int, y: Int, z: Int, imageFormat: String?
): ImageSource

open class RMapsTileFactory(
    override val contentPath: String, val isReadOnly: Boolean, protected val imageFormat: String?
) : TileFactory, ContentEntry {
    protected val connectionSource = initConnection(contentPath, isReadOnly)
    protected val tilesDao: Dao<RMapsTiles, *> = DaoManager.createDao(connectionSource, RMapsTiles::class.java)
    protected val infoDao: Dao<RMapsInfo, *> = DaoManager.createDao(connectionSource, RMapsInfo::class.java)
    protected val contentFile = File(contentPath)
    override val contentType = if (tilesDao.isTableExists && infoDao.isTableExists) "RMaps" else error("Not an RMaps map file")
    override val contentKey = contentFile.nameWithoutExtension
    val minZoom get() = infoDao.queryForFirst()?.maxzoom?.let { 17 - it } ?: 0
    val maxZoom get() = infoDao.queryForFirst()?.minzoom?.let { 17 - it } ?: 17
    val isShutdown get() = !connectionSource.isOpen("")

    fun shutdown() = connectionSource.close()

    override suspend fun lastModifiedDate(): Instant = withContext(Dispatchers.IO) {
        Instant.fromEpochMilliseconds(contentFile.lastModified())
    }

    override suspend fun contentSize(): Long = withContext(Dispatchers.IO) { contentFile.length() }

    var cachePolicy: CachePolicy = CachePolicy.UNBOUNDED
        set(value) {
            if (!value.isUnbounded && field.isUnbounded) {
                logMessage(WARN, "RMapsTileFactory", "cachePolicy",
                    "RMaps schema has no per-tile timestamps; eviction policy ignored for $contentPath")
            }
            field = value
        }

    suspend fun evict() {}

    override suspend fun clearEntry(deleteMetadata: Boolean) {
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
                tilesDao, isReadOnly, contentKey, column, (1 shl level.levelNumber) - 1 - row, 17 - level.levelNumber, imageFormat
            ).also { it.postprocessor = this }
        }

    protected open fun buildTile(sector: Sector, level: Level, row: Int, column: Int) = if (sector is MercatorSector) {
        MercatorImageTile(sector, level, row, column)
    } else {
        error("Only Mercator sector is supported!")
    }
}