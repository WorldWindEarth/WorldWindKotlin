package earth.worldwind.formats.gltf.draco

import earth.worldwind.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * JNI binding to the locally-built `libdraco_bridge.so`. **Use [installDracoDecoder]
 * instead** for normal integration with the 3D Tiles layer; this object is the low-level
 * API directly behind it.
 *
 * The JNI symbols inside `libdraco_bridge.so` are generated to match this exact class
 * (package `earth.worldwind.formats.gltf.draco`, class `NativeDraco`) by the CMake build
 * under `src/androidMain/cpp/`. Renaming this class requires renaming the corresponding
 * `Java_earth_worldwind_formats_gltf_draco_NativeDraco_*` symbols in `draco_bridge.cpp`.
 *
 * Thread-safety: each [decode] allocates a fresh native handle, reads attributes off it,
 * then releases. Multiple coroutines can decode concurrently.
 */
internal object NativeDraco {

    @Volatile private var loaded = false
    private val loadMutex = Mutex()

    /** Returns true once `libdraco_bridge.so` is loaded; false if the .so wasn't bundled
     *  into the APK (NDK + CMake weren't installed at build time → the codec module logged
     *  the install instructions and skipped the build). Mutex-guarded so concurrent callers
     *  see the same result. */
    suspend fun tryEnsureInitialized(): Boolean = withContext(Dispatchers.Default) {
        if (loaded) return@withContext true
        loadMutex.withLock {
            if (loaded) return@withLock true
            try {
                System.loadLibrary("draco_bridge")
                loaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Logger.log(
                    Logger.WARN,
                    "libdraco_bridge.so not bundled — Draco-compressed tiles will skip. " +
                        "Install NDK + CMake (Android Studio → SDK Manager → SDK Tools) and " +
                        "rebuild, or pass -Pworldwind.draco.buildAndroidNative=true. (${e.message})",
                )
                false
            }
        }
    }

    /** Decode a single Draco-compressed bufferView. The native bridge handles the entire
     *  Draco decode + attribute extraction; this Kotlin layer only marshals results.
     *  Colors are written into a thread-local pool buffer to avoid per-tile allocation
     *  pressure during high-throughput PNTS streaming. */
    fun decode(bytes: ByteArray, colorsUniqueId: Int = -1): Decoded {
        val handle = nativeDecode(bytes, colorsUniqueId)
        require(handle != 0L) { "Draco decode failed (native returned null handle)" }
        return try {
            val positionsCount = nativeGetPositionsCount(handle)
            val positionsBuf =
                if (positionsCount == 0) EMPTY_FLOATS else fillPositionsFromPool(handle, positionsCount)
            val colorsCount = nativeGetColorsCount(handle)
            val colorsBuf = if (colorsCount == 0) EMPTY_FLOATS else fillColorsFromPool(handle, colorsCount)
            Decoded(
                indices = nativeGetIndices(handle),
                positions = positionsBuf,
                positionsCount = positionsCount,
                texCoords = nativeGetTexCoords(handle),
                colors = colorsBuf,
                colorsCount = colorsCount,
            )
        } finally {
            nativeRelease(handle)
        }
    }

    /** PNTS 2-call fast path. Saves 6 JNI roundtrips per tile vs the generic [decode]. */
    fun decodePoints(bytes: ByteArray, colorsUniqueId: Int = -1): DecodedPoints {
        val sizes = probeSizesBuf.get()!!
        val handle = nativeDecodeProbePoints(bytes, colorsUniqueId, sizes)
        require(handle != 0L) { "Draco point cloud decode failed (native returned null handle)" }
        val posCount = sizes[0]
        val colorsCount = sizes[1]
        val posBuf = if (posCount == 0) EMPTY_FLOATS else growPool(positionsPool, posCount)
        val colorsBuf = if (colorsCount == 0) EMPTY_FLOATS else growPool(colorsPool, colorsCount)
        nativeFillReleasePoints(handle, posBuf, colorsBuf)
        return DecodedPoints(posBuf, posCount, colorsBuf, colorsCount)
    }

    internal data class DecodedPoints(
        val positions: FloatArray,
        val positionsCount: Int,
        val colors: FloatArray,
        val colorsCount: Int,
    )

    private fun growPool(pool: ThreadLocal<FloatArray>, needed: Int): FloatArray {
        var buf = pool.get()!!
        if (buf.size < needed) {
            buf = FloatArray(needed)
            pool.set(buf)
        }
        return buf
    }

    /** Per-thread pools (largest per-tile JNI transfers). */
    private val positionsPool = ThreadLocal.withInitial { FloatArray(0) }
    private val colorsPool = ThreadLocal.withInitial { FloatArray(0) }
    private val probeSizesBuf = ThreadLocal.withInitial { IntArray(2) }
    private val EMPTY_FLOATS = FloatArray(0)

    private fun fillPositionsFromPool(handle: Long, needed: Int): FloatArray {
        var buf = positionsPool.get()!!
        if (buf.size < needed) {
            buf = FloatArray(needed)
            positionsPool.set(buf)
        }
        val written = nativeFillPositions(handle, buf, buf.size)
        require(written >= 0) { "Draco positions fill failed: needed=${-written} capacity=${buf.size}" }
        return buf
    }

    private fun fillColorsFromPool(handle: Long, needed: Int): FloatArray {
        var buf = colorsPool.get()!!
        if (buf.size < needed) {
            buf = FloatArray(needed)
            colorsPool.set(buf)
        }
        val written = nativeFillColors(handle, buf, buf.size)
        require(written >= 0) { "Draco colors fill failed: needed=${-written} capacity=${buf.size}" }
        return buf
    }

    /** Empty arrays mean "attribute absent in the source primitive". [positions] / [colors]
     *  may be pool-backed buffers larger than the valid count — read only the first valid
     *  span (`positionsCount` / `colorsCount` floats). */
    internal data class Decoded(
        val indices: IntArray,
        val positions: FloatArray,
        val positionsCount: Int = positions.size,
        val texCoords: FloatArray,
        val colors: FloatArray,
        val colorsCount: Int = colors.size,
    ) {
        override fun equals(other: Any?): Boolean = this === other || (other is Decoded &&
            indices.contentEquals(other.indices) &&
            positions.contentEquals(other.positions) &&
            texCoords.contentEquals(other.texCoords) &&
            colors.contentEquals(other.colors))

        override fun hashCode(): Int = 31 * (31 * (31 * indices.contentHashCode() +
            positions.contentHashCode()) + texCoords.contentHashCode()) + colors.contentHashCode()
    }

    private external fun nativeDecode(bytes: ByteArray, colorsUniqueId: Int): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeGetIndices(handle: Long): IntArray
    private external fun nativeGetPositions(handle: Long): FloatArray
    private external fun nativeGetTexCoords(handle: Long): FloatArray
    private external fun nativeGetColors(handle: Long): FloatArray
    private external fun nativeGetColorsCount(handle: Long): Int
    private external fun nativeFillColors(handle: Long, out: FloatArray, capacity: Int): Int
    private external fun nativeGetPositionsCount(handle: Long): Int
    private external fun nativeFillPositions(handle: Long, out: FloatArray, capacity: Int): Int
    private external fun nativeDecodeProbePoints(bytes: ByteArray, colorsUniqueId: Int, sizesOut: IntArray): Long
    private external fun nativeFillReleasePoints(handle: Long, posOut: FloatArray, colorsOut: FloatArray)
}
