package earth.worldwind.formats.gltf.ktx2

/**
 * Install a KTX2 / Basis-Universal texture decoder into [earth.worldwind.formats.gltf.GltfDecoderRegistry]
 * so [earth.worldwind.formats.gltf.GltfReader] can transcode `KHR_texture_basisu` textures.
 * Android / JVM / iOS bind libktx (Apache 2.0); web is a no-op stub for now (textures
 * fall back to baseColorFactor only). Decode output is RGBA8888. iOS requires
 * `-Pworldwind.ktx2.buildIosNative=true`. Idempotent.
 */
expect suspend fun installKtx2Decoder()

/** Pair-release for [installKtx2Decoder]. */
expect fun releaseKtx2Decoder()
