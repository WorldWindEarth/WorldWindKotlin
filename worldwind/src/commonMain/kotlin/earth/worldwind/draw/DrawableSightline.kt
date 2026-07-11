package earth.worldwind.draw

import earth.worldwind.geom.Angle.Companion.POS360
import earth.worldwind.geom.Angle.Companion.POS90
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowCaster
import earth.worldwind.layer.sightline.SightlineState
import earth.worldwind.render.Color
import earth.worldwind.render.program.DirectionalDepthProgram
import earth.worldwind.render.program.SightlineProgram
import earth.worldwind.util.Pool
import earth.worldwind.util.kgl.*
import kotlin.jvm.JvmStatic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Renders the active sightline: a depth pass that rasterises the scene into the shared
 * depth-only cube map ([DrawContext.sightlineDepthCubeTexture]) from the sightline's point of
 * view, and an overlay pass that tints terrain visible/occluded by PCF-comparing each fragment
 * against that cube (see [earth.worldwind.layer.sightline.SightlineReceiverGlsl]).
 *
 * The cube always covers the full sphere; the sightline's heading/field-of-view wedge is a
 * receiver-side mask (360 degrees masks nothing). All six faces are rendered — photogrammetry
 * receivers above the observer (city-canyon facades) are tinted correctly, unlike the
 * terrain-era assumption that nothing is visible looking up.
 */
open class DrawableSightline protected constructor() : Drawable {
    /** Horizontal wedge angle about [heading]; 360 degrees covers the full sphere. */
    var fieldOfView = POS360
    /** Wedge heading clockwise from North. Irrelevant at 360 degrees. */
    var heading = ZERO
    /** Local (Z = up) frame of the sightline position; cube faces render in this frame. */
    val centerTransform = Matrix4()
    var range = 0f
    val visibleColor = Color(0f, 0f, 0f, 0f)
    val occludedColor = Color(0f, 0f, 0f, 0f)

    /**
     * Selects which phases this drawable runs. The sightline shapes enqueue two drawables per
     * frame: [RenderMode.DEPTH_ONLY] in BACKGROUND so the depth cube and [DrawContext.sightlineState]
     * are populated before any embedded-receiver fragment shader runs (3D tiles need this), and
     * [RenderMode.OVERLAY_ONLY] in SURFACE so terrain gets tinted after opaque receivers have
     * rendered. [RenderMode.DEPTH_AND_OVERLAY] preserves the legacy single-drawable path for
     * callers that don't split the work.
     */
    var renderMode: RenderMode = RenderMode.DEPTH_AND_OVERLAY

    enum class RenderMode { DEPTH_AND_OVERLAY, DEPTH_ONLY, OVERLAY_ONLY }

    /** Identity of the sightline shape this drawable renders; twins of one shape share it. */
    var sourceKey: Any? = null
    /** Terrain overlay receiver shader; shares its GLSL with the embedded-receiver splice. */
    var program: SightlineProgram? = null
    /** Depth-pass shader — the same trivial depth-only program the sun cascades use. */
    var depthProgram: DirectionalDepthProgram? = null
    private var pool: Pool<DrawableSightline>? = null
    private val sightlineView = Matrix4()
    private val faceProjectionView = Matrix4()
    private val matrix = Matrix4()
    private val cubeMapProjection = Matrix4()
    private val overlayState = SightlineState()
    private var terrainCoverageSkip = BooleanArray(0)

