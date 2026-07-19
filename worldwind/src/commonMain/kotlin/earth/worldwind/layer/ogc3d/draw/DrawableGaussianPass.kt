package earth.worldwind.layer.ogc3d.draw

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.draw.DrawableShadow
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.ogc3d.program.GaussianCompositeProgram
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.render.Texture
import earth.worldwind.render.program.SceneDepthProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.GL_COLOR_ATTACHMENT0
import earth.worldwind.util.kgl.GL_COLOR_BUFFER_BIT
import earth.worldwind.util.kgl.GL_DEPTH_BUFFER_BIT
import earth.worldwind.util.kgl.GL_DEPTH_TEST
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TRIANGLE_STRIP
import kotlin.jvm.JvmStatic
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reduced-resolution offscreen pass for the frame's Gaussian-splat tiles. Splat rendering is
 * blended-fill-rate bound — the same splats at half resolution cost ~4x less fragment work —
 * so all [DrawableTileGaussian]s of one layer render back-to-front into a `renderScale`-sized
 * offscreen target and composite over the scene as a single premultiplied full-screen quad.
 *
 * Terrain is rasterized depth-only into the pass target first (camera MVP, same LEQUAL test
 * as the main pass) so opaque ground still occludes splats exactly like the direct path. The
 * composite itself is a screen-space overlay: per-pixel depth interleaving with other
 * transparent SHAPE drawables collapses to this drawable's single queue depth.
 *
 * Falls back to direct full-resolution child draws when the platform lacks sized texture
 * formats (no depth-texture attachment) or a program is missing.
 */
open class DrawableGaussianPass protected constructor() : Drawable, ShadowCaster {

    /** Depth-only program for the terrain occlusion prepass. Deliberately NOT the
     *  shadow/sightline [earth.worldwind.render.program.DirectionalDepthProgram]: its caster
     *  pancaking is wrong under the camera's perspective projection (see [SceneDepthProgram]). */
    var depthProgram: SceneDepthProgram? = null
    var compositeProgram: GaussianCompositeProgram? = null

    /** Stable identity keying this pass's framebuffer ping-pong pair (the owning layer);
     *  distinct owners get distinct pairs so multi-layer frames keep the double-buffering. */
    var owner: Any? = null

    /** Offscreen target scale in (0, 1); the pass renders at `viewport * renderScale`. */
    var renderScale = 0.5f

    /** Nearest child depth — the queue key for the composite among other shape drawables. */
    var nearestChildDepthSq = Double.MAX_VALUE
        private set

    private val children = mutableListOf<DrawableTileGaussian>()
    private var pool: Pool<DrawableGaussianPass>? = null
    private val matrix = Matrix4()

    companion object {
        val KEY = DrawableGaussianPass::class

        @JvmStatic
        fun obtain(pool: Pool<DrawableGaussianPass>): DrawableGaussianPass {
            val instance = pool.acquire() ?: DrawableGaussianPass()
            instance.pool = pool
            return instance
        }
    }

    /** Collects a splat tile; children are recycled by this pass, not the drawable queue. */
    fun addChild(child: DrawableTileGaussian, depthSq: Double) {
        child.sortKey = depthSq
        if (depthSq < nearestChildDepthSq) nearestChildDepthSq = depthSq
        children.add(child)
    }

    override fun recycle() {
        for (child in children) child.recycle()
        children.clear()
        nearestChildDepthSq = Double.MAX_VALUE
        depthProgram = null
        compositeProgram = null
        owner = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        if (children.isEmpty()) return
        // Cross-tile blending: back-to-front, matching the direct path's queue order.
        children.sortByDescending { it.sortKey }

        val viewWidth = dc.viewport.width
        val viewHeight = dc.viewport.height
        val passWidth = max(1, (viewWidth * renderScale).roundToInt())
        val passHeight = max(1, (viewHeight * renderScale).roundToInt())
        val depthProgram = depthProgram
        val compositeProgram = compositeProgram
        val framebuffer =
            if (dc.gl.supportsSizedTextureFormats && depthProgram != null && compositeProgram != null &&
                viewWidth > 0 && viewHeight > 0
            ) dc.ensureGaussianPassFramebuffer(owner ?: this, passWidth, passHeight) else null
        if (framebuffer == null) {
            // Fallback: legacy direct full-resolution draws.
            for (child in children) child.draw(dc)
            return
        }

        val colorTexture = framebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0)
        val previousFramebuffer = dc.currentFramebuffer
        if (!framebuffer.bindFramebuffer(dc)) {
            for (child in children) child.draw(dc)
            return
        }
        try {
            dc.gl.viewport(0, 0, passWidth, passHeight)
            // Engine-wide clear color is transparent black — exactly the empty pass state.
            dc.gl.clear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            drawTerrainDepth(dc, depthProgram!!)
            // Splat sizing is in pixels of the current viewport — rescale the projection focal.
            // Restore after each draw: the same frame can be drawn again on the next GL tick.
            val focalScale = passHeight.toFloat() / viewHeight
            for (child in children) {
                val focalLengthPixels = child.focalLengthPixels
                child.focalLengthPixels = focalLengthPixels * focalScale
                child.draw(dc)
                child.focalLengthPixels = focalLengthPixels
            }
        } finally {
            dc.bindFramebuffer(previousFramebuffer)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, viewWidth, viewHeight)
        }
        composite(dc, compositeProgram!!, colorTexture, passWidth, passHeight)
    }

    /** Terrain depth into the pass target so ground occludes splats like the direct path. */
    private fun drawTerrainDepth(dc: DrawContext, depthProgram: SceneDepthProgram) {
        if (!depthProgram.useProgram(dc)) return
        dc.gl.colorMask(false, false, false, false)
        try {
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                val terrainOrigin = terrain.vertexOrigin
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                matrix.copy(dc.modelviewProjection)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                depthProgram.loadModelviewProjection(matrix)
                terrain.drawTriangles(dc)
            }
        } finally {
            dc.gl.colorMask(true, true, true, true)
        }
    }

    /** Full-screen premultiplied over-composite of the rendered pass region. */
    private fun composite(
        dc: DrawContext, program: GaussianCompositeProgram, colorTexture: Texture, passWidth: Int, passHeight: Int,
    ) {
        if (!program.useProgram(dc)) return
        dc.activeTextureUnit(GL_TEXTURE0)
        if (!colorTexture.bindTexture(dc)) return
        if (!dc.unitSquareBuffer.bindBuffer(dc)) return
        // Half-texel inset: the grow-only texture can be larger than the pass region, and
        // sampling exactly at the region boundary would bilinearly blend with the cleared
        // texel just outside it — a dimmed band along the screen's top/right edge. Mapping
        // the quad to the last rendered texel CENTER keeps LINEAR filtering inside the
        // region (a sub-pixel squeeze, imperceptible at any pass size).
        program.loadTexScale(
            (passWidth - 0.5f) / colorTexture.width, (passHeight - 0.5f) / colorTexture.height
        )
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 2, GL_FLOAT, false, 0, 0)
        // Screen-space overlay: the global premultiplied blend applies; depth must not reject.
        dc.gl.disable(GL_DEPTH_TEST)
        try {
            dc.gl.drawArrays(GL_TRIANGLE_STRIP, 0, 4)
        } finally {
            dc.gl.enable(GL_DEPTH_TEST)
        }
    }

    /** Cascade depth pass: delegate to children — each checks its own shadow mode. */
    override fun drawShadowDepth(dc: DrawContext, shadow: DrawableShadow) {
        for (child in children) child.drawShadowDepth(dc, shadow)
    }
}
