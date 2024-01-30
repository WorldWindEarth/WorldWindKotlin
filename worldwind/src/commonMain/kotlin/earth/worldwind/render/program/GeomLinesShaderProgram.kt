package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

open class GeomLinesShaderProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform float lineWidth;
            attribute vec4 vertexPoint;
            attribute float value;

            void main() {
                /* Transform the vertex position by the modelview-projection matrix. */
                vec4 position = mvpMatrix * vertexPoint;
                position = position / position.w;
                vec2 yBasis = normalize(vec2(-position.y, position.x));
                position.xy += yBasis * lineWidth * value;
                gl_Position = position;
            }
        """.trimIndent(),
        """
            precision mediump float;

            uniform vec4 color;
            uniform float opacity;

            void main() {
                /* TODO consolidate pickMode and enableTexture into a single textureMode */
                /* TODO it's confusing that pickMode must be disabled during surface shape render-to-texture */
                /* Return the RGBA color as-is. */
                gl_FragColor = color * opacity;
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    protected val mvpMatrix = Matrix4()
    protected val color = Color()
    protected var opacity = 1.0f
    protected var lineWidth = 1.0f;
    protected var mvpMatrixId = KglUniformLocation.NONE
    protected var colorId = KglUniformLocation.NONE
    protected var opacityId = KglUniformLocation.NONE
    protected var lineWidthId = KglUniformLocation.NONE;
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        mvpMatrix.transposeToArray(array, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        colorId = gl.getUniformLocation(program, "color")
        val alpha = color.alpha
        gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)
        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, opacity)
        lineWidthId = gl.getUniformLocation(program, "lineWidth");
        gl.uniform1f(lineWidthId, lineWidth)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        // Don't bother testing whether mvpMatrix has changed, the common case is to load a different matrix.
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
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

    fun loadLineWidth(lineWidth : Float)
    {
        if(this.lineWidth != lineWidth) {
            this.lineWidth = lineWidth;
            gl.uniform1f(lineWidthId, lineWidth)
        }
    }
}