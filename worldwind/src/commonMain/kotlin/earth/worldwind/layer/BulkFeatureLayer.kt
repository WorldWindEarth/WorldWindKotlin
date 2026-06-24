package earth.worldwind.layer

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.layer.source.BulkFeatureSource
import earth.worldwind.layer.source.CachedFeatureRow
import earth.worldwind.layer.source.DEFAULT_DENSITY
import earth.worldwind.layer.source.DEFAULT_LABEL_VISIBILITY_THRESHOLD
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Renderable
import earth.worldwind.shape.ShapeAttributes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Vector-feature layer driven by a [BulkFeatureSource]. The source delivers
 * `(geometry, properties_json)` rows from one of:
 *   * GeoJSON document: [earth.worldwind.layer.source.GeoJsonBulkFeatureSource].
 *   * network WFS: [earth.worldwind.ogc.wfs.WfsBulkFeatureSource] → WFS GetFeature pipeline.
 *   * network Shapefile: [earth.worldwind.formats.shapefile.ShapefileBulkFeatureSource] →
 *     one HTTP fetch of the .shp/.dbf/.prj triple.
 *   * cache: [earth.worldwind.layer.cache.CachedBulkFeatureSource] wrapping a
 *     [earth.worldwind.layer.cache.FeatureStore].
 *   * cache-first then network: `CachedBulkFeatureSource(WfsBulkFeatureSource(...), store)`.
 *
 * "Bulk" because one source fetch pulls *every* feature in the layer; there is no per-tile
 * addressing. Compare:
 *   * [earth.worldwind.layer.mvt.MvtVectorLayer] — bytes-per-tile vector source.
 *   * [earth.worldwind.layer.buildings.OsmBuildingsLayer] — features-per-tile source.
 *
 * Styling fires twice per feature when [autoApplyStyle] is `true` (default): first
 * [applyFeatureStyle] reads GeoJSON simplestyle keys (`stroke`, `fill`, `icon`, etc.) onto
 * the renderable, then [customLogicToApplyProperties] runs and can override anything.
 * Disable with `autoApplyStyle = false` when the source emits properties that aren't
 * simplestyle and you don't want the no-match defaults applied.
 *
 * Point features become a [Label] when the resolved feature name is non-blank and the
 * `icon` property is absent; otherwise a [Placemark]. LineStrings become [Path]s, Polygons
 * become [Polygon]s, MultiPolygons fan out to N [Polygon]s sharing one properties map.
 */
open class BulkFeatureLayer(
    var source: BulkFeatureSource,
    displayName: String? = null,
    /**
     * Default [ShapeAttributes] applied to every [Polygon] / [Path] this layer builds.
     * `null` leaves the renderable at its own default (white fill, black outline). Set this
     * to colour an entire layer in one go without writing a per-feature lambda — the per-
     * feature [customLogicToApplyProperties] still gets a chance to override attributes
     * after this default is installed.
     */
    var shapeAttributes: ShapeAttributes? = null,
    /** Apply [applyFeatureStyle] from the feature's properties before
     *  [customLogicToApplyProperties] runs. Off for sources whose properties are pure data
     *  with no styling intent (some WFS endpoints). */
    var autoApplyStyle: Boolean = true,
    /** Fallback line colour written when a feature has no `stroke` property. `null`
     *  (default) leaves the renderable's existing outline alone — preserves the layer's
     *  [shapeAttributes] template (e.g. Shapefile's translucent fill) when the source
     *  has no simplestyle keys. */
    var defaultLineColor: Color? = null,
    /** Fallback fill colour written when a feature has no `fill` property. Same null
     *  semantics as [defaultLineColor]. */
    var defaultFillColor: Color? = null,
    /** Multiplier applied to placemark icon scale — pass per-display density to keep
     *  icons consistent across DPI. Default `1.0f` leaves scale unchanged. */
    var density: Float = DEFAULT_DENSITY,
    /** Visibility threshold (eye-distance, meters) set on every emitted [Label]. `0.0`
     *  (default) keeps labels visible at any distance. */
    var labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
    /** Force every emitted renderable's altitude mode. `null` (default) picks
     *  [AltitudeMode.ABSOLUTE] when the cached geometry has any non-null z, else
     *  [AltitudeMode.CLAMP_TO_GROUND]. Set explicitly when the source has z values that
     *  shouldn't be rendered as altitude (e.g. shapefile measure-z, GeoJSON elevation
     *  values you want flattened). */
    var defaultAltitudeMode: AltitudeMode? = null,
    var customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
) : RenderableLayer(displayName), VectorLayer {

    /**
     * Pull rows from [source] and atomically replace this layer's renderables. Accumulates
     * the full set before swapping in to avoid mid-load flicker (paginated WFS responses
     * still complete in one swap). Per renderable: install [applyFeatureStyle] defaults if
     * [autoApplyStyle], then run [customLogicToApplyProperties].
     */
    open suspend fun load() {
        val incoming = buildRenderables(source.fetchAll())
        clearRenderables()
        addAllRenderables(incoming)
    }

    /**
     * When `true`, each frame reads only the visible viewport via [BulkFeatureSource.readBySector]
     * instead of loading everything via [load]. Pair with a cache-backed [source]
     * (`CachedBulkFeatureSource` over a GeoPackage store): downloaded once, then every pan/zoom is
     * a BBOX query off its spatial index so RAM holds only visible features. A plain network source
     * has no index — its default `readBySector` falls back to `fetchAll`, so memory isn't bounded.
     */
    var autoRefreshViewport = false
    /** Debounce after the viewport settles before reading, when [autoRefreshViewport] is on. */
    var autoRefreshDebounceMillis: Long
        get() = driver.debounceMillis
        set(value) { driver.debounceMillis = value }
    /** Fraction the visible sector is padded by on each side before reading (hysteresis). */
    var autoRefreshMargin: Double
        get() = driver.margin
        set(value) { driver.margin = value }

    private val driver = ViewportRefreshDriver()

    override fun doRender(rc: RenderContext) {
        // Skip pick mode — its tiny cursor frustum isn't the visible extent.
        if (autoRefreshViewport && !rc.isPickMode) {
            driver.onRender(
                rc.terrain.sector,
                apply = { clearRenderables(); addAllRenderables(it) },
                fetch = { buildRenderables(source.readBySector(it)) },
            )
        }
        super.doRender(rc)
    }

    /** Collect [rows] into styled renderables — shared by [load] and the viewport path. */
    private suspend fun buildRenderables(rows: Flow<CachedFeatureRow>): ArrayList<Renderable> {
        val renderer = FeatureRenderer(
            shapeAttributes, autoApplyStyle, defaultLineColor, defaultFillColor,
            density, labelVisibilityThreshold, defaultAltitudeMode, customLogicToApplyProperties,
        )
        val out = ArrayList<Renderable>()
        rows.collect { row -> out += renderer.build(row) }
        return out
    }

    /** Release the [source]'s resources (e.g. its reused HTTP client) and stop auto-refresh. Call
     *  when discarding the layer so a network-backed source doesn't leak its client. Idempotent. */
    open fun close() {
        driver.cancel()
        source.close()
    }

}
