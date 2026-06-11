package earth.worldwind.formats.gltf.ktx2

import earth.worldwind.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * JNI binding to the locally-built `libktx_bridge.so`. The CMake build at
 * `src/androidMain/cpp/` fetches Khronos KTX-Software and statically links it.
 */
internal object NativeKtx2 {

    @Volatile private var loaded = false
    private val loadMutex = Mutex()

    /** Returns true once `libktx_bridge.so` is loaded; false if NDK + CMake weren't
     *  installed at build time so the .so wasn't bundled. Mutex-guarded. */
    suspend fun tryEnsureInitialized(): Boolean = withContext(Dispatchers.Default) {
        if (loaded) return@withContext true
        loadMutex.withLock {
            if (loaded) return@withLock true
            try {
                System.loadLibrary("ktx_bridge")
                loaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Logger.log(
                    Logger.WARN,
                    "libktx_bridge.so not bundled — KTX2 textures will fall back to baseColorFactor. " +
                        "Install NDK + CMake via Android Studio's SDK Manager, or pass " +
                        "-Pworldwind.ktx2.buildAndroidNative=true. (${e.message})",
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

    private external fun nativeDecode(bytes: ByteArray): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeGetWidth(handle: Long): Int
    private external fun nativeGetHeight(handle: Long): Int
    private external fun nativeGetRgba(handle: Long): ByteArray
}
