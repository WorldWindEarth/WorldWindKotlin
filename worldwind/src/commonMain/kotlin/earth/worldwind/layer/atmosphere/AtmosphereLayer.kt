package earth.worldwind.layer.atmosphere

import earth.worldwind.MR
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.buffer.ShortBufferObject
import earth.worldwind.render.image.ImageConfig
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource.Companion.fromResource
import earth.worldwind.util.SunPosition
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_ELEMENT_ARRAY_BUFFER
import kotlinx.datetime.Instant

open class AtmosphereLayer: AbstractLayer("Atmosphere") {
    override var isPickEnabled = false
    var nightImageSource = fromResource(MR.images.dnb_land_ocean_ice_2012)
    var nightImageOptions = ImageOptions(ImageConfig.RGB_565)
    /**
     * Display light location on a specified time point. If null, then light is located at camera position.
     */
    var time : Instant? = null
    protected val activeLightDirection = Vec3()
    private val fullSphereSector = Sector().setFullSphere()

    companion object {
        private val VERTEX_POINTS_KEY = AtmosphereLayer::class.simpleName + ".points"
        private val TRI_STRIP_ELEMENTS_KEY = AtmosphereLayer::class.simpleName + ".triStripElements"
    }

    override fun doRender(rc: RenderContext) {
        // Compute the currently active light direction.
        determineLightDirection(rc)

        // Render the sky portion of the atmosphere.
        renderSky(rc)

        // Render the ground portion of the atmosphere.
        renderGround(rc)
    }

    protected open fun determineLightDirection(rc: RenderContext) {
        // TODO Make light/sun direction an optional property of the WorldWindow and attach it to the RenderContext each frame
        // TODO RenderContext property defaults to the eye lat/lon like we have below
        time?.let {
            val lightLocation = SunPosition.getAsGeographicLocation(it)
            rc.globe.geographicToCartesianNormal(
                lightLocation.latitude, lightLocation.longitude, activeLightDirection
            )
        } ?: rc.globe.geographicToCartesianNormal(
            rc.camera.position.latitude, rc.camera.position.longitude, activeLightDirection
        )
    }

    protected open fun renderSky(rc: RenderContext) {
        val pool = rc.getDrawablePool<DrawableSkyAtmosphere>()
        val drawable = DrawableSkyAtmosphere.obtain(pool)
        val size = 128
        drawable.program = rc.getShaderProgram { SkyProgram() }
        drawable.vertexPoints = rc.getBufferObject(VERTEX_POINTS_KEY) {
            assembleVertexPoints(rc, size, size, rc.atmosphereAltitude.toFloat())
        }
        drawable.triStripElements = rc.getBufferObject(TRI_STRIP_ELEMENTS_KEY) { assembleTriStripElements(size, size) }
        drawable.lightDirection.copy(activeLightDirection)
        drawable.globeRadius = rc.globe.equatorialRadius
        drawable.atmosphereAltitude = rc.atmosphereAltitude
        rc.offerSurfaceDrawable(drawable, Double.POSITIVE_INFINITY)
    }

    protected open fun renderGround(rc: RenderContext) {
        if (rc.terrain.sector.isEmpty) return  // no terrain surface to render on
        val pool = rc.getDrawablePool<DrawableGroundAtmosphere>()
        val drawable = DrawableGroundAtmosphere.obtain(pool)
        drawable.program = rc.getShaderProgram { GroundProgram() }
        drawable.lightDirection.copy(activeLightDirection)
        drawable.globeRadius = rc.globe.equatorialRadius
        drawable.atmosphereAltitude = rc.atmosphereAltitude

        // Use this layer's night image when the light location is different from the eye location.
        drawable.nightTexture = time?.run { rc.getTexture(nightImageSource, nightImageOptions) }
        rc.offerSurfaceDrawable(drawable, Double.POSITIVE_INFINITY)
    }

    protected open fun assembleVertexPoints(rc: RenderContext, numLat: Int, numLon: Int, altitude: Float): FloatBufferObject {
        val count = numLat * numLon
        val altitudes = FloatArray(count)
        altitudes.fill(altitude)
        val points = FloatArray(count * 3)
        rc.globe.geographicToCartesianGrid(
            fullSphereSector, numLat, numLon, altitudes, 1.0f, null, points, 0, 0
        )
        return FloatBufferObject(GL_ARRAY_BUFFER, points)
    }

    // TODO move this into a basic tessellator implementation in WorldWind
    // TODO tessellator and atmosphere needs the TriStripIndices - could we add these to BasicGlobe (needs to be on a static context)
    // TODO may need to switch the tessellation method anyway - geographic grid may produce artifacts at the poles
    protected open fun assembleTriStripElements(numLat: Int, numLon: Int): ShortBufferObject {
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
        return ShortBufferObject(GL_ELEMENT_ARRAY_BUFFER, elements)
    }
}