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
            varying vec3 worldNormal;
            varying float viewDepth;
            #endif

            void main() {
                #ifdef SHADOWS_ENABLED
                /* Zero signals "no usable normal" to the receiver - line mode and unlit
                   meshes (their normal attribute may be disabled) sample depth-only. */
                worldNormal = vec3(0.0);
                #endif
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
                        #ifdef SHADOWS_ENABLED
                        /* Smooth world-space normal for the shadow normal-offset bias.
                           mat3 built from columns - ESSL 1.00 lacks mat3(mat4). */
                        worldNormal = mat3(modelMatrix[0].xyz, modelMatrix[1].xyz, modelMatrix[2].xyz) * normalVector.xyz;
                        #endif
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
            /* Eye-space globe-radial up at the camera (same transform), hemispheric ambient. */
            uniform vec3 upDirection;

            varying vec2 texCoord;
            varying vec3 normal;

            ${LightingGlsl.DECLARATIONS}
            #ifdef SHADOWS_ENABLED
            varying vec3 worldPos;
            varying vec3 worldNormal;
            varying float viewDepth;

            ${ShadowReceiverGlsl.fragmentDeclarations(lit = true)}
            #endif

            void main() {
                vec4 textureColor = texture2D(texSampler, texCoord);
                /* Early discard for transparent texels - skips the color compose, lighting,
                   and shadow work below. On tile-based GPUs (Adreno, Mali) this also preserves
                   the tile's hidden-surface-removal which a later discard would disable. */
                if (enableTexture && textureColor.a == 0.0) discard;
                if (enableTexture && !modulateColor)
                    gl_FragColor = textureColor * color * opacity;
                else if (enableTexture && modulateColor)
                    gl_FragColor = color * ceil(textureColor.a);
                else
                    gl_FragColor = color * opacity;
                if (gl_FragColor.a == 0.0) discard;
                /* modulateColor doubles as the pick-mode flag: skip lighting and shadow
                   attenuation there so pick IDs aren't darkened. */
                if (applyLighting) {
                    vec3 n = normalize(normal) * (gl_FrontFacing ? 1.0 : -1.0);
                    float lambert = max(dot(lightDirection, n), 0.0);
                    float upFactor = dot(n, upDirection) * 0.5 + 0.5;
                    #ifdef SHADOWS_ENABLED
                    if (!modulateColor) {
                        /* Flip the offset normal with the lighting normal so interior
                           faces of two-sided meshes bias toward their own visible side. */
                        vec3 wn = dot(worldNormal, worldNormal) > 0.0
                            ? normalize(worldNormal) * (gl_FrontFacing ? 1.0 : -1.0)
                            : vec3(0.0);
                        gl_FragColor.rgb *= shadowLitFactor(lambert, upFactor, worldPos, viewDepth, wn);
                    } else {
                        gl_FragColor.rgb *= litShadingFactor(lambert, upFactor, 1.0);
                    }
                    #else
                    gl_FragColor.rgb *= litShadingFactor(lambert, upFactor, 1.0);
                    #endif
                }
                #ifdef SHADOWS_ENABLED
                else if (!modulateColor) {
                    /* Unlit content: the shadow term is the only sun shading. */
                    gl_FragColor.rgb *= shadowAlbedoFactor(worldPos, viewDepth);
                }
                #endif
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "normalVector", "vertexTexCoord")

    // Prepend [Kgl.glslDerivativesPrefix] (WW_HAS_DERIVATIVES + platform-aware extension
    // directive) so the shadow receiver's receiver-plane depth bias can use dFdx/dFdy.
    override fun glslVersion(dc: earth.worldwind.draw.DrawContext) = ShadowReceiverGlsl.glslPrefix(dc, shadowsEnabled)


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
    private val upDirection = Vec3(0.0, 0.0, 1.0)
    private var mvpMatrixId = KglUniformLocation.NONE
    private var mvInverseMatrixId = KglUniformLocation.NONE
    private var modelMatrixId = KglUniformLocation.NONE
    private val shadowUniforms = ShadowReceiverUniforms(shadowsEnabled)
    private var colorId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var modulateColorId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var applyLightingId = KglUniformLocation.NONE
    private var lightDirectionId = KglUniformLocation.NONE
    private var upDirectionId = KglUniformLocation.NONE
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
        upDirectionId = gl.getUniformLocation(program, "upDirection")
        gl.uniform3f(upDirectionId, upDirection.x.toFloat(), upDirection.y.toFloat(), upDirection.z.toFloat())
        isRenderLineId = gl.getUniformLocation(program, "isRenderLine")
        gl.uniform1i(isRenderLineId, if (isRenderLine) 1 else 0)

        // Shadow receiver uniforms - see SurfaceTextureProgram.initProgram for the no-shadow
        // GL-no-op rationale.
        modelMatrixId = gl.getUniformLocation(program, "modelMatrix")
        modelMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
        shadowUniforms.init(gl, program)
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

    /** Loads the eye-space globe-radial up at the camera (hemispheric ambient); callers
     *  transform world up by the same modelview rotation as [loadLightDirection]. */
    fun loadUpDirection(direction: Vec3) {
        if (upDirection != direction) {
            upDirection.copy(direction)
            gl.uniform3f(upDirectionId, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
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

    override var shadowUploadStamp: Long
        get() = shadowUniforms.uploadStamp
        set(value) { shadowUniforms.uploadStamp = value }

    override fun loadShadowDisabled() = shadowUniforms.loadDisabled(gl)

    override fun loadShadowEnabled(state: ShadowState) = shadowUniforms.loadEnabled(gl, state)

    fun loadIsRenderLine(isRenderLine: Boolean) {
        if (this.isRenderLine != isRenderLine) {
            this.isRenderLine = isRenderLine
            gl.uniform1i(isRenderLineId, if (isRenderLine) 1 else 0)
        }
    }

    companion object {
        /**
         * Resolves the [BasicTextureProgram] variant for [rc] - the shadow-aware variant
         * when an enabled [earth.worldwind.layer.shadow.ShadowLayer] is in the layer list,
         * the smaller-binary no-shadow variant otherwise.
         */
        fun get(rc: RenderContext): BasicTextureProgram = if (rc.hasShadowLayer) {
            rc.getShaderProgram { BasicTextureProgramShadow() }
        } else {
            rc.getShaderProgram { BasicTextureProgram() }
        }
    }
}

/** Sentinel subclass: distinct cache key for the shadow-aware GLSL variant. See [BasicTextureProgram]. */
class BasicTextureProgramShadow : BasicTextureProgram(shadowsEnabled = true)
