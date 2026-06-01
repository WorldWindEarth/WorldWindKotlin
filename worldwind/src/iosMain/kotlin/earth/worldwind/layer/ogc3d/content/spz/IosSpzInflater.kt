package earth.worldwind.layer.ogc3d.content.spz

/** Kotlin/Native has no built-in gzip — leave [SpzGaussianLoader.inflater] unset.
 *  Apps that need SPZ on iOS register a `compression`-framework backed [SpzInflater]
 *  before fetching the first SPZ tile. */
actual fun installDefaultSpzInflater() {
    // Intentionally no-op.
}
