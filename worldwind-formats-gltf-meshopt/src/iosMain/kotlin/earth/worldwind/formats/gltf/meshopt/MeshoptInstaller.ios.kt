package earth.worldwind.formats.gltf.meshopt

/** iOS no-op stub. The cinterop-backed implementation under `src/iosMainNative/` is
 *  opted into via `-Pworldwind.meshopt.buildIosNative=true` (requires CMake + Xcode CLT). */
actual suspend fun installMeshoptDecoder() = Unit

actual fun releaseMeshoptDecoder() = Unit
