package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.KglUniformLocation

class BasicTextureProgram : AbstractShaderProgram(), ShadowReceiverProgram {
    override var programSources = arrayOf(
        """
            attribute vec4 vertexPoint;
            attribute vec2 vertexTexCoord;
            attribute vec4 normalVector;
            attribute float segmentWidth;

            uniform mat4 mvpMatrix;
            uniform mat4 mvInverseMatrix;
            uniform mat3 texCoordMatrix;
            uniform bool applyLighting;
            uniform bool isRenderLine;
            /* Model -> world transform for shadow receivers. Composed by the drawable as the
               combined per-shape / per-entity transform that maps tile-local vertices to ECEF
               Cartesian. Identity is fine when the drawable already places vertices in world. */
            uniform mat4 modelMatrix;

            varying vec2 texCoord;
            varying vec4 normal;
            /* Shadow receiver inputs. Always populated; the fragment shader's [applyShadow]
               branch elides the lookup when no [ShadowLayer] is active. */
            varying vec3 worldPos;
            varying float viewDepth;

            void main() {
                if (isRenderLine) {
                    vec4 vPoint = vec4(vertexPoint.xyz + normalVector.xyz * (segmentWidth / 2.0), 1.0);
                    gl_Position = mvpMatrix * vPoint;
                    worldPos = (modelMatrix * vPoint).xyz;
                } else {
                    gl_Position = mvpMatrix * vertexPoint;
                    worldPos = (modelMatrix * vertexPoint).xyz;
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                    if (applyLighting) {
                        normal = mvInverseMatrix * normalVector;
                    }
                }
                viewDepth = gl_Position.w;
            }
        """.trimIndent(),
        """
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
            varying vec4 normal;
            varying vec3 worldPos;
            varying float viewDepth;

            ${ShadowReceiverGlsl.FRAGMENT_DECLARATIONS}

            void main() {
                vec4 textureColor = texture2D(texSampler, texCoord);
                float ambient = 0.15;
                if (enableTexture && !modulateColor)
                    gl_FragColor = textureColor * color * opacity;
                else if (enableTexture && modulateColor)
                    gl_FragColor = color * ceil(textureColor.a);
                else
                    gl_FragColor = color * opacity;
                if (gl_FragColor.a == 0.0) {discard;}
                if (applyLighting) {
                    vec3 n = normalize(normal.xyz) * (gl_FrontFacing ? 1.0 : -1.0);
                    gl_FragColor.rgb *= clamp(ambient + dot(lightDirection, n), 0.0, 1.0);
                }
                /* Shadow attenuation. No-op when [applyShadow] is false. Picks ([modulateColor]
                   path) bypass shadow application so pick IDs aren't darkened. */
                if (!modulateColor) {
                    gl_FragColor.rgb *= computeShadowVisibility(worldPos, viewDepth);
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "normalVector", "vertexTexCoord")

    private var enableTexture = false
    private var modulateColor = false
    private var applyLighting = false
    private var isRenderLine = false
    private val mvpMatrix = Matrix4()
    private val mvInverseMatrix = Matrix4()
    private val texCoordMatrix = Matrix3()
    private val color = Color()
    private var opacity = 1.0f
    private val lightDirection = Vec3(0.0, 0.0, 1.0)
    private var mvpMatrixId = KglUniformLocation.NONE
    private var mvInverseMatrixId = KglUniformLocation.NONE
    private var modelMatrixId = KglUniformLocation.NONE
    private var applyShadowId = KglUniformLocation.NONE
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

        // Shadow receiver uniforms. modelMatrix defaults to identity so drawables that don't
        // bother loading it (no [ShadowLayer] active) still produce correct world positions.
        modelMatrixId = gl.getUniformLocation(program, "modelMatrix")
        Matrix4().transposeToArray(array, 0)
        gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
        applyShadowId = gl.getUniformLocation(program, "applyShadow")
        gl.uniform1i(applyShadowId, 0)
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
     * Loads the specified matrix as the value of this program's 'mvInverseMatrix' uniform variable.
     *
     * @param matrix The matrix to load.
     */
    fun loadModelviewInverse(matrix: Matrix4) {
        // Don't bother testing whether mvpMatrix has changed, the common case is to load a different matrix.
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvInverseMatrixId, 1, false, array, 0)
    }

    /**
     * Loads the specified matrix as the value of this program's 'mvpMatrix' uniform variable.
     *
     * @param matrix The matrix to load.
     */
    fun loadModelviewProjection(matrix: Matrix4) {
        // Don't bother testing whether mvpMatrix has changed, the common case is to load a different matrix.
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /**
     * Loads the specified color as the value of this program's 'color' uniform variable.
     *
     * @param color The color to load.
     */
    fun loadColor(color: Color) {
        if (this.color != color) {
            this.color.copy(color)
            val alpha = color.alpha
            gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)
        }
    }

    /**
     * Loads the specified boolean as the value of this program's 'enableTexture' uniform variable.
     * 
     * @param enable true to enable texturing, false to disable texturing.
     */
    fun loadTextureEnabled(enable: Boolean) {
        if (enableTexture != enable) {
            enableTexture = enable
            gl.uniform1i(enableTextureId, if (enable) 1 else 0)
        }
    }

    /**
     * Loads the specified boolean as the value of this program's 'modulateColor' uniform variable. When this
     * value is true and the value of the textureEnabled variable is true, the color uniform of this shader is
     * multiplied by the rounded alpha component of the texture color at each fragment. This causes the color
     * to be either fully opaque or fully transparent depending on the value of the texture color's alpha value.
     * This is used during picking to replace opaque or mostly opaque texture colors with the pick color, and
     * to make all other texture colors transparent.
     * 
     * @param enable true to enable modulation, false to disable modulation.
     */
    fun loadModulateColor(enable: Boolean) {
        if (modulateColor != enable) {
            modulateColor = enable
            gl.uniform1i(modulateColorId, if (enable) 1 else 0)
        }
    }

    /**
     * Loads the specified number as the value of this program's 'textureSampler' uniform variable.
     * 
     * @param unit The texture unit.
     */
    fun loadTextureUnit(unit: Int) {
        gl.uniform1i(texSamplerId, unit - GL_TEXTURE0)
    }

    /**
     * Loads the specified matrix as the value of this program's 'texCoordMatrix' uniform variable.
     * 
     * @param matrix The texture coordinate matrix.
     */
    fun loadTextureMatrix(matrix: Matrix3) {
        if (texCoordMatrix != matrix) {
            texCoordMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        }
    }

    /**
     * Loads the specified number as the value of this program's 'opacity' uniform variable.
     * 
     * @param opacity The opacity in the range [0, 1].
     */
    fun loadOpacity(opacity: Float) {
        if (this.opacity != opacity) {
            this.opacity = opacity
            gl.uniform1f(opacityId, opacity)
        }
    }

    /**
     * Loads the specified boolean as the value of this program's 'applyLighting' uniform variable.
     * 
     * @param applyLighting true to apply lighting, otherwise false.
     */
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
     *
     * @param direction Eye-space light direction. Must be normalized.
     */
    fun loadLightDirection(direction: Vec3) {
        if (lightDirection != direction) {
            lightDirection.copy(direction)
            gl.uniform3f(lightDirectionId, direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        }
    }

    /**
     * Loads the model -> world transform for shadow receivers. Pass the same translate /
     * compose that the drawable applied to [mvpMatrix] (minus the modelview-projection
     * portion). Identity matrix means "vertices are already in world space".
     */
    fun loadModelMatrix(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(modelMatrixId, 1, false, array, 0)
    }

    override var shadowUploadStamp: Long = -1L

    override fun loadShadowDisabled() {
        gl.uniform1i(applyShadowId, 0)
    }

    override fun loadShadowEnabled(
        ambientShadow: Float,
        lightProjectionView0: Matrix4,
        lightProjectionView1: Matrix4,
        lightProjectionView2: Matrix4,
        cascadeFarDepth0: Float,
        cascadeFarDepth1: Float,
        cascadeFarDepth2: Float,
    ) {
        gl.uniform1i(applyShadowId, 1)
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

    /**
     * Loads the specified boolean as the value of this program's 'isRenderLine' uniform variable.
     *
     * @param isRenderLine true if rendering lines, otherwise false.
     */
    fun loadIsRenderLine(isRenderLine: Boolean) {
        if (this.isRenderLine != isRenderLine) {
            this.isRenderLine = isRenderLine
            gl.uniform1i(isRenderLineId, if (isRenderLine) 1 else 0)
        }
    }

    companion object {
        val KEY = BasicTextureProgram::class
    }
}