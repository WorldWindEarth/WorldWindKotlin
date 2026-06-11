package earth.worldwind.formats.gltf.meshopt

import earth.worldwind.util.Logger
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JVM JNI binding. Host-built `libmeshopt_bridge.{so,dylib,dll}` extracted from
 *  JAR resources `native/<os>-<arch>/` at install time. */
internal object NativeMeshopt {

    @Volatile private var loaded = false
    private val loadLock = Any()

    /** Returns true once the host's libmeshopt_bridge is loaded; false if it wasn't bundled. */
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
                    "libmeshopt_bridge native library not bundled — meshopt bufferViews " +
                        "will skip. Install cmake or pass " +
                        "-Pworldwind.meshopt.buildJvmNative=true. (${e.message})",
                )
                false
            }
        }
    }

    fun decode(compressed: ByteArray, count: Int, byteStride: Int, mode: Int, filter: Int): ByteArray =
        nativeDecode(compressed, count, byteStride, mode, filter)
            ?: error("Meshopt decode failed (mode=$mode, count=$count, stride=$byteStride)")

    private fun load() {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()
        val (subDir, libFile) = when {
            "mac" in osName || "darwin" in osName -> {
                val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
                "macos-$arch" to "libmeshopt_bridge.dylib"
            }
            "linux" in osName -> {
                val arch = if ("aarch64" in osArch || "arm64" in osArch) "aarch64" else "x86_64"
                "linux-$arch" to "libmeshopt_bridge.so"
            }
            "windows" in osName -> "windows-x86_64" to "meshopt_bridge.dll"
            else -> throw UnsatisfiedLinkError("worldwind-formats-gltf-meshopt: unsupported host $osName / $osArch")
        }
        val resource = "/native/$subDir/$libFile"
        val stream = NativeMeshopt::class.java.getResourceAsStream(resource)
            ?: throw UnsatisfiedLinkError(
                "worldwind-formats-gltf-meshopt: native library not bundled for $subDir (resource $resource not found). " +
                    "Run `./gradlew :worldwind-formats-gltf-meshopt:buildMeshoptBridgeJvm` to compile for this host."
            )
        val tmpDir = Files.createTempDirectory("worldwind-meshopt")
        val tmpFile = tmpDir.resolve(libFile)
        stream.use { Files.copy(it, tmpFile, StandardCopyOption.REPLACE_EXISTING) }
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { Files.deleteIfExists(tmpFile); Files.deleteIfExists(tmpDir) }
        })
        System.load(tmpFile.toAbsolutePath().toString())
    }

    @JvmStatic private external fun nativeDecode(
        compressed: ByteArray, count: Int, byteStride: Int, mode: Int, filter: Int,
    ): ByteArray?
}
