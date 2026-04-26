package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

class SightlineProgram : AbstractShaderProgram() {
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
            #elif defined(GL_ES)
            precision mediump float;
            uniform mediump sampler2D depthSampler;
            #else
            uniform sampler2D depthSampler;
            #endif

            uniform float range;
            uniform vec4 color[2];

            varying vec4 sightlinePosition;
            varying float sightlineDistance;

            const vec3 minusOne = vec3(-1.0, -1.0, -1.0);
            const vec3 plusOne = vec3(1.0, 1.0, 1.0);
            /* Floor on variance to avoid 0/0 in the Chebyshev formula on perfectly flat
               regions of the moments texture (where filtering doesn't introduce variance). */
            const float minVariance = 1e-5;
            /* Light-bleeding mitigation. Chebyshev's inequality returns the *upper bound* on
               occluder visibility, so its raw output drifts toward "partially visible" along
               shadow boundaries (where filtered moments mix occluder and receiver depths,
               spiking variance). Remapping `[bleedReduce, 1] -> [0, 1]` clamps low-confidence
               visibility to fully occluded. The textbook 0.2 leaves a visible green halo at
               our blur footprint; 0.5 erases the halo at the cost of harder shadow edges. */
            const float bleedReduce = 0.5;

            void main() {
                /* Compute a mask that's on when the position is inside the occlusion projection, and off otherwise. Transform the
                   position to clip coordinates, where values between -1.0 and 1.0 are in the frustum. */
                vec3 clipCoord = sightlinePosition.xyz / sightlinePosition.w;
                vec3 clipCoordMask = step(minusOne, clipCoord) * step(clipCoord, plusOne);
                float clipMask = clipCoordMask.x * clipCoordMask.y * clipCoordMask.z;

                /* Compute a mask that's on when the position is inside the sightline's range, and off otherwise.*/
                float rangeMask = step(sightlineDistance, range);

                /* Variance Shadow Mapping. The moments texture stores (E[d], E[d^2]) per
                   pixel; bilinear filtering averages neighbouring depth and depth^2 samples
                   so a kernel of nearby occluders shows up as a non-zero variance. Chebyshev's
                   one-tailed inequality bounds the probability that a kernel sample lies
                   *behind* the receiver - i.e. an upper bound on visibility:
                       d <= M1                 -> definitely visible (visibility = 1)
                       d  > M1                 -> visibility <= variance / (variance + diff^2)
                   The smoothness comes from variance, not from a screen-space blur. */
                vec3 sightlineCoord = clipCoord * 0.5 + 0.5;
                vec4 moments = texture2D(depthSampler, sightlineCoord.xy);
                float M1 = moments.r;
                float M2 = moments.g;
                /* Linear perpendicular distance from the sightline plane, normalised to
                   [0, 1] across the sightline range. Matches the metric stored by
                   SightlineMomentsProgram (gl_Position.w / range). sightlinePosition.w
                   is -eye_z post-projection, which is exactly perpendicular distance. */
                float fragmentDepth = sightlinePosition.w / range;
                float occludeMask;
                if (fragmentDepth <= M1) {
                    occludeMask = 0.0;
                } else {
                    float variance = max(M2 - M1 * M1, minVariance);
                    float diff = fragmentDepth - M1;
                    float visibility = variance / (variance + diff * diff);
                    /* linstep(bleedReduce, 1, visibility): push small visibility values
                       (low-confidence partial visibility from kernels straddling two very
                       different depths) up to fully occluded. Without this, Chebyshev's
                       upper bound leaks light through hard occluders. */
                    visibility = clamp((visibility - bleedReduce) / (1.0 - bleedReduce), 0.0, 1.0);
                    /* Power curve: nonlinearly crushes the residual partial visibility that
                       survives linstep at the immediate shadow boundary (where filtered
                       moments produce variance large enough that visibility hovers ~0.6).
                       k = 4: 0.6 -> 0.13, 0.4 -> 0.026; values near 1 stay near 1. */
                    visibility = pow(visibility, 4.0);
                    occludeMask = 1.0 - visibility;
                }

                /* Modulate the RGBA color with the computed masks to display fragments according to the sightline's configuration. */
                gl_FragColor = mix(color[0], color[1], occludeMask) * clipMask * rangeMask;
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvpMatrixId = KglUniformLocation.NONE
    private var slpMatrixId = KglUniformLocation.NONE
    private var rangeId = KglUniformLocation.NONE
    private var depthSamplerId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
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