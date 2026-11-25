package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Terrain overlay pass that hatches the ground a sensor cannot see: each terrain fragment is
 * taken into the sensor's own polar grid, azimuth and ground distance from the sensor, and
 * looked up in the viewshed's hidden-ground mask ([earth.worldwind.sensor.ViewshedMask]); where
 * the mask says hidden, diagonal lines a fixed number of screen pixels apart are drawn in the
 * hatch colour. The lines live in screen space, so they neither scale nor move with the zoom,
 * and the hatched area is the viewshed's, the same on every map, not the depth pass's.
 *
 * Vertices arrive tile-origin-relative; [loadOrigin] gives the tile origin relative to the sensor,
 * composed in double on the CPU, so the fragment's offset from the sensor stays within float precision.
 */
open class SensorFrameHatchProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform vec3 originRel;

            attribute vec4 vertexPoint;

            varying vec3 rel;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                rel = originRel + vertexPoint.xyz;
            }
        """.trimIndent(),
        """
            #if defined(GL_ES) && !defined(GL_FRAGMENT_PRECISION_HIGH)
            #define HP mediump
            #else
            #define HP highp
            #endif
            #ifdef GL_ES
            precision HP float;
            #endif

            uniform vec3 east;
            uniform vec3 north;
            uniform vec3 up;
            /* x = the first column's azimuth, y = span of all columns (radians), z = reach of all bins (metres) */
            uniform HP vec3 grid;
            /* The mask's texel scale and offset across the columns (x, y) and the bins (z, w) */
            uniform vec4 maskScale;
            /* Premultiplied */
            uniform vec4 hatchColor;
            /* x = spacing, y = line width, screen pixels */
            uniform vec2 hatch;
            uniform sampler2D mask;

            varying HP vec3 rel;

            void main() {
                HP vec3 local = vec3(dot(rel, east), dot(rel, north), dot(rel, up));
                /* Clockwise from north, folded into the grid's span from its first column */
                HP float u = mod(atan(local.x, local.y) - grid.x, 6.28318530718) / grid.y;
                HP float v = length(local.xy) / grid.z;
                float inside = step(u, 1.0) * step(v, 1.0);
                vec2 uv = vec2(maskScale.y + u * maskScale.x, maskScale.w + v * maskScale.z);
                float hidden = step(0.5, texture2D(mask, uv).r) * inside;
                float line = step(mod(gl_FragCoord.x + gl_FragCoord.y, hatch.x), hatch.y);
                gl_FragColor = hatchColor * (hidden * line);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvpMatrixId = KglUniformLocation.NONE
    private var originRelId = KglUniformLocation.NONE
    private var eastId = KglUniformLocation.NONE
    private var northId = KglUniformLocation.NONE
    private var upId = KglUniformLocation.NONE
    private var gridId = KglUniformLocation.NONE
    private var maskScaleId = KglUniformLocation.NONE
    private var hatchColorId = KglUniformLocation.NONE
    private var hatchId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        originRelId = gl.getUniformLocation(program, "originRel")
        eastId = gl.getUniformLocation(program, "east")
        northId = gl.getUniformLocation(program, "north")
        upId = gl.getUniformLocation(program, "up")
        gridId = gl.getUniformLocation(program, "grid")
        maskScaleId = gl.getUniformLocation(program, "maskScale")
        hatchColorId = gl.getUniformLocation(program, "hatchColor")
        hatchId = gl.getUniformLocation(program, "hatch")
        gl.uniform1i(gl.getUniformLocation(program, "mask"), MASK_UNIT)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /** The tile's vertex origin relative to the sensor's point, metres. */
    fun loadOrigin(x: Float, y: Float, z: Float) = gl.uniform3f(originRelId, x, y, z)

    /** The sensor's east, north and up axes: nine floats at [offset] in [axes]. */
    fun loadAxes(axes: FloatArray, offset: Int) {
        gl.uniform3f(eastId, axes[offset], axes[offset + 1], axes[offset + 2])
        gl.uniform3f(northId, axes[offset + 3], axes[offset + 4], axes[offset + 5])
        gl.uniform3f(upId, axes[offset + 6], axes[offset + 7], axes[offset + 8])
    }

    /** The mask's grid: first azimuth and span in radians, reach in metres, and its texel scale and offset per axis. */
    fun loadGrid(startAzimuth: Float, span: Float, reach: Float, columns: Int, bins: Int, width: Int, height: Int) {
        gl.uniform3f(gridId, startAzimuth, span, reach)
        gl.uniform4f(maskScaleId, columns.toFloat() / width, 1f / width, bins.toFloat() / height, 1f / height)
    }

    /** Premultiplied colour at [offset] in [colors], and the lines' spacing and width in pixels. */
    fun loadHatch(colors: FloatArray, offset: Int, spacing: Float, width: Float) {
        gl.uniform4f(hatchColorId, colors[offset], colors[offset + 1], colors[offset + 2], colors[offset + 3])
        gl.uniform2f(hatchId, spacing, width)
    }

    companion object {
        /** Texture unit of the mask: the media unit, free once the media pass is done. */
        const val MASK_UNIT = SensorFrameProgram.MEDIA_UNIT
    }
}
