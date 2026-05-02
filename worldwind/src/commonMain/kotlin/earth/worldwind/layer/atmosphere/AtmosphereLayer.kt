package earth.worldwind.layer.atmosphere

import earth.worldwind.MR
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.layer.atmosphere.bruneton.BrunetonAtmosphereModel
import earth.worldwind.layer.atmosphere.bruneton.DrawableBrunetonPrecompute
import earth.worldwind.layer.atmosphere.bruneton.DrawableBrunetonGround
import earth.worldwind.layer.atmosphere.bruneton.DrawableBrunetonSky
import earth.worldwind.layer.atmosphere.bruneton.programs.BrunetonGroundProgram
import earth.worldwind.layer.atmosphere.bruneton.programs.BrunetonSkyProgram
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.util.NumericArray
import earth.worldwind.util.SunPosition
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import kotlin.time.Instant

open class AtmosphereLayer: AbstractLayer("Atmosphere") {
    override var isPickEnabled = false
    var nightImageSource = fromResource(MR.images.black_marble_2016)
    var nightImageOptions = ImageOptions(ImageConfig.RGB_565)
    /**
     * Display light location on a specified time point. If null, then light is located at camera position.
     */
    var time : Instant? = null
    /**
     * Per-frame callback invoked when [time] is null to compute the world-space sun direction.
     * The callback should write the desired direction (unit vector pointing toward the light)
     * into `rc.lightDirection`. When both [time] is null and this provider is null, the layer
     * leaves the WorldWind default (camera-foot-point normal) in place. When [time] is set it
     * takes precedence and this provider is ignored. Tutorials use this to apply a custom
     * camera-relative sun angle without enabling the day/night terminator (which only fires
     * when [time] is set).
     */
    var lightDirectionProvider: ((RenderContext) -> Unit)? = null
    /**
     * Toggle for the Bruneton precomputed-atmospheric-scattering pipeline. When `true`:
     * - First frame after enable: precompute four LUT textures (~100-500 ms one-shot cost).
     * - Sky dome rendered via [BrunetonSkyProgram] sampling the LUTs.
     * - Ground rendered via [BrunetonGroundProgram] (Bruneton aerial perspective).
     * ES3 / WebGL2 / GL 3.3+ only — on ES2 contexts the precompute drawable bails and the
     * legacy O'Neil sky / ground pipeline runs unchanged.
     */
    var useBruneton: Boolean = true
    /**
     * Bruneton-specific exposure factor for the sky tonemap. Bruneton outputs linear
     * physical radiance in W·m⁻²·sr⁻¹; the tonemap applies `1 - exp(-radiance × exposure)`
     * then `pow(., 1/2.2)`. Default 10 produces a balanced mid-day sky; tune higher for
     * darker/twilight scenes.
     */
    var brunetonExposure: Float = 10f
    /**
     * Multiplier for the night-image emissive contribution in the Bruneton ground pass. The
     * shader gates the night image to the dark side via `(1 - smoothstep(-0.05, 0.05, μ_s))`
     * and attenuates by aerial transmittance; this knob scales the resulting emission. Set
     * higher for brighter city lights, lower (or 0) to disable the emissive layer.
     */
    var brunetonNightEmissive: Float = 1f
    /**
     * Tonemap exposure for the Bruneton ground pass's additive in-scatter contribution.
     * Defaults to the same value as [brunetonExposure] (10) so the ground composite stays
     * faithful to the reference's single-pass `tonemap(T·ground + in_scatter)` formula.
     * The two-pass overlay scheme this is plugged into can over-saturate horizon haze on
     * top of bright satellite imagery — if that's too strong for your scene, drop this
     * to 3-5 (a custom deviation from the reference).
     */
    var brunetonGroundExposure: Float = 10f
    protected val activeLightDirection = Vec3()
    private val fullSphereSector = Sector().setFullSphere()

    companion object {
        private val VERTEX_POINTS_KEY = AtmosphereLayer::class.simpleName + ".points"
        private val TRI_STRIP_ELEMENTS_KEY = AtmosphereLayer::class.simpleName + ".triStripElements"
        // Approx LUT footprint (4 RGBA16F textures); LRU cache size hint, not a hard cap.
        // transmittance 256·64 + irradiance 64·16 + scattering 256·128·32 ×2 = ~8.4 MB.
        private const val BRUNETON_LUT_BYTES = 9 * 1024 * 1024
    }

