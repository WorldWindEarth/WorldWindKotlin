package earth.worldwind.layer.ogc3d.content.spz

/**
 * Platform-pluggable gzip / deflate inflater. SPZ payloads are gzip-compressed after the
 * fixed 16-byte header — the engine has no built-in inflater that works across all KMP
 * targets (JVM has `GZIPInputStream`, K/N doesn't, Kotlin/JS would need a separate Pako
 * binding), so we expose this hook and let the app wire the most appropriate
 * implementation per target.
 *
 * Wire one impl at startup, before the first SPZ tile is fetched:
 * ```
 * SpzGaussianLoader.inflater = MyJvmInflater()
 * ```
 *
 * Default is null. With no inflater registered, [SpzGaussianLoader.parse] throws an
 * [IllegalStateException]; [SpzGaussianLoader.supports] still detects the magic bytes so
 * the layer can route the tile to a different loader if one's available.
 */
fun interface SpzInflater {
    /**
     * Inflate a gzip stream. Implementations decompress [bytes] and return the inflated
     * payload. May throw on malformed inputs — the loader treats throws as parse failures
     * and the tile transitions to FAILED state.
     */
    fun inflate(bytes: ByteArray): ByteArray
}

/**
 * Install the platform-default [SpzInflater] on [SpzGaussianLoader.inflater] when the slot
 * is empty. Idempotent. No-op on platforms without a built-in gzip; apps targeting those
 * must wire their own [SpzInflater] before the first SPZ tile is fetched.
 */
expect fun installDefaultSpzInflater()
