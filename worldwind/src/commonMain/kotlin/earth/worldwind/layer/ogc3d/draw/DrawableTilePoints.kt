package earth.worldwind.layer.ogc3d.draw

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShadow
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.layer.shadow.applyShadowReceiverUniforms
import earth.worldwind.layer.sightline.applySightlineReceiverUniforms
import earth.worldwind.layer.ogc3d.content.PointCloudContent
import earth.worldwind.layer.ogc3d.program.Ogc3dTilesPointsProgram
import earth.worldwind.render.Color
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_POINTS
import kotlin.jvm.JvmStatic

/**
 * Color-pass drawable for one 3D Tiles point cloud tile. Holds a reference to the
 * pre-uploaded [PointCloudContent], the tile-to-world transform, and per-frame state for
 * the shadow + sightline receiver paths. Pool-recycled.
 *
 * Casts cascade shadows as 1-pixel point splats: the existing moments depth program is
 * triangle-oriented but happily rasterises `GL_POINTS`, with `gl_PointSize` defaulting to 1.
 * Combined with the cascade moments separable-blur pass, dense LiDAR clouds produce realistic
 * ground-halo shadows; sparse clouds look slightly stippled. A follow-up may add a
 * dedicated points-moments program that writes a size-attenuated `gl_PointSize` matching
 * the colour pass's per-splat disc.
 */
open class DrawableTilePoints protected constructor() : Drawable, ShadowCaster {

    var content: PointCloudContent? = null

    /** Interleaved VBO resolved fresh from the RR cache at enqueue time. Matches the
     *  drawable-enqueue resolution pattern used by [DrawableTileMesh] / Path / Polygon / OSM
     *  Buildings — keeping the ref on the drawable instead of on [PointCloudContent] means
     *  a cache eviction between frames surfaces as a `null` slot here (skipping the draw)
     *  rather than a stale [BufferObject.id] bind on a content-cached ref. */
    var vertexBuffer: BufferObject? = null

    val tileToWorld = Matrix4()

    var shadowMode: ShadowMode = ShadowMode.ENABLED

    var program: Ogc3dTilesPointsProgram? = null

    /** Set from `Tile3d.isFallback` at enqueue. Fallback tiles render only where stencil
     *  != [stencilId] so they fill gaps without overlaying finer descendants. */
    var isFallback: Boolean = false

    /** Outermost-fallback subtree id (0 = no fallback ancestor → stencil bypassed). */
    var stencilId: Int = 0

    /** Per-tile base point size in pixels at 1 m eye depth. Caller sets at enqueue time. */
    var basePointSize: Float = 1f

    /** Focal length in pixels = viewportHeight / (2 * tan(fov / 2)). Caller sets at enqueue
     *  time from `rc.viewport.height` + `rc.camera.fieldOfView`. */
    var focalLengthPixels: Float = 1f

    val pickColor = Color()

    private val worldBoundingCenter = Vec3()
    private var worldBoundingRadius = 0.0

    /** Per-cascade cull test reads these. Suppressed (returns null) when the layer disabled
     *  casting via [ShadowMode.castsShadows] = false so the depth pass doesn't even iterate. */
    override val shadowCasterCenter: Vec3?
        get() = if (shadowMode.castsShadows && worldBoundingRadius > 0.0) worldBoundingCenter else null
    override val shadowCasterRadius: Double
        get() = if (shadowMode.castsShadows) worldBoundingRadius else 0.0

    private var pool: Pool<DrawableTilePoints>? = null

    private val mvpMatrix = Matrix4()

    companion object {
        val KEY = DrawableTilePoints::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableTilePoints>): DrawableTilePoints {
            val instance = pool.acquire() ?: DrawableTilePoints()
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
        vertexBuffer = null
        program = null
        tileToWorld.setToIdentity()
        worldBoundingCenter.set(0.0, 0.0, 0.0)
        worldBoundingRadius = 0.0
        shadowMode = ShadowMode.ENABLED
        basePointSize = 1f
        focalLengthPixels = 1f
        pickColor.set(0f, 0f, 0f, 1f)
        isFallback = false
        stencilId = 0
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        val content = this.content ?: return
        val vbo = this.vertexBuffer ?: return
        val pointCount = content.pointCount
        if (pointCount <= 0) return
        val program = this.program ?: return
        if (!program.useProgram(dc)) return
        if (!vbo.bindBuffer(dc)) return

        dc.applyShadowReceiverUniforms(program, shadowMode.receivesShadows)
        dc.applySightlineReceiverUniforms(program)

        program.loadModelMatrix(tileToWorld)
        mvpMatrix.copy(dc.modelviewProjection).multiplyByMatrix(tileToWorld)
        program.loadModelviewProjection(mvpMatrix)
        program.loadBasePointSize(basePointSize)
        program.loadFocalLengthPixels(focalLengthPixels)
        program.loadOpacity(1f)
        program.enablePickMode(dc.isPickMode)
        if (dc.isPickMode) program.loadPickColor(pickColor)

        // attrib 0: position (vec3) at offset 0
        dc.gl.enableVertexAttribArray(0)
        dc.gl.vertexAttribPointer(0, 3, GL_FLOAT, false, PointCloudContent.VERTEX_STRIDE, PointCloudContent.POSITION_OFFSET)
        // attrib 1: color (vec4) at offset 12
        dc.gl.enableVertexAttribArray(1)
        dc.gl.vertexAttribPointer(1, 4, GL_FLOAT, false, PointCloudContent.VERTEX_STRIDE, PointCloudContent.COLOR_OFFSET)

        // Same stencil as mesh: writes GROUND_COVERED_BIT at each point pixel so terrain
        // can't occlude points from DEM-error hills. Bits 0..6 hold the skip-LoD subtree id.
        applyTileStencilState(dc, isFallback, stencilId)

        try {
            dc.gl.drawArrays(GL_POINTS, 0, pointCount)
        } finally {
            dc.gl.disableVertexAttribArray(1)
            clearTileStencilState(dc)
        }
    }

    /**
     * Cascade depth-pass dispatch. Binds the same VBO as the colour pass, points attribute 0
     * at the interleaved position field, and rasterises `GL_POINTS` into the moments
     * framebuffer. The depth program writes perpendicular-depth moments per fragment, same as
     * the triangle path — the only difference is each occluder is one pixel rather than a
     * filled triangle. Cascade separable-blur smears those impulses into round soft splats on
     * the ground, giving believable point-cloud shadows without a dedicated point-sprite
     * moments shader.
     *
     * No-op when `shadowMode.castsShadows` is false or the VBO hasn't been uploaded yet.
     * Doesn't touch attribute 1 (colour) — moments shader only reads position.
     */
    override fun drawShadowDepth(dc: DrawContext, shadow: DrawableShadow) {
        if (!shadowMode.castsShadows) return
        val content = this.content ?: return
        val vbo = this.vertexBuffer ?: return
        val pointCount = content.pointCount
        if (pointCount <= 0) return
        if (!vbo.bindBuffer(dc)) return
        shadow.loadCasterMatrix(tileToWorld)
        dc.gl.enableVertexAttribArray(0)
        dc.gl.vertexAttribPointer(
            0, 3, GL_FLOAT, false,
            PointCloudContent.VERTEX_STRIDE, PointCloudContent.POSITION_OFFSET,
        )
        dc.gl.drawArrays(GL_POINTS, 0, pointCount)
    }
}