    override fun doRender(rc: RenderContext) {
        if (rc.globe.is2D) return // Atmosphere layer is not applicable for 2D globe

        // Compute the currently active light direction.
        determineLightDirection(rc)

        // Bruneton: queue precomputation before any sky / ground draws so the LUTs are
        // populated by the time the runtime kernels (Phases 3-4) sample them. The model
        // gates "actually run" behind isPrecomputed; queuing every frame is cheap.
        if (useBruneton) queueBrunetonPrecompute(rc)

        // Render the sky portion of the atmosphere.
        renderSky(rc)

        // Render the ground portion of the atmosphere.
        renderGround(rc)
    }

    private fun queueBrunetonPrecompute(rc: RenderContext) {
        val cache = rc.renderResourceCache
        // Sized as a small fixed footprint — the model itself owns ~8 MB of LUT textures
        // but the cache size param is just an LRU hint; pin via a reasonable upper bound.
        val model = (cache[BrunetonAtmosphereModel.KEY] as? BrunetonAtmosphereModel)
            ?: BrunetonAtmosphereModel().also { cache.put(BrunetonAtmosphereModel.KEY, it, BRUNETON_LUT_BYTES) }

        // Steady-state fast path: skip the program rebind + drawable queue once the LUTs
        // are populated. The model resets [isPrecomputed] on context loss, so the next
        // frame after a teardown / resume re-enters the precompute path naturally.
        if (model.isPrecomputed) return

        model.bindPrograms(rc)
        val pool = rc.getDrawablePool(DrawableBrunetonPrecompute.KEY)
        val drawable = DrawableBrunetonPrecompute.obtain(pool)
        drawable.model = model
        // Surface group, very-back zOrder so it runs before sky / ground / shapes within the
        // surface phase. Background group would also work but is reserved for terrain tiles.
        rc.offerSurfaceDrawable(drawable, Double.NEGATIVE_INFINITY)
    }

    protected open fun determineLightDirection(rc: RenderContext) {
        // [time] takes precedence: it drives both the day/night terminator and sun-direction
        // shadows. When null, an optional [lightDirectionProvider] can write a custom direction
        // into [rc.lightDirection] for shadows-without-terminator scenarios. Otherwise the
        // WorldWind default (camera-foot-point normal) is left in place.
        val t = time
        if (t != null) {
            val lightLocation = SunPosition.getAsGeographicLocation(t)
            rc.globe.geographicToCartesianNormal(
                lightLocation.latitude, lightLocation.longitude, rc.lightDirection
            )
        } else {
            lightDirectionProvider?.invoke(rc)
        }
        activeLightDirection.copy(rc.lightDirection)
    }

    protected open fun renderSky(rc: RenderContext) {
        if (useBruneton) {
            renderBrunetonSky(rc)
            return
        }
        val pool = rc.getDrawablePool(DrawableSkyAtmosphere.KEY)
        val drawable = DrawableSkyAtmosphere.obtain(pool)
        val size = 128
        drawable.program = rc.getShaderProgram(SkyProgram.KEY) { SkyProgram() }
        drawable.vertexPoints = rc.getBufferObject(VERTEX_POINTS_KEY) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(VERTEX_POINTS_KEY, 1) { NumericArray.Floats(assembleVertexPoints(rc, size, size, rc.atmosphereAltitude.toFloat())) }
        drawable.triStripElements = rc.getBufferObject(TRI_STRIP_ELEMENTS_KEY) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(TRI_STRIP_ELEMENTS_KEY, 1) { NumericArray.Shorts(assembleTriStripElements(size, size)) }
        drawable.lightDirection.copy(activeLightDirection)
        drawable.globeRadius = rc.globe.equatorialRadius
        drawable.atmosphereAltitude = rc.atmosphereAltitude
        rc.offerSurfaceDrawable(drawable, Double.POSITIVE_INFINITY)
    }

    private fun renderBrunetonSky(rc: RenderContext) {
        val cache = rc.renderResourceCache
        val model = cache[BrunetonAtmosphereModel.KEY] as? BrunetonAtmosphereModel
            ?: return // bind first happens in queueBrunetonPrecompute, run earlier this frame

        val pool = rc.getDrawablePool(DrawableBrunetonSky.KEY)
        val drawable = DrawableBrunetonSky.obtain(pool)
        val size = 128
        drawable.program = rc.getShaderProgram(BrunetonSkyProgram.KEY) { BrunetonSkyProgram() }
        drawable.model = model
        drawable.vertexPoints = rc.getBufferObject(VERTEX_POINTS_KEY) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(VERTEX_POINTS_KEY, 1) { NumericArray.Floats(assembleVertexPoints(rc, size, size, rc.atmosphereAltitude.toFloat())) }
        drawable.triStripElements = rc.getBufferObject(TRI_STRIP_ELEMENTS_KEY) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(TRI_STRIP_ELEMENTS_KEY, 1) { NumericArray.Shorts(assembleTriStripElements(size, size)) }
        drawable.sunDirection.copy(activeLightDirection)
        drawable.exposure = brunetonExposure
        rc.offerSurfaceDrawable(drawable, Double.POSITIVE_INFINITY)
    }

