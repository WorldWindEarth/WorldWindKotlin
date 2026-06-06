package earth.worldwind.layer.cache

import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.CachedGeometry
import earth.worldwind.util.js.jso
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.length
import kotlin.js.toJsString
import kotlin.js.unsafeCast
import kotlin.time.Clock

/**
 * IndexedDB-backed [FeatureStore]. Bulk rows (`z`/`x`/`y` all NULL — WFS, Shapefile) and
 * tile-addressed rows (OsmBuildings) live in the same `features` object store; rows
 * discriminate by `(contentKey, z, x, y)` composite. Geometry and properties are
 * serialised as JSON strings — the store doesn't parse them. Each row also carries the
 * tile's `cachedAt` (epoch-ms), which drives stale-while-revalidate ([readTileLastModified])
 * and oldest-first capacity [evict].
 *
 * Tile sentinel: a single row at `(contentKey, z, x, y)` with `geometry = null` means
 * "tile fetched, zero features". Distinct from "tile not cached" (no rows at all), which
 * surfaces as a `null` flow from [readTile].
 */
internal class IndexedDbFeatureStore(
    private val db: IDBDatabase,
    private val contentKey: String,
    override val cachePolicy: CachePolicy,
) : FeatureStore, CachedSourceInfoProvider {

    override val cacheInfo: CachedSourceInfo
        get() = CachedSourceInfo(contentKey = contentKey, contentPath = db.name)

    override suspend fun readAll(): Flow<CachedFeatureRow> = flow {
        val tx = db.transaction(FEATURES_STORE, "readonly")
        val store = tx.objectStore(FEATURES_STORE)
        // Bulk rows have z = null (encoded as `null` in IDB keys). We range over the
        // contentKey prefix and filter to the null-z rows on read.
        val req = store.index(INDEX_BY_CONTENT).openCursor(contentKey.toJsString())
        val rows = mutableListOf<CachedFeatureRow>()
        idbWalkCursor(req) { cursor ->
            val record = cursor.value?.unsafeCast<FeatureRecordSurrogate>() ?: return@idbWalkCursor
            if (record.z == null) {
                val geom = record.geometry
                val props = record.properties
                rows += CachedFeatureRow(geom?.let { decodeGeometry(it) }, props)
            }
        }
        // No idbAwaitTransaction — readonly auto-commits; awaiting can hang.
        rows.forEach { emit(it) }
    }

    override suspend fun replaceAll(rows: Flow<CachedFeatureRow>) {
        val materialised = rows.toList()
        // Delete existing bulk rows for this content key, then insert the new set in one tx.
        val tx = db.transaction(FEATURES_STORE, "readwrite")
        val store = tx.objectStore(FEATURES_STORE)
        val cursorReq = store.index(INDEX_BY_CONTENT).openCursor(contentKey.toJsString())
        idbWalkCursor(cursorReq) { cursor ->
            if (cursor.value?.unsafeCast<FeatureRecordSurrogate>()?.z == null) cursor.delete()
        }
        for (row in materialised) store.add(newFeatureRecord(contentKey, null, null, null, row))
        idbAwaitTransaction(tx)
    }

    override suspend fun readTile(z: Int, x: Int, y: Int): Flow<CachedFeatureRow>? {
        val tx = db.transaction(FEATURES_STORE, "readonly")
        val store = tx.objectStore(FEATURES_STORE)
        val req = store.index(INDEX_BY_TILE).getAll(tileKey(contentKey, z, x, y))
        val arr = featureSurrogateList(idbAwait(req))
        // No idbAwaitTransaction — readonly auto-commits.
        if (arr.isEmpty()) return null
        if (arr.size == 1 && arr[0].geometry == null) return emptyFlow()  // negative cache
        return arr.asSequence()
            .mapNotNull { record ->
                val geom = record.geometry?.let { decodeGeometry(it) } ?: return@mapNotNull null
                CachedFeatureRow(geom, record.properties)
            }
            .toList()
            .asFlow()
    }

    override suspend fun writeTile(z: Int, x: Int, y: Int, rows: Flow<CachedFeatureRow>) {
        val materialised = rows.toList()
        val tx = db.transaction(FEATURES_STORE, "readwrite")
        val store = tx.objectStore(FEATURES_STORE)
        // Drop any existing rows for this tile so the write replaces wholesale.
        val cursorReq = store.index(INDEX_BY_TILE).openCursor(tileKey(contentKey, z, x, y))
        idbWalkCursor(cursorReq) { cursor -> cursor.delete() }
        if (materialised.isEmpty()) {
            // Negative-cache marker.
            store.add(newFeatureRecord(contentKey, z, x, y, CachedFeatureRow(geometry = null, properties = null)))
        } else {
            for (row in materialised) store.add(newFeatureRecord(contentKey, z, x, y, row))
        }
        idbAwaitTransaction(tx)
    }

    override suspend fun deleteTile(z: Int, x: Int, y: Int) {
        val tx = db.transaction(FEATURES_STORE, "readwrite")
        val store = tx.objectStore(FEATURES_STORE)
        val cursorReq = store.index(INDEX_BY_TILE).openCursor(tileKey(contentKey, z, x, y))
        idbWalkCursor(cursorReq) { cursor -> cursor.delete() }
        idbAwaitTransaction(tx)
    }

    override suspend fun sizeBytes(): Long {
        val tx = db.transaction(FEATURES_STORE, "readonly")
        val store = tx.objectStore(FEATURES_STORE)
        val req = store.index(INDEX_BY_CONTENT).openCursor(contentKey.toJsString())
        var total = 0L
        idbWalkCursor(req) { cursor ->
            val record = cursor.value?.unsafeCast<FeatureRecordSurrogate>()
            val geom = record?.geometry
            val props = record?.properties
            total += (geom?.length ?: 0) + (props?.length ?: 0)
        }
        // No idbAwaitTransaction — readonly auto-commits.
        return total
    }

    override suspend fun readTileLastModified(z: Int, x: Int, y: Int): Long? {
        val tx = db.transaction(FEATURES_STORE, "readonly")
        val store = tx.objectStore(FEATURES_STORE)
        val arr = featureSurrogateList(idbAwait(store.index(INDEX_BY_TILE).getAll(tileKey(contentKey, z, x, y))))
        if (arr.isEmpty()) return null
        // Records of one tile share a write time; take the max defensively.
        var max = 0.0
        for (rec in arr) rec.cachedAt?.let { if (it > max) max = it }
        return if (max > 0.0) max.toLong() else null
    }

    /** Capacity eviction: drop whole tiles oldest-first (by `cachedAt`) until within
     *  [CachePolicy.maxEntries] (tile count) and [CachePolicy.maxBytes]. Bulk rows (z == null) are
     *  atomic snapshots and are never partially evicted. staleAfter never deletes. */
    override suspend fun evict() {
        if (cachePolicy.isUnbounded) return
        data class TileAgg(val z: Int, val x: Int, val y: Int, var cachedAt: Double, var bytes: Long)
        val readStore = db.transaction(FEATURES_STORE, "readonly").objectStore(FEATURES_STORE)
        val agg = HashMap<String, TileAgg>()
        idbWalkCursor(readStore.index(INDEX_BY_CONTENT).openCursor(contentKey.toJsString())) { cursor ->
            val rec = cursor.value?.unsafeCast<FeatureRecordSurrogate>() ?: return@idbWalkCursor
            val zz = rec.z
            if (zz != null) {
                val xx = rec.x!!
                val yy = rec.y!!
                val size = ((rec.geometry?.length ?: 0) + (rec.properties?.length ?: 0)).toLong()
                val cachedAt = rec.cachedAt ?: 0.0
                val existing = agg["$zz/$xx/$yy"]
                if (existing == null) agg["$zz/$xx/$yy"] = TileAgg(zz, xx, yy, cachedAt, size)
                else { if (cachedAt > existing.cachedAt) existing.cachedAt = cachedAt; existing.bytes += size }
            }
        }
        // Keep newest tiles within both caps; the rest are victims.
        var keptCount = 0L
        var keptBytes = 0L
        val victims = agg.values.sortedByDescending { it.cachedAt }.filter { tile ->
            val overCount = keptCount >= cachePolicy.maxEntries
            val overBytes = cachePolicy.maxBytes != Long.MAX_VALUE && keptBytes + tile.bytes > cachePolicy.maxBytes
            if (overCount || overBytes) true else { keptCount++; keptBytes += tile.bytes; false }
        }
        if (victims.isEmpty()) return
        val writeTx = db.transaction(FEATURES_STORE, "readwrite")
        val writeStore = writeTx.objectStore(FEATURES_STORE)
        for (tile in victims) {
            idbWalkCursor(writeStore.index(INDEX_BY_TILE).openCursor(tileKey(contentKey, tile.z, tile.x, tile.y))) {
                it.delete()
            }
        }
        idbAwaitTransaction(writeTx)
    }

    private fun newFeatureRecord(
        contentKey: String, z: Int?, x: Int?, y: Int?, row: CachedFeatureRow,
    ): FeatureRecordSurrogate {
        val r = newFeatureRecordObj()
        r.contentKey = contentKey
        r.z = z
        r.x = x
        r.y = y
        r.geometry = row.geometry?.let { encodeGeometry(it) }
        r.properties = row.properties
        r.cachedAt = Clock.System.now().toEpochMilliseconds().toDouble()
        return r
    }

    private fun encodeGeometry(geometry: CachedGeometry): String =
        FEATURE_JSON.encodeToString(CachedGeometry.serializer(), geometry)

    private fun decodeGeometry(text: String): CachedGeometry? = runCatching {
        FEATURE_JSON.decodeFromString(CachedGeometry.serializer(), text)
    }.getOrNull()

    companion object {
        internal const val FEATURES_STORE = "features"
        internal const val INDEX_BY_TILE = "byTile"
        internal const val INDEX_BY_CONTENT = "byContent"
        private val FEATURE_JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Typed view of a `features`-store row as written by [IndexedDbFeatureStore.newFeatureRecord].
 */
internal external interface FeatureRecordSurrogate : JsAny {
    var contentKey: String
    var z: Int?
    var x: Int?
    var y: Int?
    var geometry: String?
    var properties: String?
    var cachedAt: Double?
}

private fun newFeatureRecordObj(): FeatureRecordSurrogate = jso()

// IDB getAll() resolves to a JS array of feature rows; copy into a Kotlin List of surrogates.
// UNNECESSARY_NOT_NULL_ASSERTION: JsArray.get returns T? on wasmJs (so the !! is required) but
// non-null T on JS (where the JS compiler then flags the !! as unnecessary). Shared webMain can't
// satisfy both targets, so keep the !! for wasmJs and suppress the JS-only warning.
@Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
private fun featureSurrogateList(raw: JsAny?): List<FeatureRecordSurrogate> {
    val arr = raw?.unsafeCast<JsArray<FeatureRecordSurrogate>>() ?: return emptyList()
    return List(arr.length) { arr[it]!! }
}
