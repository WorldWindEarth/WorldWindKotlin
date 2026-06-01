package earth.worldwind.layer.ogc3d.content.spz

/** Browser targets (js + wasmJs) have no synchronous gzip primitive — `CompressionStream`
 *  is async-only. Leave [SpzGaussianLoader.inflater] unset; apps that need SPZ in the
 *  browser register a pako-binding-backed [SpzInflater] before fetching the first SPZ tile. */
actual fun installDefaultSpzInflater() {
    // Intentionally no-op.
}