    /**
     * Per-face eye-to-world rotation for the sightline depth cube. Built to match the
     * OpenGL canonical cube-map face convention so the depth-pass output texels align with
     * the s,t coordinates that `samplerCube` reads back: e.g. POS_X uses `up = -Y, right = -Z`,
     * which makes a fragment at world direction `(1, 0, 1)` rasterise to clip `(-1, 0)` =
     * texel `(0, 0.5)` - the same texel `samplerCube` looks up for that direction. Without
     * this match the cube-map sample returns depth stored for a different direction and the
     * receiver computes nonsense. Each matrix is the rotation that, concatenated with
     * `centerTransform` and inverted, gives the per-face view matrix.
     */
    private val cubeMapFace = arrayOf(
        // POS_X: eye_x=-Z, eye_y=-Y, eye_z=-X (eye_-z=+X). Looking at +X.
        Matrix4().set(
            0.0,  0.0, -1.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
           -1.0,  0.0,  0.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // NEG_X: eye_x=+Z, eye_y=-Y, eye_z=+X. Looking at -X.
        Matrix4().set(
            0.0,  0.0,  1.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
            1.0,  0.0,  0.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // POS_Y: eye_x=+X, eye_y=+Z, eye_z=-Y. Looking at +Y.
        Matrix4().set(
            1.0,  0.0,  0.0, 0.0,
            0.0,  0.0, -1.0, 0.0,
            0.0,  1.0,  0.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // NEG_Y: eye_x=+X, eye_y=-Z, eye_z=+Y. Looking at -Y.
        Matrix4().set(
            1.0,  0.0,  0.0, 0.0,
            0.0,  0.0,  1.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // POS_Z: eye_x=+X, eye_y=-Y, eye_z=-Z. Looking at +Z (up).
        Matrix4().set(
            1.0,  0.0,  0.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
            0.0,  0.0, -1.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
        // NEG_Z: eye_x=-X, eye_y=-Y, eye_z=+Z. Looking at -Z (down).
        Matrix4().set(
           -1.0,  0.0,  0.0, 0.0,
            0.0, -1.0,  0.0, 0.0,
            0.0,  0.0,  1.0, 0.0,
            0.0,  0.0,  0.0, 1.0,
        ),
    )

    /** GL cube-map face attachment target parallel to [cubeMapFace]. */
    private val cubeMapFaceTarget = intArrayOf(
        GL_TEXTURE_CUBE_MAP_POSITIVE_X,
        GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
        GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
        GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
        GL_TEXTURE_CUBE_MAP_POSITIVE_Z,
        GL_TEXTURE_CUBE_MAP_NEGATIVE_Z,
    )

    companion object {
        val KEY = DrawableSightline::class
        // Slope-scaled caster bias, same rationale as the sun-shadow depth pass: grazing
        // triangles get proportionally more bias than a receiver-side constant could give.
        private const val POLYGON_OFFSET_FACTOR = 1.5f
        private const val POLYGON_OFFSET_UNITS = 4f

        @JvmStatic
        fun obtain(pool: Pool<DrawableSightline>): DrawableSightline {
            val instance = pool.acquire() ?: DrawableSightline()
            instance.pool = pool
            return instance
        }
    }

    override fun recycle() {
        sourceKey = null
        program = null
        depthProgram = null
        pool?.release(this)
        pool = null
    }

    override fun draw(dc: DrawContext) {
        // Depth textures need sized formats; without them the sightline stays off entirely.
        if (!dc.gl.supportsSizedTextureFormats) return
        // Faces are always 90 degrees; the directional field of view is a receiver-side mask.
        cubeMapProjection.setToPerspectiveProjection(1, 1, POS90, 1.0, max(range.toDouble(), 1.001))
        if (renderMode != RenderMode.OVERLAY_ONLY) {
            if (!drawSceneDepth(dc)) return
            // Publish for embedded receivers; with several sightlines the last one wins there.
            dc.sightlineState = SightlineState().also(::fillState)
        }
        if (renderMode != RenderMode.DEPTH_ONLY) {
            // Overlay renders its OWN configuration (dc.sightlineState may be another
            // sightline's); re-render the cube only if another sightline overwrote it.
            if (dc.sightlineCubeOwner !== sourceKey && !drawSceneDepth(dc)) return
            drawSceneOcclusion(dc, overlayState.also(::fillState))
        }
    }

    /** Copies this drawable's sightline configuration into [state]. */
    private fun fillState(state: SightlineState) {
        state.range = range
        state.visibleColor.copy(visibleColor)
        state.occludedColor.copy(occludedColor)
        state.sightlineView.copy(centerTransform).invertOrthonormal()
        // cos(180) = -1 disables the azimuth wedge; the elevation cap saturates at 90 degrees
        // so wide sightlines cover the full sphere.
        val halfFov = fieldOfView.inRadians / 2.0
        state.cosHalfFov = cos(halfFov).toFloat()
        state.sinHalfFov = sin(min(halfFov, PI / 2.0)).toFloat()
        state.forwardX = sin(heading.inRadians).toFloat()
        state.forwardY = cos(heading.inRadians).toFloat()
    }

    /**
     * Depth pass: renders the visible terrain (and 3D occluder shapes) into all six faces of
     * the sightline depth cube. Each iteration attaches the matching cube face to the shared
     * depth-only FBO's `GL_DEPTH_ATTACHMENT`, clears it, and rasterises with a per-face view
     * matrix through the 90-degree projection.
     */
    protected open fun drawSceneDepth(dc: DrawContext): Boolean {
        val depthProgram = depthProgram ?: return false
        if (!depthProgram.useProgram(dc)) return false

        val framebuffer = dc.sightlineCubeFramebuffer
        val cubeTexture = dc.sightlineDepthCubeTexture
        val size = cubeTexture.width
        val previousFramebuffer = dc.currentFramebuffer
        if (!framebuffer.bindFramebuffer(dc)) return false

        try {
            // Rendering into a texture still bound for sampling on unit 5 is undefined.
            dc.activeTextureUnit(GL_TEXTURE5)
            dc.defaultCubeTexture.bindTexture(dc)
            dc.activeTextureUnit(GL_TEXTURE0)

            dc.gl.viewport(0, 0, size, size)
            // Assert the depth-write state the pass depends on; colour writes stay masked.
            dc.gl.enable(GL_DEPTH_TEST)
            dc.gl.depthFunc(GL_LEQUAL)
            dc.gl.depthMask(true)
            dc.gl.colorMask(false, false, false, false)
            dc.gl.enable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(POLYGON_OFFSET_FACTOR, POLYGON_OFFSET_UNITS)
            computeTerrainCoverageSkips(dc)

            for (i in cubeMapFace.indices) {
                framebuffer.attachTexture(dc, cubeTexture, GL_DEPTH_ATTACHMENT, cubeMapFaceTarget[i])
                dc.gl.clear(GL_DEPTH_BUFFER_BIT)

                // Full per-face MVP composed in double so ECEF translations cancel.
                sightlineView.copy(centerTransform)
                sightlineView.multiplyByMatrix(cubeMapFace[i])
                sightlineView.invertOrthonormal()
                faceProjectionView.copy(cubeMapProjection)
                faceProjectionView.multiplyByMatrix(sightlineView)

                // No back-face culling: terrain winding is unreliable from a sightline POV.
                dc.gl.disable(GL_CULL_FACE)
                for (idx in 0 until dc.drawableTerrainCount) {
                    if (terrainCoverageSkip[idx]) continue
                    val terrain = dc.getDrawableTerrain(idx)
                    val terrainOrigin = terrain.vertexOrigin
                    if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue
                    matrix.copy(faceProjectionView)
                    matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                    depthProgram.loadModelviewProjection(matrix)
                    terrain.drawTriangles(dc)
                }
                dc.gl.enable(GL_CULL_FACE)

                drawShapesDepth(dc)
            }
        } finally {
            dc.bindFramebuffer(previousFramebuffer)
            dc.gl.viewport(dc.viewport.x, dc.viewport.y, dc.viewport.width, dc.viewport.height)
            dc.gl.colorMask(true, true, true, true)
            dc.gl.disable(GL_POLYGON_OFFSET_FILL)
            dc.gl.polygonOffset(0f, 0f)
        }
        dc.sightlineCubeOwner = sourceKey
        return true
    }

    /**
     * True when the world-space sphere intersects the observer's range sphere - only then
     * can it occlude a fragment within the sightline's reach (an occluder of a point at
     * distance <= range lies between observer and point, so inside the range sphere).
     * Radius <= 0 opts out of culling. The floor matches the cube projection's minimum far.
     */
    protected fun intersectsRange(center: Vec3, radius: Double): Boolean {
        if (radius <= 0.0) return true
        val m = centerTransform.m
        val dx = center.x - m[3]
        val dy = center.y - m[7]
        val dz = center.z - m[11]
        val reach = max(range.toDouble(), 1.001) + radius
        return dx * dx + dy * dy + dz * dz <= reach * reach
    }

    /**
     * Precomputes, once per depth pass, which terrain tiles to skip as occluders: those
     * OUTSIDE the observer's range sphere (they can't occlude anything the sightline
     * reaches, and rasterizing them into six faces was the pass's dominant waste), plus
     * those intersecting any 3D-Tile mesh coverage region - the coarse terrain model can
     * sit above the photogrammetry streets and would otherwise occlude everything a
     * street-level observer sees, the same conflict (and cure) as the sun-shadow pass.
     */
    private fun computeTerrainCoverageSkips(dc: DrawContext) {
        val count = dc.drawableTerrainCount
        if (terrainCoverageSkip.size < count) terrainCoverageSkip = BooleanArray(count)
        val regions = dc.groundCoverageRegions
        for (idx in 0 until count) {
            val terrain = dc.getDrawableTerrain(idx)
            var skip = !intersectsRange(terrain.vertexOrigin, terrain.boundingSphereRadius)
            if (!skip && dc.hasGroundCoverageMask) {
                val sector = terrain.sector
                for (i in regions.indices) if (regions[i].intersects(sector)) { skip = true; break }
            }
            terrainCoverageSkip[idx] = skip
        }
    }

    /**
     * Iterates the non-terrain drawable queue and dispatches every [SightlineOccluder] to
     * cast its geometry into the sightline depth cube. The built-in [DrawableShape] /
     * [DrawableMesh] / [DrawableCollada] all implement the interface; custom drawables opt
     * in the same way. Surface decals, screen-space sprites, line drawables and lambdas
     * don't implement [SightlineOccluder] and are therefore skipped.
     */
    protected open fun drawShapesDepth(dc: DrawContext) {
        val queue = dc.drawableQueue ?: return
        for (i in 0 until queue.count) {
            val drawable = queue.getDrawable(i)
            if (drawable !is SightlineOccluder) continue
            // Range-cull via the shadow-caster bounds every built-in occluder also exposes;
            // unbounded occluders dispatch into every face - the safe default.
            if (drawable is ShadowCaster) {
                val center = drawable.shadowCasterCenter
                if (center != null && !intersectsRange(center, drawable.shadowCasterRadius)) continue
            }
            drawable.drawSightlineDepth(dc, this)
        }
    }

    /**
     * Composes the active face's matrices (`cubeMapProjection × faceView × modelMatrix`) and
     * loads the result into the depth program's mvpMatrix uniform. [SightlineOccluder]
     * implementations call this once before each draw call.
     */
    fun loadOccluderMatrix(modelMatrix: Matrix4) {
        val program = depthProgram ?: return
        matrix.copy(faceProjectionView)
        matrix.multiplyByMatrix(modelMatrix)
        program.loadModelviewProjection(matrix)
    }

    /**
     * Convenience for occluders whose vertices are stored relative to a translated origin in
     * world coordinates (the common case — terrain tiles, shape vertex origins, etc.).
     */
    fun loadOccluderTranslation(x: Double, y: Double, z: Double) {
        val program = depthProgram ?: return
        matrix.copy(faceProjectionView)
        matrix.multiplyByTranslation(x, y, z)
        program.loadModelviewProjection(matrix)
    }

    /**
     * Renders the filled-triangle primitives of a [DrawShapeState] into the sightline depth
     * cube. Shared between [DrawableShape] (interleaved attributes, passes its own
     * [DrawShapeState.vertexStride]) and [DrawableMesh] (positions packed tightly in their
     * own buffer, stride 0). Line-mode primitives within an otherwise filled state are
     * skipped, and the state's [DrawShapeState.enableCullFace] preference is respected.
     */
    fun drawShapeStateOccluder(dc: DrawContext, state: DrawShapeState, vertexStride: Int) {
        if (state.vertexBuffer?.bindBuffer(dc) != true) return
        if (state.elementBuffer?.bindBuffer(dc) != true) return
        loadOccluderTranslation(state.vertexOrigin.x, state.vertexOrigin.y, state.vertexOrigin.z)
        dc.gl.vertexAttribPointer(0 /*vertexPoint*/, 3, GL_FLOAT, false, vertexStride, 0)
        val cullFaceDisabled = !state.enableCullFace
        if (cullFaceDisabled) dc.gl.disable(GL_CULL_FACE)
        for (idx in 0 until state.primCount) {
            val prim = state.prims[idx]
            if (prim.mode == GL_TRIANGLES || prim.mode == GL_TRIANGLE_STRIP || prim.mode == GL_TRIANGLE_FAN) {
                dc.gl.drawElements(prim.mode, prim.count, prim.type, prim.offset)
            }
        }
        if (cullFaceDisabled) dc.gl.enable(GL_CULL_FACE)
    }

    /**
     * Overlay (receiver) pass: single full-terrain pass that PCF-samples the depth cube with
     * each fragment's sightline-local direction and blends the visible/occluded tint over the
     * scene. Runs after opaque content (SURFACE group); embedded receivers (3D tiles) apply
     * the identical GLSL inside their own fragment shaders instead.
     */
    protected open fun drawSceneOcclusion(dc: DrawContext, state: SightlineState) {
        val program = program ?: return
        if (!program.useProgram(dc)) return
        try {
            dc.activeTextureUnit(GL_TEXTURE5)
            if (!dc.sightlineDepthCubeTexture.bindTexture(dc)) return
            dc.activeTextureUnit(GL_TEXTURE0)
            // Per-frame scalars once; the world -> local matrix is reloaded per tile below.
            program.loadSightlineEnabled(state, state.sightlineView)
            for (idx in 0 until dc.drawableTerrainCount) {
                val terrain = dc.getDrawableTerrain(idx)
                val terrainOrigin = terrain.vertexOrigin
                if (!terrain.useVertexPointAttrib(dc, 0 /*vertexPoint*/)) continue

                matrix.copy(dc.modelviewProjection)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                program.loadModelviewProjection(matrix)

                // Terrain vertices are tile-origin-relative; compose in double per tile.
                matrix.copy(state.sightlineView)
                matrix.multiplyByTranslation(terrainOrigin.x, terrainOrigin.y, terrainOrigin.z)
                program.loadSightlineLocalMatrix(matrix)

                terrain.drawTriangles(dc)
            }
        } finally {
            // Benign cube on unit 5 so the next depth pass can attach faces without feedback.
            dc.activeTextureUnit(GL_TEXTURE5)
            dc.defaultCubeTexture.bindTexture(dc)
            dc.activeTextureUnit(GL_TEXTURE0)
        }
    }
}
