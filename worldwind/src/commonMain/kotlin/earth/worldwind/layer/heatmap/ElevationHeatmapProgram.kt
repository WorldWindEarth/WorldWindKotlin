package earth.worldwind.layer.heatmap

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.KglUniformLocation

// TODO Try accumulating tile state (texCoordMatrix, texSampler), loading uniforms once, then loading a uniform index
// TODO to select the state for a surface tile. This reduces the uniform calls when many surface tiles intersect
// TODO one terrain tile.
// TODO Try class representing transform with a specific scale+translate object that can be uploaded to a GLSL vec4
open class ElevationHeatmapProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform float scale;
            uniform float offset;

            attribute vec4 vertexPoint;
            attribute float vertexHeight;

            varying float height;

            void main() {
                height = vertexHeight * scale + offset;
                /* Transform the vertex position by the modelview-projection matrix. */
                gl_Position = mvpMatrix * vertexPoint;
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform vec3 color0;
            uniform vec3 color1;
            uniform vec3 color2;
            uniform vec3 color3;
            uniform vec3 color4;
            uniform float opacity;

            varying float height;

            void main() {
                vec3 overlay;
                overlay = mix(color0, color1, clamp(height * 4.0, 0.0, 1.0));
                overlay = mix(overlay, color2, clamp(height * 4.0 - 1.0, 0.0, 1.0));
                overlay = mix(overlay, color3, clamp(height * 4.0 - 2.0, 0.0, 1.0));
                overlay = mix(overlay, color4, clamp(height * 4.0 - 3.0, 0.0, 1.0));
                gl_FragColor = vec4(overlay * opacity, opacity);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint", "vertexTexCoord")

    val mvpMatrix = Matrix4()
    protected var scaleId = KglUniformLocation.NONE
    protected var offsetId = KglUniformLocation.NONE
    protected var color0Id = KglUniformLocation.NONE
    protected var color1Id = KglUniformLocation.NONE
    protected var color2Id = KglUniformLocation.NONE
    protected var color3Id = KglUniformLocation.NONE
    protected var color4Id = KglUniformLocation.NONE
    protected var opacityId = KglUniformLocation.NONE
    protected var mvpMatrixId = KglUniformLocation.NONE
    private val mvpMatrixArray = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        scaleId = gl.getUniformLocation(program, "scale")
        gl.uniform1f(scaleId, 0f)
        offsetId = gl.getUniformLocation(program, "offset")
        gl.uniform1f(offsetId, 0f)
        color0Id = gl.getUniformLocation(program, "color0")
        gl.uniform3f(color0Id, 0f, 0f, 0f)
        color1Id = gl.getUniformLocation(program, "color1")
        gl.uniform3f(color1Id, 0f, 0f, 0f)
        color2Id = gl.getUniformLocation(program, "color2")
        gl.uniform3f(color2Id, 0f, 0f, 0f)
        color3Id = gl.getUniformLocation(program, "color3")
        gl.uniform3f(color3Id, 0f, 0f, 0f)
        color4Id = gl.getUniformLocation(program, "color4")
        gl.uniform3f(color4Id, 0f, 0f, 0f)
        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, 0f)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        Matrix4().transposeToArray(mvpMatrixArray, 0) // 4 x 4 identity matrix
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, mvpMatrixArray, 0)
    }

    fun setLimits(limits: FloatArray) {
        val delta = limits[1] - limits[0]
        gl.uniform1f(scaleId, 1.0f / delta)
        gl.uniform1f(offsetId, -limits[0] / delta)
    }

    fun setColors(colors: Array<Color>) {
        gl.uniform3f(color0Id, colors[0].red, colors[0].green, colors[0].blue)
        gl.uniform3f(color1Id, colors[1].red, colors[1].green, colors[1].blue)
        gl.uniform3f(color2Id, colors[2].red, colors[2].green, colors[2].blue)
        gl.uniform3f(color3Id, colors[3].red, colors[3].green, colors[3].blue)
        gl.uniform3f(color4Id, colors[4].red, colors[4].green, colors[4].blue)
    }

    fun setOpacity(opacity: Float) {
        gl.uniform1f(opacityId, opacity)
    }

    fun loadModelviewProjection() {
        mvpMatrix.transposeToArray(mvpMatrixArray, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, mvpMatrixArray, 0)
    }
}