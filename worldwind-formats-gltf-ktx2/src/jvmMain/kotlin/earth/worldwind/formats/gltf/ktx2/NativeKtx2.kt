package earth.worldwind.formats.gltf.ktx2

import earth.worldwind.util.Logger
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JVM JNI binding to the host-built `libktx_bridge.{so,dylib,dll}`. Extracted from JAR
 *  resources `native/<os>-<arch>/` on first install. Same shape as the Android binding —
 *  the C++ bridge source is shared (compiled per host platform via CMake). */
internal object NativeKtx2 {

    @Volatile private var loaded = false
    private val loadLock = Any()

    /** Returns true once the host's libktx_bridge is loaded; false if it wasn't bundled. */
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
                    "libktx_bridge native library not bundled — KTX2 textures will fall back " +
                        "to baseColorFactor. Install cmake or pass " +
                        "-Pworldwind.ktx2.buildJvmNative=true. (${e.message})",
                )
                false
            }
        }
    }

    fun decode(bytes: ByteArray): Decoded {
        val handle = nativeDecode(bytes)
        require(handle != 0L) { "KTX2 decode failed (native returned null handle)" }
        return try {
            Decoded(
                width = nativeGetWidth(handle),
                height = nativeGetHeight(handle),
                rgba = nativeGetRgba(handle),
            )
        } finally {
            nativeRelease(handle)
        }
    }

    internal data class Decoded(val width: Int, val height: Int, val rgba: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other || (other is Decoded &&
            width == other.width && height == other.height && rgba.contentEquals(other.rgba))
        override fun hashCode(): Int = 31 * (31 * width + height) + rgba.contentHashCode()
    }

    private fun load() {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val (subDir, libFile) = when {
            "mac" in osName || "darwin" in osName -> {
                val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
                "macos-$arch" to "libktx_bridge.dylib"
            }
            "linux" in osName -> {
                val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
                "linux-$arch" to "libktx_bridge.so"
            }
            "windows" in osName -> "windows-x86_64" to "ktx_bridge.dll"
            else -> throw UnsatisfiedLinkError("worldwind-formats-gltf-ktx2: unsupported host $osName / $osArch")
        }
        val resource = "/native/$subDir/$libFile"
        val stream = NativeKtx2::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "worldwind-formats-gltf-ktx2: native library not bundled for $subDir (resource $resource not found). " +
                    "Run `./gradlew :worldwind-formats-gltf-ktx2:buildKtx2BridgeJvm` to compile for this host."
            )
        val tmpDir = Files.createTempDirectory("worldwind-ktx2")
        val tmpFile = tmpDir.resolve(libFile)
        stream.use { Files.copy(it, tmpFile, StandardCopyOption.REPLACE_EXISTING) }
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { Files.deleteIfExists(tmpFile); Files.deleteIfExists(tmpDir) }
        })
        System.load(tmpFile.toAbsolutePath().toString())
    }

    @JvmStatic private external fun nativeDecode(bytes: ByteArray): Long
    @JvmStatic private external fun nativeRelease(handle: Long)
    @JvmStatic private external fun nativeGetWidth(handle: Long): Int
    @JvmStatic private external fun nativeGetHeight(handle: Long): Int
    @JvmStatic private external fun nativeGetRgba(handle: Long): ByteArray
}
