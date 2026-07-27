package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Drapes a terrain tile's surface-shape texture onto arbitrary ground geometry (3D-Tile meshes).
 * The vertex shader carries the position through a world→UV matrix composed in double precision
 * on the CPU; the fragment shader samples the tile texture and discards fragments outside the
 * tile sector.
 */
class SurfaceOverlayProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform mat4 uvMatrix;

            attribute vec4 vertexPoint;

            varying vec2 texCoord;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                texCoord = (uvMatrix * vertexPoint).xy;
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #elif defined(GL_ES)
            precision mediump float;
            #endif

            uniform sampler2D texSampler;

            varying vec2 texCoord;

            void main() {
                vec2 uv = texCoord;
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) discard;
                gl_FragColor = texture2D(texSampler, uv);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvpMatrixId = KglUniformLocation.NONE
    private var uvMatrixId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        uvMatrixId = gl.getUniformLocation(program, "uvMatrix")
        gl.uniform1i(gl.getUniformLocation(program, "texSampler"), 0)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    fun loadUvMatrix(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(uvMatrixId, 1, false, array, 0)
    }
}
