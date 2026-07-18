package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Sector
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.layer.shadow.ShadowReceiverUniforms
import earth.worldwind.layer.shadow.ShadowState
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.geom.Vec3
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Surface tile / surface texture program. Composites up to [TEXTURE_UNITS] imagery textures over a
 * terrain tile in a single geometry pass: the fragment shader transforms the tile-local texture
 * coordinate per attached texture (scale + translation), masks fragments outside each texture's
 * sub-rectangle and blends the stack front-to-back in-shader. Terrain geometry is therefore drawn
 * once per batch of textures instead of once per texture.
 *
 * A single GLSL source carries both the no-shadow (default) and the shadow-aware paths, gated by a
 * `#define SHADOWS_ENABLED` preprocessor symbol that [shadowsEnabled] toggles. The sentinel
 * subclass [SurfaceTextureProgramShadow] - selected by [get] when an
 * [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list - exists only to give the cache
 * a distinct [kotlin.reflect.KClass] key for the shadow-aware variant.
 */
open class SurfaceTextureProgram(
    protected val shadowsEnabled: Boolean = false,
) : AbstractShaderProgram(), ShadowReceiverProgram {

    companion object {
        /**
         * Texture units used for imagery compositing: unit 0 plus 5..7, within the ES2 minimum of
         * 8 units. Units 1..4 hold the frame-wide shadow cascade binds shared by every receiver
         * program and are never touched (see
         * [earth.worldwind.layer.shadow.applyShadowReceiverUniforms]); unit 5's cube-map binding
         * target is used by the sightline pipeline, which does not conflict with a 2D binding on
         * the same unit. A wider table was measured on Mali-G72 and changed nothing: composite
         * cost is proportional to overlapping-texture count, not pass count.
         */
        val TEXTURE_UNITS = intArrayOf(0, 5, 6, 7)

        /** Outward pad on batch coverage rectangles, in tile-local units - see [setBatchTexture]. */
        private const val COVERAGE_EPSILON = 1.0e-5

        /**
         * Resolves the right [SurfaceTextureProgram] variant for [rc] - the shadow-aware variant
         * when an enabled [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list, the
         * smaller-binary no-shadow variant otherwise.
         */
        fun get(rc: RenderContext): SurfaceTextureProgram = if (rc.hasShadowLayer) {
            rc.getShaderProgram { SurfaceTextureProgramShadow() }
        } else {
            rc.getShaderProgram { SurfaceTextureProgram() }
        }
    }

    private val unitCount = TEXTURE_UNITS.size

    override var programSources = arrayOf(
        defines() + """
            uniform mat4 mvpMatrix;
            #ifdef SHADOWS_ENABLED
            /* Tile-local -> camera-relative translation (terrainOrigin - eyePoint, differenced
               in double on the CPU) for shadow receivers. The fragment shader recovers a
               camera-relative position for the shadow lookup without re-uploading matrices. */
            uniform vec3 vertexOrigin;
            #endif

            attribute vec4 vertexPoint;
            attribute vec2 vertexTexCoord;
            #ifdef SHADOWS_ENABLED
            attribute vec3 vertexNormal;
            #endif

            varying vec2 tileCoord;
            #ifdef SHADOWS_ENABLED
            varying vec3 worldPos;
            varying float viewDepth;
            varying vec3 terrainNormal;
            #endif

            void main() {
                /* Transform the vertex position by the modelview-projection matrix. */
                gl_Position = mvpMatrix * vertexPoint;

                #ifdef SHADOWS_ENABLED
                worldPos = vertexPoint.xyz + vertexOrigin;
                viewDepth = gl_Position.w;
                terrainNormal = vertexNormal;
                #endif

                /* Tile-local texture coordinate; per-texture transforms happen in the fragment shader. */
                tileCoord = vertexTexCoord;
            }
        """.trimIndent(),
        defines() + """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            uniform bool enablePickMode;
            uniform vec4 color;
            uniform int texCount;
            uniform vec4 texScaleTrans[$unitCount];
            uniform vec4 texRect[$unitCount];
            uniform float texOpacity[$unitCount];
        """.trimIndent() + "\n" +
        (0 until unitCount).joinToString("\n") { "            uniform sampler2D texSampler$it;" } + """

            varying vec2 tileCoord;
            #ifdef SHADOWS_ENABLED
            varying vec3 worldPos;
            varying float viewDepth;
            varying vec3 terrainNormal;

            ${LightingGlsl.DECLARATIONS}

            ${ShadowReceiverGlsl.fragmentDeclarations(lit = true)}
            #endif

            void main() {
                if (enablePickMode) {
                    /* Pick renders one texture per draw; modulate the pick color by the texture's
                       rounded alpha and by the texture's sub-rectangle mask. */
                    vec2 uv = tileCoord * texScaleTrans[0].xy + texScaleTrans[0].zw;
                    vec2 lo = step(texRect[0].xy, tileCoord);
                    vec2 hi = step(tileCoord, texRect[0].zw);
                    float texMask = floor(texture2D(texSampler0, uv).a + 0.5);
                    gl_FragColor = color * (texMask * lo.x * lo.y * hi.x * hi.y);
                } else {
                    /* Composite the attached textures bottom-to-top with premultiplied-alpha OVER.
                       Fragments outside a texture's sub-rectangle contribute nothing. */
                    vec4 accum = vec4(0.0);
        """.trimIndent() + "\n" +
        (0 until unitCount).joinToString("\n") { k -> """
                    if (texCount > $k) {
                        vec2 lo$k = step(texRect[$k].xy, tileCoord);
                        vec2 hi$k = step(tileCoord, texRect[$k].zw);
                        float mask$k = lo$k.x * lo$k.y * hi$k.x * hi$k.y;
                        vec2 uv$k = tileCoord * texScaleTrans[$k].xy + texScaleTrans[$k].zw;
                        /* Sampling stays outside divergent control flow on every platform:
                           implicit-LOD inside a fragment-varying branch is undefined and fetches
                           wrong mips at rectangle edges (dark seams along the coverage grid on
                           both desktop GL and Mali); the mask zeroes contributions instead. */
                        vec4 src$k = texture2D(texSampler$k, uv$k) * (texOpacity[$k] * mask$k);
                        accum = src$k + accum * (1.0 - src$k.a);
                    }""" } + """

                    gl_FragColor = accum;

                    #ifdef SHADOWS_ENABLED
                    gl_FragColor.rgb *= terrainLambert > 0.0
                        ? terrainReliefFactor(terrainNormal, worldPos, viewDepth)
                        : shadowAlbedoFactor(worldPos, viewDepth);
                    #endif
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "vertexTexCoord", "vertexNormal")

    // Prepend [Kgl.glslDerivativesPrefix] (WW_HAS_DERIVATIVES + platform-aware extension
    // directive) so the shadow receiver's receiver-plane depth bias can use dFdx/dFdy.
    override fun glslVersion(dc: DrawContext) = ShadowReceiverGlsl.glslPrefix(dc, shadowsEnabled)

    private fun defines() = if (shadowsEnabled) ShadowReceiverGlsl.SHADOWS_ENABLED_DEFINE else ""

    val mvpMatrix = Matrix4()
    private var enablePickModeId = KglUniformLocation.NONE
    private var mvpMatrixId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var texCountId = KglUniformLocation.NONE
    private var texScaleTransId = KglUniformLocation.NONE
    private var texRectId = KglUniformLocation.NONE
    private var texOpacityId = KglUniformLocation.NONE
    private var vertexOriginId = KglUniformLocation.NONE
    private val shadowUniforms = ShadowReceiverUniforms(shadowsEnabled)
    private val mvpMatrixArray = FloatArray(16)
    private val color = Color()
    // Batch upload staging - program instances are per-GL-context, like mvpMatrixArray above.
    private val tileTransform = Matrix3()
    private val scaleTransArray = FloatArray(TEXTURE_UNITS.size * 4)
    private val rectArray = FloatArray(TEXTURE_UNITS.size * 4)
    private val opacityArray = FloatArray(TEXTURE_UNITS.size)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        enablePickModeId = gl.getUniformLocation(program, "enablePickMode")
        gl.uniform1i(enablePickModeId, 0) // disable pick mode
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        Matrix4().transposeToArray(mvpMatrixArray, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, mvpMatrixArray, 0)
        colorId = gl.getUniformLocation(program, "color")
        color.set(1f, 1f, 1f, 1f) // opaque white
        gl.uniform4f(colorId, color.red, color.green, color.blue, color.alpha)
        texCountId = gl.getUniformLocation(program, "texCount")
        gl.uniform1i(texCountId, 0)
        texScaleTransId = gl.getUniformLocation(program, "texScaleTrans")
        texRectId = gl.getUniformLocation(program, "texRect")
        texOpacityId = gl.getUniformLocation(program, "texOpacity")
        for (k in TEXTURE_UNITS.indices) {
            gl.uniform1i(gl.getUniformLocation(program, "texSampler$k"), TEXTURE_UNITS[k])
        }

        // Shadow receiver uniforms - getUniformLocation returns NONE on the no-shadow variant
        // (GLSL preprocessor stripped the declarations); subsequent uniformX calls are silent
        // GL no-ops there. Cascade samplers stay on the engine-wide units 1..4.
        vertexOriginId = gl.getUniformLocation(program, "vertexOrigin")
        gl.uniform3f(vertexOriginId, 0f, 0f, 0f)
        shadowUniforms.init(gl, program)
    }

    fun enablePickMode(enable: Boolean) { gl.uniform1i(enablePickModeId, if (enable) 1 else 0) }

    fun loadModelviewProjection() {
        mvpMatrix.transposeToArray(mvpMatrixArray, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, mvpMatrixArray, 0)
    }

    fun loadColor(color: Color) {
        if (this.color != color) {
            this.color.copy(color)
            val alpha = color.alpha
            gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)
        }
    }

    /**
     * Packs batch slot [k] from the texture's coordinate transform and the terrain/texture
     * sectors: the tile-local -> image scale+translation and the texture's coverage rectangle in
     * tile-local coordinates. [texCoordMatrix] must be axis-aligned affine (scale + translation,
     * e.g. a vertical flip); rotation and shear terms are ignored.
     */
    fun setBatchTexture(k: Int, texCoordMatrix: Matrix3, terrainSector: Sector, textureSector: Sector, opacity: Float) {
        // The tile transform maps tile-local coordinates into the texture's sector
        val tt = tileTransform.setToTileTransform(terrainSector, textureSector).m
        val cm = texCoordMatrix.m
        val k4 = k * 4
        // Compose the texture's coord transform over the tile transform (both axis-aligned affine)
        scaleTransArray[k4] = (cm[0] * tt[0]).toFloat()
        scaleTransArray[k4 + 1] = (cm[4] * tt[4]).toFloat()
        scaleTransArray[k4 + 2] = (cm[0] * tt[2] + cm[2]).toFloat()
        scaleTransArray[k4 + 3] = (cm[4] * tt[5] + cm[5]).toFloat()
        // Texture coverage in tile-local coordinates: where the tile transform lands inside [0,1].
        // Exact division, padded outward by an epsilon: a rounded-down bound would mask the last
        // fragment column where texture and tile edges coincide (a visible seam at the antimeridian,
        // where no neighbor covers the strip); the pad only lets clamp-to-edge repeat a border texel.
        rectArray[k4] = (-tt[2] / tt[0] - COVERAGE_EPSILON).toFloat()
        rectArray[k4 + 1] = (-tt[5] / tt[4] - COVERAGE_EPSILON).toFloat()
        rectArray[k4 + 2] = ((1.0 - tt[2]) / tt[0] + COVERAGE_EPSILON).toFloat()
        rectArray[k4 + 3] = ((1.0 - tt[5]) / tt[4] + COVERAGE_EPSILON).toFloat()
        opacityArray[k] = opacity
    }

    /**
     * Uploads the first [count] batch slots packed by [setBatchTexture].
     */
    fun loadBatchTextures(count: Int) {
        gl.uniform1i(texCountId, count)
        gl.uniform4fv(texScaleTransId, count, scaleTransArray, 0)
        gl.uniform4fv(texRectId, count, rectArray, 0)
        gl.uniform1fv(texOpacityId, count, opacityArray, 0)
    }

    /**
     * Sets the tile-local -> world translation for the next draw call. Per-tile because each
     * terrain tile uses its own local frame; loaded each iteration of the tile loop.
     * Drawables only call this when [earth.worldwind.draw.DrawContext.shadowState] is non-null,
     * so the call doesn't happen on no-shadow frames at all.
     */
    fun loadVertexOrigin(x: Float, y: Float, z: Float) {
        gl.uniform3f(vertexOriginId, x, y, z)
    }

    /** Forwards to [ShadowReceiverUniforms.loadTerrainRelief]; a no-op on the no-shadow variant. */
    fun loadTerrainRelief(strength: Float, lightDirection: Vec3?) =
        shadowUniforms.loadTerrainRelief(gl, strength, lightDirection)

    override var shadowUploadStamp: Long
        get() = shadowUniforms.uploadStamp
        set(value) { shadowUniforms.uploadStamp = value }

    override fun loadShadowDisabled() = shadowUniforms.loadDisabled(gl)

    override fun loadShadowEnabled(state: ShadowState) = shadowUniforms.loadEnabled(gl, state)
}

/**
 * Sentinel subclass: distinct cache key for the shadow-aware GLSL variant of
 * [SurfaceTextureProgram]. No GLSL or method overrides of its own - the parent's GLSL is the
 * single source of truth, the `#define SHADOWS_ENABLED` flipped on by the constructor
 * argument selects the shadow-aware compilation.
 */
class SurfaceTextureProgramShadow : SurfaceTextureProgram(shadowsEnabled = true)
