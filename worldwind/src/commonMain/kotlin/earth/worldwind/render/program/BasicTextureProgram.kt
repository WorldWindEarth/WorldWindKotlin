package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.KglUniformLocation

class BasicTextureProgram : AbstractShaderProgram() {
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

            varying vec2 texCoord;
            varying vec4 normal;

            void main() {
                if (isRenderLine) {
                    vec4 vPoint = vec4(vertexPoint.xyz + normalVector.xyz * (segmentWidth / 2.0), 1.0);
                    gl_Position = mvpMatrix * vPoint;
                } else {
                    gl_Position = mvpMatrix * vertexPoint;
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                    if (applyLighting) {
                        normal = mvInverseMatrix * normalVector;
                    }
                }
            }
        """.trimIndent(),
        """
            precision mediump float;

            uniform float opacity;
            uniform vec4 color;
            uniform bool enableTexture;
            uniform bool modulateColor;
            uniform sampler2D texSampler;
            uniform bool applyLighting;

            varying vec2 texCoord;
            varying vec4 normal;

            void main() {
                vec4 textureColor = texture2D(texSampler, texCoord);
                float ambient = 0.15; vec4 lightDirection = vec4(0, 0, 1, 0);
                if (enableTexture && !modulateColor)
                    gl_FragColor = textureColor * color * opacity;
                else if (enableTexture && modulateColor)
                    gl_FragColor = color * ceil(textureColor.a);
                else
                    gl_FragColor = color * opacity;
                if (gl_FragColor.a == 0.0) {discard;}
                if (applyLighting) {
                    vec4 n = normal * (gl_FrontFacing ? 1.0 : -1.0);
                    gl_FragColor.rgb *= clamp(ambient + dot(lightDirection, n), 0.0, 1.0);
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
    private var mvpMatrixId = KglUniformLocation.NONE
    private var mvInverseMatrixId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var modulateColorId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var applyLightingId = KglUniformLocation.NONE
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
        isRenderLineId = gl.getUniformLocation(program, "isRenderLine")
        gl.uniform1i(isRenderLineId, if (isRenderLine) 1 else 0)
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