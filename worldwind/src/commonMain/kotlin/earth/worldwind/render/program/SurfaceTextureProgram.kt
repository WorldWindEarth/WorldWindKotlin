package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.layer.shadow.ShadowReceiverUniforms
import earth.worldwind.layer.shadow.ShadowState
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.util.kgl.KglUniformLocation

// TODO Try accumulating surface tile state (texCoordMatrix, texSampler), loading uniforms once, then loading a uniform
// TODO index to select the state for a surface tile. This reduces the uniform calls when many surface tiles intersect
// TODO one terrain tile.
// TODO Try class representing transform with a specific scale+translate object that can be uploaded to a GLSL vec4
/**
 * Surface tile / surface texture program. A single GLSL source carries both the no-shadow
 * (default) and the shadow-aware paths, gated by a `#define SHADOWS_ENABLED` preprocessor
 * symbol that [shadowsEnabled] toggles. Most apps don't use shadows, so the default
 * `SurfaceTextureProgram()` instance compiles to the smaller-binary no-shadow program.
 * The sentinel subclass [SurfaceTextureProgramShadow] - selected by [get] when an
 * [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list - exists only to give
 * the cache a distinct [kotlin.reflect.KClass] key for the shadow-aware variant; it carries
 * no GLSL or method overrides of its own.
 */
