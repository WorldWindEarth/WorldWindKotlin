package earth.worldwind.layer.cache

// Packing of a slippy/pyramid tile address (z, x, y) into a single Long, shared by the cache
// decorators and stores (in-flight-revalidation sets, pending-eviction queues) so the bit layout
// lives in exactly one place: z in bits 48+, x in bits 24–47, y in bits 0–23 — valid for
// x, y < 2^24 and z < 2^16, which covers every real tile pyramid.

internal fun tileKey(z: Int, x: Int, y: Int): Long =
    (z.toLong() shl 48) or (x.toLong() and 0xFFFFFF shl 24) or (y.toLong() and 0xFFFFFF)

internal fun tileKeyZ(key: Long): Int = (key shr 48).toInt()
internal fun tileKeyX(key: Long): Int = (key shr 24 and 0xFFFFFF).toInt()
internal fun tileKeyY(key: Long): Int = (key and 0xFFFFFF).toInt()
