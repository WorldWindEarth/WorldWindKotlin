package earth.worldwind.formats.gltf.meshopt

/**
 * Install a Meshopt decoder into [earth.worldwind.formats.gltf.GltfDecoderRegistry] so
 * [earth.worldwind.formats.gltf.GltfReader] can decode `EXT_meshopt_compression` bufferViews.
 * Android / JVM / iOS bind a JNI/cinterop wrapper around meshoptimizer (MIT); web wraps the
 * `meshoptimizer` npm package. iOS requires `-Pworldwind.meshopt.buildIosNative=true`.
 * Idempotent.
 */
expect suspend fun installMeshoptDecoder()

/** Pair-release for [installMeshoptDecoder]. */
expect fun releaseMeshoptDecoder()
