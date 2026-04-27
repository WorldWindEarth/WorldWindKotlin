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
            /* Hamburger MSM bias parameters (Peters & Klein 2015). The moment bias mixes the
               filtered moments toward the moments of a uniform distribution on [0,1]
               `(1/2, 1/3, 1/4, 1/5)` to keep the Cholesky factorisation numerically stable
               when the raw depth distribution is rank-deficient (e.g. the all-equal d=1
               sentinel). Depth bias subtracts a tiny epsilon from the receiver's own depth so
               a surface compares as "in front of itself" - prevents self-shadow on flat
               terrain. Both tuned for 32-bit float storage. */
            const float momentBias = 3e-5;
            const float depthBias = 1e-5;

            void main() {
                /* Compute a mask that's on when the position is inside the occlusion projection, and off otherwise. Transform the
                   position to clip coordinates, where values between -1.0 and 1.0 are in the frustum. */
                vec3 clipCoord = sightlinePosition.xyz / sightlinePosition.w;
                vec3 clipCoordMask = step(minusOne, clipCoord) * step(clipCoord, plusOne);
                float clipMask = clipCoordMask.x * clipCoordMask.y * clipCoordMask.z;

                /* Compute a mask that's on when the position is inside the sightline's range, and off otherwise.*/
                float rangeMask = step(sightlineDistance, range);

                /* Moment Shadow Mapping (Hamburger 4-moment, Peters & Klein 2015). The
                   moments texture stores (E[d], E[d^2], E[d^3], E[d^4]) per pixel; bilinear
                   filtering and the separable Gaussian average those raw moments over a
                   kernel of nearby surfaces. Given those four moments the receiver finds the
                   unique three-point depth distribution matching them, then computes a tight
                   closed-form upper bound on `P(d <= z_f)`. The bound is tight at depth
                   discontinuities by construction - no light-bleed crushers needed, no
                   false-occluder midpoint problem at triangle edges. */
                vec3 sightlineCoord = clipCoord * 0.5 + 0.5;
                vec4 moments = texture2D(depthSampler, sightlineCoord.xy);
                /* Bias the moments toward the moments of a uniform distribution on [0, 1]:
                   `(E[d], E[d^2], E[d^3], E[d^4]) = (1/2, 1/3, 1/4, 1/5)`. A flat
                   `(0.5, 0.5, 0.5, 0.5)` target represents a delta function at d=0.5, which
                   is rank-deficient and makes the Cholesky factorisation singular when
                   raw moments are all-equal (e.g., the d=1 sentinel). The uniform target
                   is well-conditioned, so the Cholesky stays stable for any raw input. */
                vec4 b = mix(moments, vec4(0.5, 0.333333333, 0.25, 0.2), momentBias);
                /* Receiver depth = perpendicular distance / range. `sightlinePosition.w` is
                   `-eye_z` after the perspective projection (the projection matrix's `w` row
                   is `(0,0,-1,0)`), which is exactly what [SightlineMomentsProgram] wrote at
                   each texel - so bilinear sampling at any texture coordinate returns the
                   linearly-interpolated perpendicular depth, matching this receiver's z0
                   exactly at the corresponding surface point. Radial would have been
                   non-linear, biasing the moment values bilinear sampling produces. */
                float z0 = (sightlinePosition.w / range) - depthBias;
                /* Cholesky factorisation of the 3x3 Hankel-style matrix B = [[1, b1, b2],
                   [b1, b2, b3], [b2, b3, b4]] storing only the non-trivial entries. */
                float L32D22 = b.z - b.x * b.y;
                float D22 = b.y - b.x * b.x;
                float squaredDepthVariance = b.w - b.y * b.y;
                float D33D22 = squaredDepthVariance * D22 - L32D22 * L32D22;
                float invD22 = 1.0 / D22;
                float L32 = L32D22 * invD22;
                /* Solve L * D * L^T * c = (1, z0, z0^2)^T to obtain the coefficients of the
                   quadratic whose roots are the non-receiver depths in the reconstructed
                   3-point depth distribution. */
                vec3 c = vec3(1.0, z0, z0 * z0);
                c.y -= b.x;
                c.z -= b.y + L32 * c.y;
                c.y *= invD22;
                c.z *= D22 / D33D22;
                c.y -= L32 * c.z;
                c.x -= dot(c.yz, b.xy);
                /* Solve `c.x + c.y * z + c.z * z^2 = 0` for the two non-receiver depths. */
                float p = c.y / c.z;
                float q = c.x / c.z;
                float D = p * p * 0.25 - q;
                float r = sqrt(D);
                float z1 = -p * 0.5 - r;
                float z2 = -p * 0.5 + r;
                /* Three cases for the placement of (z0, z1, z2) on the depth axis. The Switch
                   tuple selects coefficients for the closed-form occlusion fraction. */
                vec4 sw = (z2 < z0) ? vec4(z1, z0, 1.0, 1.0)
                        : (z1 < z0) ? vec4(z0, z1, 0.0, 1.0)
                        : vec4(0.0);
                float quotient = (sw.x * z2 - b.x * (sw.x + z2) + b.y)
                               / ((z2 - sw.y) * (z0 - z1));
                float occludeMask = clamp(sw.z + sw.w * quotient, 0.0, 1.0);

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