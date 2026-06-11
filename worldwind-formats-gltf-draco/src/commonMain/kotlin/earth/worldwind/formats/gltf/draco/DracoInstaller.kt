package earth.worldwind.formats.gltf.draco

/**
 * Install a platform-specific Draco mesh decoder into
 * [earth.worldwind.formats.gltf.GltfDecoderRegistry] so [earth.worldwind.formats.gltf.GltfReader]
 * can render `KHR_draco_mesh_compression`-encoded primitives.
 *
 * Android / JVM / iOS bind a JNI/cinterop wrapper around Google's open-source libdraco;
 * web wraps the published `draco3d` npm package. iOS requires `-Pworldwind.draco.buildIosNative=true`
 * (plus CMake + Xcode CLT) — otherwise the iOS actual is a no-op stub. Suspend because
 * `System.loadLibrary` blocks. Idempotent.
 */
expect suspend fun installDracoDecoder()

/** Pair-release for [installDracoDecoder]. Clears the engine's decoder slot. */
expect fun releaseDracoDecoder()
