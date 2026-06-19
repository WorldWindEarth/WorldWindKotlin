package earth.worldwind.layer.ogc3d.draw

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShadow
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.ogc3d.content.GaussianContent
import earth.worldwind.layer.ogc3d.content.STRIDE_ATTRIBS
import earth.worldwind.layer.ogc3d.program.Ogc3dTilesGaussianProgram
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.render.Color
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_POINTS
import earth.worldwind.util.kgl.GL_SHORT
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import earth.worldwind.util.kgl.GL_UNSIGNED_INT
import kotlin.jvm.JvmStatic

/**
 * Colour-pass drawable for one 3D Tiles Gaussian-splat tile. Holds references to the tile's
 * per-attribute GPU buffers (centres / scales / rotations / rgba) + a static back-to-front
 * index buffer computed once at upload time, and dispatches a single
 * `glDrawElements(GL_POINTS)` per tile.
 *
 * The static sort is computed against the tile bounding sphere centre, not the live camera —
 * good enough for tiles roughly viewed from a consistent direction, fast to upload (no
 * per-frame mutation), but produces some visible overlap artifacts when the camera orbits
 * around the tile.
 *
 * Implements [ShadowCaster] like [DrawableTilePoints]: emits one-pixel `GL_POINTS` into the
 * cascade moments framebuffer when `shadowMode.castsShadows`. The colour pass does not
 * apply incoming shadows — splats are usually self-shadowed already, the bookkeeping cost
 * outweighs the fidelity gain.
 */
open class DrawableTileGaussian protected constructor() : Drawable, ShadowCaster {

    var content: GaussianContent? = null

    /** Live GPU buffer references resolved fresh from the RR cache at enqueue time. Matches the
     *  drawable-enqueue resolution pattern used by [DrawableTileMesh] / Path / Polygon / OSM
     *  Buildings — keeping the refs on the drawable instead of on [GaussianContent] means a
     *  cache eviction between frames surfaces as a `null` slot here (skipping the draw) rather
     *  than a stale [BufferObject.id] bind on a content-cached ref. */
    var centers: BufferObject? = null
    var attribs: BufferObject? = null
    var sortIndices: BufferObject? = null

    val tileToWorld = Matrix4()

    var shadowMode: ShadowMode = ShadowMode.ENABLED

    var program: Ogc3dTilesGaussianProgram? = null

    /** Focal length in pixels = `viewportHeight / (2 * tan(fov / 2))`. Caller sets at enqueue
     *  time from `rc.viewport.height` + `rc.camera.fieldOfView`. */
    var focalLengthPixels: Float = 1f

    /** Global splat-size multiplier piped into the shader's per-splat sizing. Defaults to 1. */
    var splatSizeMultiplier: Float = 1f

    /** See [earth.worldwind.layer.ogc3d.Ogc3dTilesLayer.gaussianMinAlpha]. */
    var minAlpha: Float = 0.01f

    /** Unique pick-ID RGBA emitted by the pick-mode fragment shader on the splat core. Set
     *  on pick frames in `enqueueGaussianDrawable` alongside the matching `offerPickedObject`. */
    val pickColor = Color()

    private val worldBoundingCenter = Vec3()
    private var worldBoundingRadius = 0.0

    /** `tileToWorld * diag(halfRange) | center` — bakes the position dequant into the model
     *  matrix for the shadow caster path, which uses a generic depth shader that doesn't
     *  know about the int16 normalized position encoding. The colour path doesn't use this:
     *  the Gaussian VS dequantizes via uniforms (which keeps `mvMatrix`'s 3×3 a pure rotation
     *  for the covariance projection). */
    private val shadowCasterMatrix = Matrix4()

    override val shadowCasterCenter: Vec3?
        get() = if (shadowMode.castsShadows && worldBoundingRadius > 0.0) worldBoundingCenter else null
    override val shadowCasterRadius: Double
        get() = if (shadowMode.castsShadows) worldBoundingRadius else 0.0

    private var pool: Pool<DrawableTileGaussian>? = null

    private val mvMatrix = Matrix4()

