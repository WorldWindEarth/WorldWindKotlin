package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

// TODO Try accumulating surface tile state (texCoordMatrix, texSampler), loading uniforms once, then loading a uniform
// TODO index to select the state for a surface tile. This reduces the uniform calls when many surface tiles intersect
// TODO one terrain tile.
// TODO Try class representing transform with a specific scale+translate object that can be uploaded to a GLSL vec4
class SurfaceTextureProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform bool enableTexture;
            uniform mat4 mvpMatrix;
            uniform mat3 texCoordMatrix[2];

            attribute vec4 vertexPoint;
            attribute vec2 vertexTexCoord;

            varying vec2 texCoord;
            varying vec2 tileCoord;

            void main() {
                /* Transform the vertex position by the modelview-projection matrix. */
                gl_Position = mvpMatrix * vertexPoint;

                /* Transform the vertex tex coord by the tex coord matrices. */
                if (enableTexture) {
                    vec3 texCoord3 = vec3(vertexTexCoord, 1.0);
                    texCoord = (texCoordMatrix[0] * texCoord3).st;
                    tileCoord = (texCoordMatrix[1] * texCoord3).st;
                }
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform vec4 color;
            uniform float opacity;
            uniform sampler2D texSampler;

            varying vec2 texCoord;
            varying vec2 tileCoord;

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
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "vertexTexCoord")

    val mvpMatrix = Matrix4()
    val texCoordMatrix = arrayOf(Matrix3(), Matrix3())
    private var enablePickModeId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var mvpMatrixId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
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

    companion object {
        val KEY = SurfaceTextureProgram::class
    }
}