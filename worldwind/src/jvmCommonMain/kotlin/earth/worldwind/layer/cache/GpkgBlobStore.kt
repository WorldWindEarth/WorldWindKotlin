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
 * GeoPackage-backed [BlobStore] following the OGC 3D Tiles GeoPackage Extension shape:
 * one SQLite table per dataset (the [tableName]), registered in `gpkg_contents` with
 * `data_type='3d-tiles'` and in `gpkg_extensions` as `gpkg_3d_tiles`. The per-tile
 * schema is [GpkgBlobRow] — `tile_path` PK + `tile_data` BLOB + WorldWind-extended
 * metadata columns for conditional fetches and LRU eviction.
 *
 * Created by [earth.worldwind.formats.gpkg.GpkgContentManager.openBlobStore]; the manager
 * owns the [GeoPackage] handle, table lifecycle, AND the deferred eviction (mirrors the
 * `deferEvict` path used by every other GPKG store), so this class itself does no work in
 * construction — eviction runs detached on the manager scope after [openBlobStore] returns.
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

    /**
     * Run the [evictionPolicy] sweep. Fired detached by [GpkgContentManager.openBlobStore]
     * after construction and on demand by callers (matching the rest of the GPKG store
     * machinery). DELETEs in [EVICT_CHUNK_ROWS]-sized batches with a [yield] between each
     * so a 30 K-row trim doesn't lock the SQLite connection for tens of seconds.
     */
    suspend fun evict(): Unit = withContext(writeDispatcher) {
        if (geoPackage.isReadOnly || evictionPolicy.isUnbounded) return@withContext
        if (evictionPolicy.maxEntries == Long.MAX_VALUE) return@withContext
        val escaped = tableName.replace("\"", "\"\"")
        val q = "\"$escaped\""
        // staleAfter NEVER deletes — stale blobs are refreshed in place by
        // stale-while-revalidate, not removed. Only [CachePolicy.maxEntries] evicts.
        runCatching {
            while (currentCoroutineContext().isActive) {
                val current = dao.queryRawValue("SELECT COUNT(*) FROM $q")
                val overflow = current - evictionPolicy.maxEntries
                if (overflow <= 0) break
                val chunk = overflow.coerceAtMost(EVICT_CHUNK_ROWS)
                geoPackage.core.database.execSQL(
                    "DELETE FROM $q WHERE ${GpkgBlobRow.COLUMN_TILE_PATH} IN (" +
                        "SELECT ${GpkgBlobRow.COLUMN_TILE_PATH} FROM $q " +
                        "ORDER BY ${GpkgBlobRow.COLUMN_CACHED_AT} ASC LIMIT $chunk" +
                        ")"
                )
                yield()
            }
        }.onFailure { t ->
            logMessage(WARN, "GpkgBlobStore", "evict", "eviction failed on $tableName", t)
        }
    }

    companion object {
        /** Rows deleted per chunk during [evict]. Sized so each DELETE holds the SQLite
         *  connection ≈ 30–60 ms — short enough that interleaving reads don't perceive
         *  the eviction stall, large enough that the overall sweep doesn't pay round-trip
         *  cost for every row. */
        private const val EVICT_CHUNK_ROWS = 500L

        private fun getOrCreateDao(
            connectionSource: ConnectionSource,
            tableName: String,
        ): Dao<GpkgBlobRow, String> {
            val config = DatabaseTableConfig(GpkgBlobRow::class.java, tableName, null)
            val dao = object : BaseDaoImpl<GpkgBlobRow, String>(connectionSource, config) {}
            DaoManager.registerDaoWithTableConfig(connectionSource, dao)
            TableUtils.createTableIfNotExists(connectionSource, config)
            return dao
        }
    }
}
