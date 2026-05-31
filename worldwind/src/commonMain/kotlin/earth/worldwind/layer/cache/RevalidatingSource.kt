package earth.worldwind.layer.cache

/**
 * A cache-aware source that fires [onTileRevalidated] — **off the render thread** — after a
 * stale tile has been re-downloaded and written through (the stale-while-revalidate refresh).
 * The render layer wires this to drop the tile's cached texture / coverage entry and request a
 * redraw, so the freshly-cached bytes appear on-screen without waiting for the next reload.
 *
 * Coordinates are the source's native `(z, x, y)` — slippy `(z, column, row)` for image tile
 * sources, `(matrix ordinal, column, row)` for elevation source factories.
 *
 * Implemented by [CachedTileSource] and the GeoPackage elevation source factory. The callback
 * must marshal any cache mutation onto the render thread itself (the render caches aren't
 * synchronized); [WorldWind.requestRedraw][earth.worldwind.WorldWind.Companion.requestRedraw]
 * is the safe cross-thread signal.
 */
interface RevalidatingSource {
    var onTileRevalidated: ((z: Int, x: Int, y: Int) -> Unit)?
}
