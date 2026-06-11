package earth.worldwind.formats.gltf.ktx2

/** iOS no-op stub. The cinterop-backed implementation under `src/iosMainNative/` is
 *  opted into via `-Pworldwind.ktx2.buildIosNative=true` (requires CMake + Xcode CLT). */
actual suspend fun installKtx2Decoder() = Unit

actual fun releaseKtx2Decoder() = Unit
