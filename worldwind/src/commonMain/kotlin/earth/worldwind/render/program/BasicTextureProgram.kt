package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Lit / unlit textured-mesh program. A single GLSL source carries both the no-shadow
 * (default) and shadow-aware paths, gated by a `#define SHADOWS_ENABLED` preprocessor
 * symbol that [shadowsEnabled] toggles. Default `BasicTextureProgram()` is the smaller-binary
 * no-shadow variant; [BasicTextureProgramShadow] - selected by [get] when an
 * [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list - flips the `#define` on.
 */
open class BasicTextureProgram(
    protected val shadowsEnabled: Boolean = false,
) : AbstractShaderProgram(), ShadowReceiverProgram {
    override var programSources = arrayOf(
        defines() + """
            attribute vec4 vertexPoint;
            attribute vec2 vertexTexCoord;
            attribute vec4 normalVector;
            attribute float segmentWidth;

            uniform mat4 mvpMatrix;
            uniform mat4 mvInverseMatrix;
            uniform mat3 texCoordMatrix;
            uniform bool applyLighting;
            uniform bool isRenderLine;
            #ifdef SHADOWS_ENABLED
            /* Model -> world transform for shadow receivers. Composed by the drawable as the
               combined per-shape / per-entity transform that maps tile-local vertices to ECEF
               Cartesian. Identity is fine when the drawable already places vertices in world. */
            uniform mat4 modelMatrix;
            #endif

            varying vec2 texCoord;
            varying vec3 normal;
            #ifdef SHADOWS_ENABLED
            varying vec3 worldPos;
            varying float viewDepth;
            #endif

            void main() {
                if (isRenderLine) {
                    vec4 vPoint = vec4(vertexPoint.xyz + normalVector.xyz * (segmentWidth / 2.0), 1.0);
                    gl_Position = mvpMatrix * vPoint;
                    #ifdef SHADOWS_ENABLED
                    worldPos = (modelMatrix * vPoint).xyz;
                    #endif
                } else {
                    gl_Position = mvpMatrix * vertexPoint;
                    #ifdef SHADOWS_ENABLED
                    worldPos = (modelMatrix * vertexPoint).xyz;
                    #endif
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                    if (applyLighting) {
                        normal = (mvInverseMatrix * normalVector).xyz;
                    }
                }
                #ifdef SHADOWS_ENABLED
                viewDepth = gl_Position.w;
                #endif
            }
        """.trimIndent(),
        defines() + """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            uniform float opacity;
            uniform vec4 color;
            uniform bool enableTexture;
            uniform bool modulateColor;
            uniform sampler2D texSampler;
            uniform bool applyLighting;
            /* Eye-space unit vector pointing toward the light source. CPU pre-multiplies the
               world-space sun direction by the modelview rotation so the fragment shader can
               dot directly against the eye-space normal. */
            uniform vec3 lightDirection;

            varying vec2 texCoord;
            varying vec3 normal;
            #ifdef SHADOWS_ENABLED
            varying vec3 worldPos;
            varying float viewDepth;

            ${ShadowReceiverGlsl.FRAGMENT_DECLARATIONS}
            #endif

            void main() {
                vec4 textureColor = texture2D(texSampler, texCoord);
                /* Early discard for transparent texels - skips the color compose, lighting,
                   and shadow work below. On tile-based GPUs (Adreno, Mali) this also preserves
                   the tile's hidden-surface-removal which a later discard would disable. */
                if (enableTexture && textureColor.a == 0.0) discard;
                float ambient = 0.15;
                if (enableTexture && !modulateColor)
                    gl_FragColor = textureColor * color * opacity;
                else if (enableTexture && modulateColor)
                    gl_FragColor = color * ceil(textureColor.a);
                else
                    gl_FragColor = color * opacity;
                if (gl_FragColor.a == 0.0) discard;
                if (applyLighting) {
                    vec3 n = normalize(normal) * (gl_FrontFacing ? 1.0 : -1.0);
                    gl_FragColor.rgb *= clamp(ambient + dot(lightDirection, n), 0.0, 1.0);
                }
                #ifdef SHADOWS_ENABLED
                /* Skip shadow attenuation in pick mode so pick IDs aren't darkened. */
                if (!modulateColor) {
                    gl_FragColor.rgb *= computeShadowVisibility(worldPos, viewDepth);
                }
                #endif
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "normalVector", "vertexTexCoord")

    private fun defines() = if (shadowsEnabled) ShadowReceiverGlsl.SHADOWS_ENABLED_DEFINE else ""

    private var enableTexture = false
    private var modulateColor = false
    private var applyLighting = false
    private var isRenderLine = false
    private val mvpMatrix = Matrix4()
    private val mvInverseMatrix = Matrix4()
    /** Cached value of the last uploaded `modelMatrix`; defaults to identity (matches the
     *  init-time upload). Used by [loadModelMatrix] to skip redundant uploads. */
    private val modelMatrix = Matrix4()
    private val texCoordMatrix = Matrix3()
    private val color = Color()
    private var opacity = 1.0f
    private val lightDirection = Vec3(0.0, 0.0, 1.0)
    private var mvpMatrixId = KglUniformLocation.NONE
    private var mvInverseMatrixId = KglUniformLocation.NONE
    private var modelMatrixId = KglUniformLocation.NONE
    private var applyShadowId = KglUniformLocation.NONE
    private var useMSMId = KglUniformLocation.NONE
    private var ambientShadowId = KglUniformLocation.NONE
    private val shadowMapIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val cascadeFarDepthIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewArray = FloatArray(16)
    private var colorId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var modulateColorId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var applyLightingId = KglUniformLocation.NONE
    private var lightDirectionId = KglUniformLocation.NONE
    private var isRenderLineId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        mvpMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        mvInverseMatrixId = gl.getUniformLocation(program, "mvInverseMatrix")
        mvInverseMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvInverseMatrixId, 1, false, array, 0)
        colorId = gl.getUniformLocation(program, "color")
        val alpha = color.alpha
        gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)
        enableTextureId = gl.getUniformLocation(program, "enableTexture")
        gl.uniform1i(enableTextureId, if (enableTexture) 1 else 0)
        modulateColorId = gl.getUniformLocation(program, "modulateColor")
        gl.uniform1i(modulateColorId, if (modulateColor) 1 else 0)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0
        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        texCoordMatrix.transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, opacity)
        applyLightingId = gl.getUniformLocation(program, "applyLighting")
        gl.uniform1i(applyLightingId, if (applyLighting) 1 else 0)
        lightDirectionId = gl.getUniformLocation(program, "lightDirection")
        gl.uniform3f(lightDirectionId, lightDirection.x.toFloat(), lightDirection.y.toFloat(), lightDirection.z.toFloat())
        isRenderLineId = gl.getUniformLocation(program, "isRenderLine")
        gl.uniform1i(isRenderLineId, if (isRenderLine) 1 else 0)

        // Shadow receiver uniforms - see SurfaceTextureProgram.initProgram for the no-shadow
        // GL-no-op rationale.
        modelMatrixId = gl.getUniformLocation(program, "modelMatrix")
        modelMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
        applyShadowId = gl.getUniformLocation(program, "applyShadow")
        gl.uniform1i(applyShadowId, 0)
        useMSMId = gl.getUniformLocation(program, "useMSM")
        gl.uniform1i(useMSMId, 0)
        ambientShadowId = gl.getUniformLocation(program, "ambientShadow")
        gl.uniform1f(ambientShadowId, 0.4f)
        for (i in shadowMapIds.indices) {
            shadowMapIds[i] = gl.getUniformLocation(program, "shadowMap$i")
            gl.uniform1i(shadowMapIds[i], 1 + i) // GL_TEXTURE1 + i
            lightProjectionViewIds[i] = gl.getUniformLocation(program, "lightProjectionView$i")
            cascadeFarDepthIds[i] = gl.getUniformLocation(program, "cascadeFarDepth$i")
            gl.uniform1f(cascadeFarDepthIds[i], 0f)
        }
    }

    /**
     * Loads the specified matrix as the value of this program's 'mvInverseMatrix' uniform
     * variable. Skips the upload when the value matches the cached one - COLLADA / glTF
     * scenes with many sub-meshes sharing the same camera state see the same matrix on
     * every draw, so dirty-checking pays off.
     */
    fun loadModelviewInverse(matrix: Matrix4) {
        if (mvInverseMatrix != matrix) {
            mvInverseMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix4fv(mvInverseMatrixId, 1, false, array, 0)
        }
    }

    /**
     * Loads the specified matrix as the value of this program's 'mvpMatrix' uniform variable.
     * Dirty-checked - same rationale as [loadModelviewInverse].
     */
    fun loadModelviewProjection(matrix: Matrix4) {
        if (mvpMatrix != matrix) {
            mvpMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        }
    }

    fun loadColor(color: Color) {
        if (this.color != color) {
            this.color.copy(color)
            val alpha = color.alpha
            gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)
        }
    }

    fun loadTextureEnabled(enable: Boolean) {
        if (enableTexture != enable) {
            enableTexture = enable
            gl.uniform1i(enableTextureId, if (enable) 1 else 0)
        }
    }

    fun loadModulateColor(enable: Boolean) {
        if (modulateColor != enable) {
            modulateColor = enable
            gl.uniform1i(modulateColorId, if (enable) 1 else 0)
        }
    }

    fun loadTextureUnit(unit: Int) {
        gl.uniform1i(texSamplerId, unit - GL_TEXTURE0)
    }

    fun loadTextureMatrix(matrix: Matrix3) {
        if (texCoordMatrix != matrix) {
            texCoordMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        }
    }

    fun loadOpacity(opacity: Float) {
        if (this.opacity != opacity) {
            this.opacity = opacity
            gl.uniform1f(opacityId, opacity)
        }
    }

    fun loadApplyLighting(applyLighting: Boolean) {
        if (this.applyLighting != applyLighting) {
            this.applyLighting = applyLighting
            gl.uniform1i(applyLightingId, if (applyLighting) 1 else 0)
        }
    }

    /**
     * Loads an eye-space unit vector pointing toward the light source. Callers transform the
     * world-space sun direction by the modelview rotation (see [DrawContext.modelviewNormalTransform])
     * before passing it in, so the fragment shader can dot it directly against eye-space normals.
     */
    fun loadLightDirection(direction: Vec3) {
        if (lightDirection != direction) {
            lightDirection.copy(direction)
            gl.uniform3f(lightDirectionId, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        }
    }

    /**
     * Loads the model -> world transform for shadow receivers. Dirty-checked: COLLADA / glTF
     * scenes with many sub-meshes sharing the same root world transform see the same matrix
     * on every draw. Drawables only call this when [earth.worldwind.draw.DrawContext.shadowState]
     * is non-null, so it doesn't run on no-shadow frames at all.
     */
    fun loadModelMatrix(matrix: Matrix4) {
        if (modelMatrix != matrix) {
            modelMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
        }
    }

    override var shadowUploadStamp: Long = -1L

    override fun loadShadowDisabled() {
        if (shadowsEnabled) gl.uniform1i(applyShadowId, 0)
    }

    override fun loadShadowEnabled(
        ambientShadow: Float,
        lightProjectionView0: Matrix4,
        lightProjectionView1: Matrix4,
        lightProjectionView2: Matrix4,
        cascadeFarDepth0: Float,
        cascadeFarDepth1: Float,
        cascadeFarDepth2: Float,
        useMSM: Boolean,
    ) {
        gl.uniform1i(applyShadowId, 1)
        gl.uniform1i(useMSMId, if (useMSM) 1 else 0)
        gl.uniform1f(ambientShadowId, ambientShadow)
        lightProjectionView0.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[0], 1, false, lightProjectionViewArray, 0)
        lightProjectionView1.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[1], 1, false, lightProjectionViewArray, 0)
        lightProjectionView2.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[2], 1, false, lightProjectionViewArray, 0)
        gl.uniform1f(cascadeFarDepthIds[0], cascadeFarDepth0)
        gl.uniform1f(cascadeFarDepthIds[1], cascadeFarDepth1)
        gl.uniform1f(cascadeFarDepthIds[2], cascadeFarDepth2)
    }

    fun loadIsRenderLine(isRenderLine: Boolean) {
        if (this.isRenderLine != isRenderLine) {
            this.isRenderLine = isRenderLine
            gl.uniform1i(isRenderLineId, if (isRenderLine) 1 else 0)
        }
    }

    companion object {
        val KEY = BasicTextureProgram::class

        /**
         * Resolves the [BasicTextureProgram] variant for [rc] - the shadow-aware variant
         * when an enabled [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list,
         * the smaller-binary no-shadow variant otherwise.
         */
        fun get(rc: RenderContext): BasicTextureProgram = if (rc.hasShadowLayer) {
            rc.getShaderProgram(BasicTextureProgramShadow.KEY) { BasicTextureProgramShadow() }
        } else {
            rc.getShaderProgram(KEY) { BasicTextureProgram() }
        }
    }
}

/** Sentinel subclass: distinct cache key for the shadow-aware GLSL variant. See [BasicTextureProgram]. */
class BasicTextureProgramShadow : BasicTextureProgram(shadowsEnabled = true) {
    companion object {
        val KEY = BasicTextureProgramShadow::class
    }
}
