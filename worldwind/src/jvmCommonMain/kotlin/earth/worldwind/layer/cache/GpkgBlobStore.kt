package earth.worldwind.layer.cache

import com.j256.ormlite.dao.BaseDaoImpl
import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager
import com.j256.ormlite.support.ConnectionSource
import com.j256.ormlite.table.DatabaseTableConfig
import com.j256.ormlite.table.TableUtils
import earth.worldwind.formats.gpkg.GeoPackage
import earth.worldwind.formats.gpkg.GpkgBlobRow
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.logMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.time.Instant

/**
 * GeoPackage-backed [BlobStore] following the OGC 3D Tiles GeoPackage Extension shape: one
 * SQLite table per dataset, registered in `gpkg_contents` (`data_type='3d-tiles'`) and
 * `gpkg_extensions` (`gpkg_3d_tiles`). Row schema is [GpkgBlobRow]; the table lifecycle
 * is owned by [earth.worldwind.formats.gpkg.GpkgContentManager.openBlobStore].
 */
class GpkgBlobStore internal constructor(
    private val geoPackage: GeoPackage,
    val tableName: String,
    private val evictionPolicy: CachePolicy,
) : BlobStore {

    private val dao: Dao<GpkgBlobRow, String> = getOrCreateDao(geoPackage.core.database.connectionSource, tableName)
    private val writeDispatcher = Dispatchers.IO

    override suspend fun get(uri: String): BlobEntry? = withContext(Dispatchers.IO) {
        val row = dao.queryForId(uri) ?: return@withContext null
        BlobEntry(
            bytes = row.tileData,
            contentType = row.contentType,
            etag = row.etag,
            lastModified = row.lastModified?.let { Instant.fromEpochMilliseconds(it) },
            responseUrl = row.responseUrl,
        )
    }

    override suspend fun put(
        uri: String,
        bytes: ByteArray,
        contentType: String?,
        etag: String?,
        lastModified: Instant?,
        responseUrl: String?,
    ): Unit = withContext(writeDispatcher) {
        if (geoPackage.isReadOnly) return@withContext
        val row = GpkgBlobRow().apply {
            this.tilePath = uri
            this.tileData = bytes
            this.contentType = contentType
            this.etag = etag
            this.lastModified = lastModified?.toEpochMilliseconds()
            this.cachedAt = System.currentTimeMillis()
            this.sizeBytes = bytes.size.toLong()
            this.responseUrl = responseUrl
        }
        dao.createOrUpdate(row)
        // Per-put eviction trigger — async drain fires when the table crosses the overshoot budget.
        geoPackage.notifyBlobInsert(
            tableName = tableName,
            policy = evictionPolicy,
            countRows = { dao.countOf() },
            evict = { evict() },
        )
    }

    override suspend fun remove(uri: String): Unit = withContext(writeDispatcher) {
        if (geoPackage.isReadOnly) return@withContext
        dao.deleteById(uri)
    }

    override suspend fun clear(): Unit = withContext(writeDispatcher) {
        if (geoPackage.isReadOnly) return@withContext
        val escaped = tableName.replace("\"", "\"\"")
        geoPackage.core.database.execSQL("DELETE FROM \"$escaped\"")
    }

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        val escaped = tableName.replace("\"", "\"\"")
        dao.queryRawValue("SELECT COALESCE(SUM(${GpkgBlobRow.COLUMN_SIZE_BYTES}), 0) FROM \"$escaped\"")
    }

    /** Run the [evictionPolicy] sweep. Chunked DELETE + `yield` so a large trim doesn't
     *  hold the SQLite connection for tens of seconds. staleAfter is refresh-only — only
     *  [CachePolicy.maxEntries] evicts. */
    suspend fun evict(): Unit = withContext(writeDispatcher) {
        if (geoPackage.isReadOnly || evictionPolicy.maxEntries == Long.MAX_VALUE) return@withContext
        val escaped = tableName.replace("\"", "\"\"")
        val q = "\"$escaped\""
        runCatching {
            // COUNT(*) once and track locally — re-counting per chunk is ~30 ms each on large tables.
            var remaining = dao.queryRawValue("SELECT COUNT(*) FROM $q") - evictionPolicy.maxEntries
            while (remaining > 0 && currentCoroutineContext().isActive) {
                val chunk = remaining.coerceAtMost(GeoPackage.EVICT_CHUNK_ROWS)
                geoPackage.core.database.execSQL(
                    "DELETE FROM $q WHERE ${GpkgBlobRow.COLUMN_TILE_PATH} IN (" +
                        "SELECT ${GpkgBlobRow.COLUMN_TILE_PATH} FROM $q " +
                        "ORDER BY ${GpkgBlobRow.COLUMN_CACHED_AT} ASC LIMIT $chunk" +
                        ")"
                )
                remaining -= chunk
                yield()
            }
        }.onFailure { t ->
            logMessage(WARN, "GpkgBlobStore", "evict", "eviction failed on $tableName", t)
        }
    }

    companion object {
        private fun getOrCreateDao(
            connectionSource: ConnectionSource,
            tableName: String,
        ): Dao<GpkgBlobRow, String> {
            val config = DatabaseTableConfig(GpkgBlobRow::class.java, tableName, null)
            val dao = object : BaseDaoImpl<GpkgBlobRow, String>(connectionSource, config) {}
            // Both registries: classMap is what TableUtils.createTableIfNotExists reads
            // (and it ignores tableName). See [GeoPackage.create3DTilesUserDataTable].
            DaoManager.registerDao(connectionSource, dao)
            DaoManager.registerDaoWithTableConfig(connectionSource, dao)
            TableUtils.createTableIfNotExists(connectionSource, config)
            return dao
        }
    }
}
