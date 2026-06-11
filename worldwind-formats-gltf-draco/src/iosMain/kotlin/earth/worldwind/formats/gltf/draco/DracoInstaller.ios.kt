package earth.worldwind.formats.gltf.draco

/**
 * iOS no-op stub. The real cinterop-backed implementation lives at
 * `src/iosMainNative/kotlin/.../DracoInstaller.ios.kt` and is opted into with
 * `-Pworldwind.draco.buildIosNative=true` (also requires CMake + Xcode CLT installed).
 *
 * Without the opt-in, this stub keeps the iOS build green for developers who don't have
 * the native toolchain set up. Draco-compressed tiles render as empty geometry on iOS.
 */
actual suspend fun installDracoDecoder() = Unit

actual fun releaseDracoDecoder() = Unit
