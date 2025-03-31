package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

open class SightlineProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform mat4 slpMatrix[2];

            attribute vec4 vertexPoint;

            varying vec4 sightlinePosition;
            varying float sightlineDistance;

            void main() {
                /* Transform the vertex position by the modelview-projection matrix. */
                gl_Position = mvpMatrix * vertexPoint;

                /* Transform the vertex position by the sightline-projection matrix. */
                vec4 sightlineEyePosition = slpMatrix[1] * vertexPoint;
                sightlinePosition = slpMatrix[0] * sightlineEyePosition;
                sightlineDistance = length(sightlineEyePosition);
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            uniform highp sampler2D depthSampler;
            #else
            precision mediump float;
            uniform mediump sampler2D depthSampler;
            #endif

            uniform float range;
            uniform vec4 color[2];

            varying vec4 sightlinePosition;
            varying float sightlineDistance;

            const vec3 minusOne = vec3(-1.0, -1.0, -1.0);
            const vec3 plusOne = vec3(1.0, 1.0, 1.0);

            void main() {
                /* Compute a mask that's on when the position is inside the occlusion projection, and off otherwise. Transform the
                   position to clip coordinates, where values between -1.0 and 1.0 are in the frustum. */
                vec3 clipCoord = sightlinePosition.xyz / sightlinePosition.w;
                vec3 clipCoordMask = step(minusOne, clipCoord) * step(clipCoord, plusOne);
                float clipMask = clipCoordMask.x * clipCoordMask.y * clipCoordMask.z;

                /* Compute a mask that's on when the position is inside the sightline's range, and off otherwise.*/
                float rangeMask = step(sightlineDistance, range);

                /* Compute a mask that's on when the object's depth is less than the sightline's depth. The depth texture contains
                   the scene's minimum depth at each position, from the sightline's point of view. */
                vec3 sightlineCoord = clipCoord * 0.5 + 0.5;
                float sightlineDepth = texture2D(depthSampler, sightlineCoord.xy).r;
                float occludeMask = step(sightlineDepth, sightlineCoord.z);

                /* Modulate the RGBA color with the computed masks to display fragments according to the sightline's configuration. */
                gl_FragColor = mix(color[0], color[1], occludeMask) * clipMask * rangeMask;
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    protected var mvpMatrixId = KglUniformLocation.NONE
    protected var slpMatrixId = KglUniformLocation.NONE
    protected var rangeId = KglUniformLocation.NONE
    protected var depthSamplerId = KglUniformLocation.NONE
    protected var colorId = KglUniformLocation.NONE
    private val array = FloatArray(32)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        slpMatrixId = gl.getUniformLocation(program, "slpMatrix")
        gl.uniformMatrix4fv(slpMatrixId, 2, false, array, 0)
        rangeId = gl.getUniformLocation(program, "range")
        gl.uniform1f(rangeId, 0f)
        colorId = gl.getUniformLocation(program, "color")
        gl.uniform4f(colorId, 1f, 1f, 1f, 1f)
        depthSamplerId = gl.getUniformLocation(program, "depthSampler")
        gl.uniform1i(depthSamplerId, 0) // GL_TEXTURE0
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    fun loadSightlineProjection(projection: Matrix4, sightline: Matrix4) {
        projection.transposeToArray(array, 0)
        sightline.transposeToArray(array, 16)
        gl.uniformMatrix4fv(slpMatrixId, 2, false, array, 0)
    }

    fun loadRange(range: Float) {
        gl.uniform1f(rangeId, range)
    }

    fun loadColor(visibleColor: Color, occludedColor: Color) {
        visibleColor.premultiplyToArray(array, 0)
        occludedColor.premultiplyToArray(array, 4)
        gl.uniform4fv(colorId, 2, array, 0)
    }

    companion object {
        val KEY = SightlineProgram::class
    }
}