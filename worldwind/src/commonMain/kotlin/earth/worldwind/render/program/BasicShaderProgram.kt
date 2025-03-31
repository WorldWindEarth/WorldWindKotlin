package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

open class BasicShaderProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform bool enableTexture;
            uniform mat4 mvpMatrix;
            uniform mat3 texCoordMatrix;

            attribute vec4 vertexPoint;
            attribute vec2 vertexTexCoord;

            varying vec2 texCoord;

            void main() {
                /* Transform the vertex position by the modelview-projection matrix. */
                gl_Position = mvpMatrix * vertexPoint;

                /* Transform the vertex tex coord by the tex coord matrix. */
                if (enableTexture) {
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                }
            }
        """.trimIndent(),
        """
            precision mediump float;

            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform vec4 color;
            uniform float opacity;
            uniform sampler2D texSampler;

            varying vec2 texCoord;

            void main() {
                /* TODO consolidate pickMode and enableTexture into a single textureMode */
                /* TODO it's confusing that pickMode must be disabled during surface shape render-to-texture */
                if (enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's Alpha component (rounded to 0.0 or 1.0). */
                    float texMask = floor(texture2D(texSampler, texCoord).a + 0.5);
                    gl_FragColor = color * texMask;
                } else if (!enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's RGBA color. */
                    gl_FragColor = color * texture2D(texSampler, texCoord) * opacity;
                } else {
                    /* Return the RGBA color as-is. */
                    gl_FragColor = color * opacity;
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "vertexTexCoord")

    protected var enablePickMode = false
    protected var enableTexture = false
    protected val mvpMatrix = Matrix4()
    protected val texCoordMatrix = Matrix3()
    protected val color = Color()
    protected var opacity = 1.0f
    protected var enablePickModeId = KglUniformLocation.NONE
    protected var enableTextureId = KglUniformLocation.NONE
    protected var mvpMatrixId = KglUniformLocation.NONE
    protected var texCoordMatrixId = KglUniformLocation.NONE
    protected var texSamplerId = KglUniformLocation.NONE
    protected var colorId = KglUniformLocation.NONE
    protected var opacityId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        enablePickModeId = gl.getUniformLocation(program, "enablePickMode")
        gl.uniform1i(enablePickModeId, if (enablePickMode) 1 else 0)
        enableTextureId = gl.getUniformLocation(program, "enableTexture")
        gl.uniform1i(enableTextureId, if (enableTexture) 1 else 0)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        mvpMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        texCoordMatrix.transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        colorId = gl.getUniformLocation(program, "color")
        val alpha = color.alpha
        gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)
        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, opacity)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0
    }

    fun enablePickMode(enable: Boolean) {
        if (enablePickMode != enable) {
            enablePickMode = enable
            gl.uniform1i(enablePickModeId, if (enable) 1 else 0)
        }
    }

    fun enableTexture(enable: Boolean) {
        if (enableTexture != enable) {
            enableTexture = enable
            gl.uniform1i(enableTextureId, if (enable) 1 else 0)
        }
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        // Don't bother testing whether mvpMatrix has changed, the common case is to load a different matrix.
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    fun loadTexCoordMatrix(matrix: Matrix3) {
        if (texCoordMatrix != matrix) {
            texCoordMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        }
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

    companion object {
        val KEY = BasicShaderProgram::class
    }
}