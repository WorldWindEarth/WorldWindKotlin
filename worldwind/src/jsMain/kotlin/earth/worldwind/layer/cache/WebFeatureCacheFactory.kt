package earth.worldwind.layer.cache

import earth.worldwind.layer.FeatureCacheSourceFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** IndexedDB-backed [FeatureCacheSourceFactory]; rows namespaced by [contentKey]. */
class WebFeatureCacheFactory internal constructor(
    private val store: IdbFeatureStore,
    override val contentKey: String,
) : FeatureCacheSourceFactory {
    override val contentType = "IDB"
    override val contentPath = "indexeddb:worldwind-cache"

    override suspend fun lastModifiedDate(): Instant? =
        store.lastModified(contentKey)?.let { Instant.fromEpochMilliseconds(it.toLong()) }

    override suspend fun contentSize(): Long = store.contentSize(contentKey)

    override suspend fun clearContent(deleteMetadata: Boolean) {
        store.deleteByContent(contentKey)
    }

    override suspend fun readFeatureTile(z: Int, x: Int, y: Int) =
        store.readByTile(contentKey, z, x, y).map { it.toCachedRow() }

    override suspend fun writeFeatureTile(z: Int, x: Int, y: Int, rows: List<CachedFeatureRow>) {
        store.deleteByTile(contentKey, z, x, y)
        val now = currentEpochMs()
        val payload = if (rows.isEmpty()) {
            listOf(newIdbFeatureRecord(contentKey, z, x, y, geometry = null, properties = null, lastModified = now))
        } else rows.map { row ->
            newIdbFeatureRecord(
                contentKey, z, x, y,
                geometry = row.geometry?.let { JSON.encodeToString(it) },
                properties = row.properties,
                lastModified = now,
            )
        }
        store.putAll(payload)
    }

    override suspend fun readAllFeatures() =
        store.readByContent(contentKey).map { it.toCachedRow() }

    override suspend fun replaceAllFeatures(rows: List<CachedFeatureRow>) {
        store.deleteByContent(contentKey)
        if (rows.isEmpty()) return
        val now = currentEpochMs()
        val payload = rows.map { row ->
            newIdbFeatureRecord(
                contentKey, z = null, x = null, y = null,
                geometry = row.geometry?.let { JSON.encodeToString(it) },
                properties = row.properties,
                lastModified = now,
            )
        }
        store.putAll(payload)
    }

    private fun IdbFeatureRecord.toCachedRow(): CachedFeatureRow {
        val geom = geometry?.let { runCatching { JSON.decodeFromString<CachedGeometry>(it) }.getOrNull() }
        return CachedFeatureRow(geom, properties)
    }

    private fun currentEpochMs(): Double = js("Date.now()").unsafeCast<Double>()

    private companion object {
        val JSON = Json { encodeDefaults = false; ignoreUnknownKeys = true; classDiscriminator = "type" }
    }
}