    companion object {
        val KEY = DrawableTileGaussian::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableTileGaussian>): DrawableTileGaussian {
            val instance = pool.acquire() ?: DrawableTileGaussian()
            instance.pool = pool
            return instance
        }
    }

    fun setWorldBounds(center: Vec3, radius: Double) {
        worldBoundingCenter.copy(center)
        worldBoundingRadius = radius
    }

    override fun recycle() {
        content = null
        centers = null
        attribs = null
        sortIndices = null
        program = null
        tileToWorld.setToIdentity()
        worldBoundingCenter.set(0.0, 0.0, 0.0)
        worldBoundingRadius = 0.0
        shadowMode = ShadowMode.ENABLED
        focalLengthPixels = 1f
        splatSizeMultiplier = 1f
        minAlpha = 0.01f
        pickColor.set(0f, 0f, 0f, 1f)
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val content = content ?: return
        val centers = centers ?: return
        val attribs = attribs ?: return
        val sortIndices = sortIndices ?: return
        val splatCount = content.splatCount
        if (splatCount <= 0) return
        val program = program ?: return
        if (!program.useProgram(dc)) return

        val pickMode = dc.isPickMode
        program.loadPickMode(pickMode)
        if (pickMode) program.loadPickColor(pickColor)
        program.loadFocalLengthPixels(focalLengthPixels)
        program.loadSplatSizeMultiplier(splatSizeMultiplier)
        program.loadMinAlpha(minAlpha)
        program.loadPositionDequant(
            content.qPosCenterX, content.qPosCenterY, content.qPosCenterZ,
            content.qPosHalfRangeX, content.qPosHalfRangeY, content.qPosHalfRangeZ,
        )
        // Anisotropic splat needs modelview + projection separately: covariance must be
        // projected through the perspective Jacobian computed from eye-space depth.
        mvMatrix.copy(dc.modelview).multiplyByMatrix(tileToWorld)
        program.loadModelview(mvMatrix)
        program.loadProjection(dc.projection)

        // Vertex inputs: position (0) from the int16-normalized centres VBO; scale (1),
        // rgba (2), rotation (3) from one interleaved attribs VBO at stride [STRIDE_ATTRIBS].
        // Bind everything first; only enable the attribs once we know nothing will return
        // early — an attrib left enabled across an early-return reads from whatever VBO the
        // next drawable happens to bind.
        if (!centers.bindBuffer(dc)) return
        dc.gl.vertexAttribPointer(0, 3, GL_SHORT, true, 0, 0)
        if (!attribs.bindBuffer(dc)) return
        dc.gl.vertexAttribPointer(1, 3, GL_FLOAT,         false, STRIDE_ATTRIBS, 0)
        dc.gl.vertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true,  STRIDE_ATTRIBS, 12)
        dc.gl.vertexAttribPointer(3, 4, GL_FLOAT,         false, STRIDE_ATTRIBS, 16)
        if (!sortIndices.bindBuffer(dc)) return

        dc.gl.enableVertexAttribArray(1)
        dc.gl.enableVertexAttribArray(2)
        dc.gl.enableVertexAttribArray(3)

        // Color pass: depth WRITE off — a far-then-near splat at the same pixel would have
        // its near contribution depth-rejected, collapsing back-to-front accumulation to
        // black voids. Depth READ stays on so opaque terrain still occludes splats.
        // Pick pass: depth WRITE on; fragment alpha-discards the Gaussian fringe so the pick
        // framebuffer's depth ends up on the splat core (the visible surface) instead of the
        // terrain behind it. Without this, depth-readback unproject lands on the ground.
        if (!pickMode) dc.gl.depthMask(false)

        try {
            dc.gl.drawElements(GL_POINTS, splatCount, GL_UNSIGNED_INT, 0)
        } finally {
            if (!pickMode) dc.gl.depthMask(true)
            dc.gl.disableVertexAttribArray(1)
            dc.gl.disableVertexAttribArray(2)
            dc.gl.disableVertexAttribArray(3)
        }
    }

    /**
     * Cascade depth-pass dispatch. Mirrors [DrawableTilePoints.drawShadowDepth]: binds the
     * centers VBO + a single position attribute, issues `GL_POINTS` into the cascade moments
     * framebuffer. One-pixel splats per splat → cascade separable blur softens them into
     * believable ground halos.
     */
    override fun drawShadowDepth(dc: DrawContext, shadow: DrawableShadow) {
        if (!shadowMode.castsShadows) return
        val content = content ?: return
        val centers = centers ?: return
        val splatCount = content.splatCount
        if (splatCount <= 0) return
        if (!centers.bindBuffer(dc)) return
        // Bake the position dequant into the model matrix so the cascade depth shader (which
        // doesn't know about our int16 normalized encoding) sees world-space positions.
        // composed = tileToWorld * diag(halfRange) | center.
        shadowCasterMatrix.copy(tileToWorld).multiplyByMatrix(
            content.qPosHalfRangeX.toDouble(), 0.0, 0.0, content.qPosCenterX.toDouble(),
            0.0, content.qPosHalfRangeY.toDouble(), 0.0, content.qPosCenterY.toDouble(),
            0.0, 0.0, content.qPosHalfRangeZ.toDouble(), content.qPosCenterZ.toDouble(),
            0.0, 0.0, 0.0, 1.0,
        )
        shadow.loadCasterMatrix(shadowCasterMatrix)
        dc.gl.enableVertexAttribArray(0)
        dc.gl.vertexAttribPointer(0, 3, GL_SHORT, true, 0, 0)
        dc.gl.drawArrays(GL_POINTS, 0, splatCount)
    }
}
