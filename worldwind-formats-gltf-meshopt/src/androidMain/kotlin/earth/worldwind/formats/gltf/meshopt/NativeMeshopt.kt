package earth.worldwind.formats.gltf.meshopt

import earth.worldwind.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** JNI binding to the locally-built `libmeshopt_bridge.so`. The CMake build at
 *  `src/androidMain/cpp/` statically links meshoptimizer. */
internal object NativeMeshopt {

    @Volatile private var loaded = false
    private val loadMutex = Mutex()

    /** Returns true once `libmeshopt_bridge.so` is loaded; false if it wasn't bundled
     *  (NDK + CMake absent at build time). Mutex-guarded. */
    suspend fun tryEnsureInitialized(): Boolean = withContext(Dispatchers.Default) {
        if (loaded) return@withContext true
        loadMutex.withLock {
            if (loaded) return@withLock true
            try {
                System.loadLibrary("meshopt_bridge")
                loaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Logger.log(
                    Logger.WARN,
                    "libmeshopt_bridge.so not bundled — EXT_meshopt_compression bufferViews " +
                        "will skip. Install NDK + CMake via Android Studio's SDK Manager, " +
                        "or pass -Pworldwind.meshopt.buildAndroidNative=true. (${e.message})",
                )
                false
            }
        }
    }

    fun decode(compressed: ByteArray, count: Int, byteStride: Int, mode: Int, filter: Int): ByteArray =
        nativeDecode(compressed, count, byteStride, mode, filter)
            ?: error("Meshopt decode failed (mode=$mode, count=$count, stride=$byteStride)")

    private external fun nativeDecode(
        compressed: ByteArray,
        count: Int,
        byteStride: Int,
        mode: Int,
        filter: Int,
    ): ByteArray?
}
