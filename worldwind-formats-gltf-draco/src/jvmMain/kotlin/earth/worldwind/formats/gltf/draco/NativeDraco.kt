package earth.worldwind.formats.gltf.draco

import earth.worldwind.util.Logger
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JVM JNI binding. The native bridge (`libdraco_bridge.so` / `.dylib` / `.dll`) is built
 * by `cmakeBuild_*` Gradle tasks for the build host, then bundled as a JAR resource under
 * `native/<os>-<arch>/`. At first decode the [load] helper extracts the right binary into
 * a temp file and calls [System.load] on it.
 *
 * The JNI symbol convention matches the Android binding (same Kotlin package + class
 * name), so the C++ bridge source is identical and lives at
 * `src/jvmCommonMain/cpp/draco_bridge.cpp` — both targets compile it.
 */
internal object NativeDraco {

    @Volatile private var loaded = false
    private val loadLock = Any()

    /** Returns true once the host's libdraco_bridge is loaded; false if it wasn't bundled
     *  (cmake wasn't on PATH at build time, so the host binary skipped). */
    fun tryEnsureInitialized(): Boolean {
        if (loaded) return true
        synchronized(loadLock) {
            if (loaded) return true
            return try {
                load()
                loaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Logger.log(
                    Logger.WARN,
                    "libdraco_bridge native library not bundled — Draco-compressed tiles will skip. " +
                        "Install cmake (brew install cmake / apt install cmake) or pass " +
                        "-Pworldwind.draco.buildJvmNative=true and rebuild. (${e.message})",
                )
                false
            }
        }
    }

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

    /** Per-thread pools for the biggest JNI transfers. Buffer reuse is safe because
     *  consumers copy out before the parse call returns. */
    private val positionsPool = ThreadLocal.withInitial { FloatArray(0) }
    private val colorsPool = ThreadLocal.withInitial { FloatArray(0) }
    private val probeSizesBuf = ThreadLocal.withInitial { IntArray(2) }

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

    internal data class Decoded(
        val indices: IntArray,
        /** May be pool-backed and larger than `positionsCount` — read only the first
         *  `positionsCount` values (= `pointCount * 3`). */
        val positions: FloatArray,
        /** Number of valid floats in [positions]. Defaults to `positions.size` for non-pool
         *  paths (web / iOS). */
        val positionsCount: Int = positions.size,
        val texCoords: FloatArray,
        /** May be pool-backed and larger than [colorsCount] — read only the first values. */
        val colors: FloatArray,
        /** Number of valid floats in [colors]. */
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

    private fun load() {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val (subDir, libFile) = when {
            "mac" in osName || "darwin" in osName -> {
                val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
                "macos-$arch" to "libdraco_bridge.dylib"
            }
            "linux" in osName -> {
                val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
                "linux-$arch" to "libdraco_bridge.so"
            }
            "windows" in osName -> "windows-x86_64" to "draco_bridge.dll"
            else -> throw UnsatisfiedLinkError("worldwind-draco: unsupported host $osName / $osArch")
        }
        val resource = "/native/$subDir/$libFile"
        val stream = NativeDraco::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "worldwind-draco: native library not bundled for $subDir (resource $resource not found). " +
                    "Run `./gradlew :worldwind-draco:buildDracoBridgeJvm` to compile it for this host."
            )
        val tmpDir = Files.createTempDirectory("worldwind-draco")
        val tmpFile = tmpDir.resolve(libFile)
        stream.use { Files.copy(it, tmpFile, StandardCopyOption.REPLACE_EXISTING) }
        // Delete on JVM exit so the tmpdir doesn't accrete copies across runs.
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { Files.deleteIfExists(tmpFile); Files.deleteIfExists(tmpDir) }
        })
        System.load(tmpFile.toAbsolutePath().toString())
    }

    private val EMPTY_FLOATS = FloatArray(0)

    @JvmStatic private external fun nativeDecode(bytes: ByteArray, colorsUniqueId: Int): Long
    @JvmStatic private external fun nativeRelease(handle: Long)
    @JvmStatic private external fun nativeGetIndices(handle: Long): IntArray
    @JvmStatic private external fun nativeGetPositions(handle: Long): FloatArray
    @JvmStatic private external fun nativeGetTexCoords(handle: Long): FloatArray
    @JvmStatic private external fun nativeGetColors(handle: Long): FloatArray
    @JvmStatic private external fun nativeGetColorsCount(handle: Long): Int
    @JvmStatic private external fun nativeFillColors(handle: Long, out: FloatArray, capacity: Int): Int
    @JvmStatic private external fun nativeGetPositionsCount(handle: Long): Int
    @JvmStatic private external fun nativeFillPositions(handle: Long, out: FloatArray, capacity: Int): Int
    @JvmStatic private external fun nativeDecodeProbePoints(bytes: ByteArray, colorsUniqueId: Int, sizesOut: IntArray): Long
    @JvmStatic private external fun nativeFillReleasePoints(handle: Long, posOut: FloatArray, colorsOut: FloatArray)
}