    protected open fun renderGround(rc: RenderContext) {
        if (rc.terrain.sector.isEmpty) return  // no terrain surface to render on
        if (useBruneton) {
            renderBrunetonGround(rc)
            return
        }
        val pool = rc.getDrawablePool(DrawableGroundAtmosphere.KEY)
        val drawable = DrawableGroundAtmosphere.obtain(pool)
        drawable.program = rc.getShaderProgram(GroundProgram.KEY) { GroundProgram() }
        drawable.lightDirection.copy(activeLightDirection)
        drawable.globeRadius = rc.globe.equatorialRadius
        drawable.atmosphereAltitude = rc.atmosphereAltitude

        // Use this layer's night image when the light location is different from the eye location.
        drawable.nightTexture = time?.run { rc.getTexture(nightImageSource, nightImageOptions) }
        rc.offerSurfaceDrawable(drawable, Double.POSITIVE_INFINITY)
    }

    private fun renderBrunetonGround(rc: RenderContext) {
        val cache = rc.renderResourceCache
        val model = cache[BrunetonAtmosphereModel.KEY] as? BrunetonAtmosphereModel ?: return

        val pool = rc.getDrawablePool(DrawableBrunetonGround.KEY)
        val drawable = DrawableBrunetonGround.obtain(pool)
        drawable.program = rc.getShaderProgram(BrunetonGroundProgram.KEY) { BrunetonGroundProgram() }
        drawable.model = model
        drawable.sunDirection.copy(activeLightDirection)
        drawable.exposure = brunetonExposure
        drawable.groundExposure = brunetonGroundExposure
        drawable.nightEmissive = brunetonNightEmissive
        // Same convention as the legacy path: night image only when [time] is set (i.e.
        // when the sun position is independent of the camera, so a terminator is meaningful).
        drawable.nightTexture = time?.run { rc.getTexture(nightImageSource, nightImageOptions) }
        rc.offerSurfaceDrawable(drawable, Double.POSITIVE_INFINITY)
    }

    protected open fun assembleVertexPoints(rc: RenderContext, numLat: Int, numLon: Int, altitude: Float): FloatArray {
        val count = numLat * numLon
        val altitudes = FloatArray(count)
        altitudes.fill(altitude)
        val points = FloatArray(count * 3)
        // Ignore vertical exaggeration for atmosphere
        rc.globe.projection.geographicToCartesianGrid(rc.globe.ellipsoid, fullSphereSector, numLat, numLon, altitudes, 1.0, null, 0.0, points, 0, 0)
        return points
    }

    // TODO move this into a basic tessellator implementation in WorldWind
    // TODO tessellator and atmosphere needs the TriStripIndices - could we add these to BasicGlobe (needs to be on a static context)
    // TODO may need to switch the tessellation method anyway - geographic grid may produce artifacts at the poles
    protected open fun assembleTriStripElements(numLat: Int, numLon: Int): ShortArray {
        // Allocate a buffer to hold the indices.
        val count = ((numLat - 1) * numLon + (numLat - 2)) * 2
        val elements = ShortArray(count)
        var pos = 0
        var vertex = 0
        for (latIndex in 0 until numLat - 1) {
            // Create a triangle strip joining each adjacent column of vertices, starting in the bottom left corner and
            // proceeding to the right. The first vertex starts with the left row of vertices and moves right to create
            // a counterclockwise winding order.
            for (lonIndex in 0 until numLon) {
                vertex = lonIndex + latIndex * numLon
                elements[pos++] = (vertex + numLon).toShort()
                elements[pos++] = vertex.toShort()
            }

            // Insert indices to create 2 degenerate triangles:
            // - one for the end of the current row, and
            // - one for the beginning of the next row
            if (latIndex < numLat - 2) {
                elements[pos++] = vertex.toShort()
                elements[pos++] = ((latIndex + 2) * numLon).toShort()
            }
        }
        return elements
    }
}