open class SurfaceTextureProgram(
    protected val shadowsEnabled: Boolean = false,
) : AbstractShaderProgram(), ShadowReceiverProgram {
    override var programSources = arrayOf(
        defines() + """
            uniform bool enableTexture;
            uniform mat4 mvpMatrix;
            uniform mat3 texCoordMatrix[2];
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

            varying vec2 texCoord;
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

                /* Transform the vertex tex coord by the tex coord matrices. */
                if (enableTexture) {
                    vec3 texCoord3 = vec3(vertexTexCoord, 1.0);
                    texCoord = (texCoordMatrix[0] * texCoord3).st;
                    tileCoord = (texCoordMatrix[1] * texCoord3).st;
                }
            }
        """.trimIndent(),
        defines() + """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform vec4 color;
            uniform float opacity;
            uniform sampler2D texSampler;

            varying vec2 texCoord;
            varying vec2 tileCoord;
            #ifdef SHADOWS_ENABLED
            varying vec3 worldPos;
            varying float viewDepth;
            varying vec3 terrainNormal;

            ${LightingGlsl.DECLARATIONS}

            ${ShadowReceiverGlsl.fragmentDeclarations(lit = true)}
            #endif

            void main() {
                /* Using the second texture coordinate, compute a mask that's 1.0 when the fragment is inside the surface tile, and
                   0.0 otherwise. */
                float sMask = step(0.0, tileCoord.s) * step(0.0, 1.0 - tileCoord.s);
                float tMask = step(0.0, tileCoord.t) * step(0.0, 1.0 - tileCoord.t);
                float tileMask = sMask * tMask;

                if (enablePickMode && enableTexture) {
                    /* Using the first texture coordinate, modulate the RGBA color with the 2D texture's Alpha component (rounded to
                       0.0 or 1.0). Finally, modulate the result by the tile mask to suppress fragments outside the surface tile. */
                    float texMask = floor(texture2D(texSampler, texCoord).a + 0.5);
                    gl_FragColor = color * texMask * tileMask;
                } else if (!enablePickMode && enableTexture) {
                    /* Using the first texture coordinate, modulate the RGBA color with the 2D texture's RGBA color. Finally,
                       modulate by the tile mask to suppress fragments outside the surface tile. */
                    gl_FragColor = color * texture2D(texSampler, texCoord) * opacity * tileMask;
                } else {
                    /* Modulate the RGBA color by the tile mask to suppress fragments outside the surface tile. */
                    gl_FragColor = color * opacity * tileMask;
                }

                #ifdef SHADOWS_ENABLED
                /* Skip shadow attenuation in pick mode so picked terrain isn't darkened. */
                if (!enablePickMode) {
                    gl_FragColor.rgb *= terrainLambert > 0.0
                        ? terrainReliefFactor(terrainNormal, worldPos, viewDepth)
                        : shadowAlbedoFactor(worldPos, viewDepth);
                }
                #endif
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "vertexTexCoord", "vertexNormal")

    // Prepend [Kgl.glslDerivativesPrefix] (WW_HAS_DERIVATIVES + platform-aware extension
    // directive) so the shadow receiver's receiver-plane depth bias can use dFdx/dFdy.
    override fun glslVersion(dc: earth.worldwind.draw.DrawContext) = ShadowReceiverGlsl.glslPrefix(dc, shadowsEnabled)


    private fun defines() = if (shadowsEnabled) ShadowReceiverGlsl.SHADOWS_ENABLED_DEFINE else ""

    val mvpMatrix = Matrix4()
    val texCoordMatrix = arrayOf(Matrix3(), Matrix3())
    private var enablePickModeId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var mvpMatrixId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var vertexOriginId = KglUniformLocation.NONE
    private val shadowUniforms = ShadowReceiverUniforms(shadowsEnabled)
    private val mvpMatrixArray = FloatArray(16)
    private val texCoordMatrixArray = FloatArray(9 * 2)
    private val color = Color()
    private var opacity = 1.0f

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        enablePickModeId = gl.getUniformLocation(program, "enablePickMode")
        gl.uniform1i(enablePickModeId, 0) // disable pick mode
        enableTextureId = gl.getUniformLocation(program, "enableTexture")
        gl.uniform1i(enableTextureId, 0) // disable texture
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        Matrix4().transposeToArray(mvpMatrixArray, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, mvpMatrixArray, 0)
        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        Matrix3().transposeToArray(texCoordMatrixArray, 0) // 3 x 3 identity matrix
        Matrix3().transposeToArray(texCoordMatrixArray, 9) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 2, false, texCoordMatrixArray, 0)
        colorId = gl.getUniformLocation(program, "color")
        color.set(1f, 1f, 1f, 1f) // opaque white
        gl.uniform4f(colorId, color.red, color.green, color.blue, color.alpha)
        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, opacity)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0

        // Shadow receiver uniforms - getUniformLocation returns NONE on the no-shadow variant
        // (GLSL preprocessor stripped the declarations); subsequent uniformX calls are silent
        // GL no-ops there.
        vertexOriginId = gl.getUniformLocation(program, "vertexOrigin")
        gl.uniform3f(vertexOriginId, 0f, 0f, 0f)
        shadowUniforms.init(gl, program)
    }

    fun enablePickMode(enable: Boolean) { gl.uniform1i(enablePickModeId, if (enable) 1 else 0) }

    fun enableTexture(enable: Boolean) { gl.uniform1i(enableTextureId, if (enable) 1 else 0) }

    fun loadModelviewProjection() {
        mvpMatrix.transposeToArray(mvpMatrixArray, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, mvpMatrixArray, 0)
    }

    fun loadTexCoordMatrix() {
        texCoordMatrix[0].transposeToArray(texCoordMatrixArray, 0)
        texCoordMatrix[1].transposeToArray(texCoordMatrixArray, 9)
        gl.uniformMatrix3fv(texCoordMatrixId, 2, false, texCoordMatrixArray, 0)
    }

    fun loadColor(color: Color) {
        if (this.color != color) {
            this.color.copy(color)
            val alpha = color.alpha
            gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)
        }
    }

    fun loadOpacity(opacity: Float) {
        if (this.opacity != opacity) {
            this.opacity = opacity
            gl.uniform1f(opacityId, opacity)
        }
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

    companion object {
        /**
         * Resolves the right [SurfaceTextureProgram] variant for [rc] - the shadow-aware
         * variant when an enabled [earth.worldwind.layer.shadow.ShadowLayer] is in the layer
         * list, the smaller-binary no-shadow variant otherwise.
         */
        fun get(rc: RenderContext): SurfaceTextureProgram = if (rc.hasShadowLayer) {
            rc.getShaderProgram { SurfaceTextureProgramShadow() }
        } else {
            rc.getShaderProgram { SurfaceTextureProgram() }
        }
    }
}

/**
 * Sentinel subclass: distinct cache key for the shadow-aware GLSL variant of
 * [SurfaceTextureProgram]. No GLSL or method overrides of its own - the parent's GLSL is the
 * single source of truth, the `#define SHADOWS_ENABLED` flipped on by the constructor
 * argument selects the shadow-aware compilation, and the parent's `loadShadowX` /
 * `loadVertexOrigin` bodies do the actual uniform uploads against the now-valid uniform
 * locations.
 */
class SurfaceTextureProgramShadow : SurfaceTextureProgram(shadowsEnabled = true)
