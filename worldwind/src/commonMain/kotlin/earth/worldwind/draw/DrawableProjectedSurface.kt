package earth.worldwind.draw

import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Sector
import earth.worldwind.globe.Globe
import earth.worldwind.render.Color
import earth.worldwind.render.Texture
import earth.worldwind.render.program.Surface3DProjectionShaderProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_TEXTURE0
import kotlin.jvm.JvmStatic

/**
 * Surface drawable for the 3D camera-frustum projection path of
 * [earth.worldwind.shape.ProjectedMediaSurface]. Bypasses the surface-skin stage that
 * [DrawableSurfaceQuad] uses for the homography path: there's no intermediate skin
 * texture and no per-tile rasterise-then-resample, just a per-tile direct render of
 * the source texture against the terrain triangulation with a per-fragment image
 * projection. Trade-off: per-shape, per-tile draw call (no batching like
 * [DrawableSurfaceQuad]'s scratch list does), but correct projection on relief.
 *
 * Per-tile precision: the shape's `imageProjection` is in WGS84 ECEF (huge
 * magnitudes); evaluating it against terrain world positions in single-precision
 * float would lose sub-metre precision. Instead, the drawable pre-multiplies it by
 * the tile's vertex-origin translation per tile, producing a tile-local matrix that
 * the shader applies to the float-precise local vertex positions.
 */
open class DrawableProjectedSurface protected constructor() : Drawable {
    var offset = Globe.Offset.Center
    val sector = Sector()

    /**
     * Shader to drive: typically [Surface3DProjectionShaderProgram] for 2D textures or
     * [earth.worldwind.render.program.Surface3DProjectionShaderProgramOES] for Android OES
     * external textures. The shape picks the right one in `makeDrawable` based on the
     * resolved source texture's target.
     */
    var program: Surface3DProjectionShaderProgram? = null

    /** Source texture (video frame or static photo). Must outlive the draw call. */
    var texture: Texture? = null

    /**
     * 4x4 matrix mapping a world-ECEF position to image clip space. Composition:
     * `bias_to_uv * perspective * lookAt(camera, target, up)`. Per-fragment, the
     * shader divides `xy/w` for UV in [0, 1] across the camera's frustum.
     */
    val imageProjection = Matrix4()

    /** Source-texture coord transform (e.g. the vertical flip image / video textures install). */
    val texCoordMatrix = Matrix3()

    val color = Color()
    var opacity = 1.0f

    /**
     * Soft-edge fade margin (UV-space, 0..0.5). 0 (default) is the cheapest path: the
     * shader's uniform-controlled branch skips the fade math entirely so non-fading
     * draws cost nothing extra over a hard cutoff. Non-zero values ramp alpha 0..1
     * across the inner strip on each side to blend the projection into surrounding
     * terrain.
     */
    var fadeMargin = 0.0f

    private var pool: Pool<DrawableProjectedSurface>? = null
    private val mvpMatrix = Matrix4()
    private val imageProjectionLocal = Matrix4()

    companion object {
        val KEY = DrawableProjectedSurface::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableProjectedSurface>): DrawableProjectedSurface {
            val instance = pool.acquire() ?: DrawableProjectedSurface()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        program = null
        texture = null
        imageProjection.setToIdentity()
        texCoordMatrix.setToIdentity()
        color.set(1f, 1f, 1f, 1f)
        opacity = 1f
        fadeMargin = 0f
        offset = Globe.Offset.Center
        sector.setEmpty()
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val prog = program ?: return
        val tex = texture ?: return
        if (!prog.useProgram(dc)) return

        dc.activeTextureUnit(GL_TEXTURE0)
        if (!tex.bindTexture(dc)) return

        prog.loadTexCoordMatrix(texCoordMatrix)
        prog.loadColor(color)
        prog.loadOpacity(opacity)
        prog.loadFadeMargin(fadeMargin)

        for (idx in 0 until dc.drawableTerrainCount) {
            val terrain = dc.getDrawableTerrain(idx)
            if (terrain.offset != offset) continue
            if (sector.isEmpty || !sector.intersectsOrNextTo(terrain.sector)) continue

            // Bind the terrain's vertex position attribute. This drawable doesn't use the
            // terrain's per-vertex texCoord because the source UV is derived per-fragment
            // from the world position via `imageProjection`.
            if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue

            // Per-tile matrices: both mvpMatrix (for clip space) and imageProjectionLocal
            // (for image-space UV) take terrain-local positions to their respective
            // outputs. Pre-multiplying by the tile's vertex-origin translation here keeps
            // the shader-side math in float-precise small magnitudes.
            val tileOrigin = terrain.vertexOrigin
            mvpMatrix.copy(dc.modelviewProjection)
            mvpMatrix.multiplyByTranslation(tileOrigin.x, tileOrigin.y, tileOrigin.z)
            prog.loadModelviewProjection(mvpMatrix)

            imageProjectionLocal.copy(imageProjection)
            imageProjectionLocal.multiplyByTranslation(tileOrigin.x, tileOrigin.y, tileOrigin.z)
            prog.loadImageProjectionLocal(imageProjectionLocal)

            terrain.drawTriangles(dc)
        }

        // Avoid leaving the source texture bound on unit 0 (would cause a feedback loop
        // if the next surface drawable's render-to-texture stage tried to read it).
        dc.defaultTexture.bindTexture(dc)
    }
}
