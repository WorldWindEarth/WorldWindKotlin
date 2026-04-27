package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Receiver shader for the omnidirectional sightline's cube-map MSM path. Samples a single
 * `samplerCube` moments texture with the fragment's centerTransform-local direction; hardware
 * picks the dominant cube face and applies seamless cross-face filtering. Replaces the 5-face
 * pass over [SightlineProgram] for the omni case - no per-face clipMask, no seam double-blend.
 *
 * Depth metric is **perpendicular** depth in each canonical-OpenGL face frame, which by
 * construction equals the dominant centerTransform-local axis projection of the fragment
 * (POS_X stores `P.x / range`, NEG_Z stores `-P.z / range`, etc.). The receiver recovers it as
 * `z0 = max(|P.x|, |P.y|, |P.z|) / range` (L∞ norm) - exactly what the cube map's dominant
 * face stored at each texel, so the bilinear blend reproduces the depth at the sample
 * direction with no aliasing. The omitted POS_Z face is masked out when the +Z component
 * dominates so the receiver never reads its uninitialised storage.
 */
class SightlineProgramCube : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            /* Tile-local vertex to centerTransform-local frame:
               sightlineLocalMatrix = inv(centerTransform) * translate(terrainOrigin). */
            uniform mat4 sightlineLocalMatrix;

            attribute vec4 vertexPoint;

            varying vec3 sightlineLocalPos;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                sightlineLocalPos = (sightlineLocalMatrix * vertexPoint).xyz;
            }
        """.trimIndent(),
        """
            #extension GL_OES_standard_derivatives : enable
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            uniform highp samplerCube depthSampler;
            #elif defined(GL_ES)
            precision mediump float;
            uniform mediump samplerCube depthSampler;
            #else
            uniform samplerCube depthSampler;
            #endif

            uniform float range;
            uniform vec4 color[2];

            varying vec3 sightlineLocalPos;

            /* Hamburger MSM bias parameters (Peters & Klein 2015). Tuned for 32-bit float
               storage; the d=1 sentinel is rank-deficient so the moments are mixed toward
               the moments of a uniform distribution on [0,1] = (1/2, 1/3, 1/4, 1/5). */
            const float momentBias = 3e-5;
            const float depthBias = 1e-5;
            /* Screen-pixel radius of the receiver-side blur. The 5-tap kernel covers ~2 *
               BLUR_RADIUS texels per axis, wide enough at 3 to dissolve cube-grid-aligned
               occluder silhouettes that otherwise read as polygonal/square shadow edges
               at high-altitude viewpoints. Per-face 2D pre-blur isn't an option for the
               cube path (it reintroduces the seam mismatch the cube sampling smooths away),
               so the smoothing is done in the receiver via dFdx/dFdy-aligned offsets. */
            const float BLUR_RADIUS = 3.0;

            void main() {
                /* Range gate. Per-fragment length() of the perspective-correctly interpolated
                   vec3 gives the true sphere boundary; a length varying interpolated at vertex
                   level produces a piecewise-linear polygon following the terrain triangulation. */
                float rangeMask = step(length(sightlineLocalPos), range);

                /* Mask out fragments whose direction is dominated by +Z (the omitted POS_Z
                   face). Sampling that face would read uninitialised texImage2D storage. */
                vec3 absLocal = abs(sightlineLocalPos);
                float upMask = 1.0 - step(max(absLocal.x, absLocal.y), sightlineLocalPos.z);

                /* 5-tap blur (centre + 4 diagonal corners). On platforms with derivatives
                   (the macro is defined when `#extension : enable` succeeds, or always on
                   desktop GLSL where they're core) the offset is screen-pixel-adaptive via
                   dFdx/dFdy. Some WebGL2 implementations refuse the directive even though
                   derivatives are core in WebGL2; those fall through to a fixed-angular basis
                   perpendicular to `sightlineLocalPos`, scaled so the angular blur is constant
                   (~0.09 degrees ~= 1 cube texel at 1024 per face). */
                #if defined(GL_OES_standard_derivatives) || !defined(GL_ES)
                vec3 ddx = dFdx(sightlineLocalPos) * BLUR_RADIUS;
                vec3 ddy = dFdy(sightlineLocalPos) * BLUR_RADIUS;
                #else
                /* Fallback offset scales with BLUR_RADIUS so tuning one knob keeps both
                   paths in sync; 0.0005 was calibrated so that BLUR_RADIUS = 3 reproduces
                   the angular blur the dFdx/dFdy path produces at typical viewpoints. */
                vec3 dnorm = normalize(sightlineLocalPos);
                vec3 helper = abs(dnorm.x) > 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 ddx = normalize(cross(dnorm, helper))
                    * (length(sightlineLocalPos) * BLUR_RADIUS * 0.0005);
                vec3 ddy = cross(dnorm, ddx);
                #endif
                vec4 moments = (textureCube(depthSampler, sightlineLocalPos)
                              + textureCube(depthSampler, sightlineLocalPos + ddx + ddy)
                              + textureCube(depthSampler, sightlineLocalPos - ddx - ddy)
                              + textureCube(depthSampler, sightlineLocalPos + ddx - ddy)
                              + textureCube(depthSampler, sightlineLocalPos - ddx + ddy)) * 0.2;

                vec4 b = mix(moments, vec4(0.5, 0.333333333, 0.25, 0.2), momentBias);
                /* Receiver depth = L_inf / range. The cube map's dominant face stored exactly
                   this scalar (its perpendicular -eye_z = the dominant axis projection in the
                   canonical-OpenGL per-face frame), so M1_sampled and z0 match at the sample
                   direction without any per-face logic. */
                float z0 = max(absLocal.x, max(absLocal.y, absLocal.z)) / range - depthBias;

                /* Cholesky-style reconstruction (identical to the 2D receiver). */
                float L32D22 = b.z - b.x * b.y;
                float D22 = b.y - b.x * b.x;
                float D33D22 = (b.w - b.y * b.y) * D22 - L32D22 * L32D22;
                float invD22 = 1.0 / D22;
                float L32 = L32D22 * invD22;
                vec3 c = vec3(1.0, z0, z0 * z0);
                c.y -= b.x;
                c.z -= b.y + L32 * c.y;
                c.y *= invD22;
                c.z *= D22 / D33D22;
                c.y -= L32 * c.z;
                c.x -= dot(c.yz, b.xy);
                float p = c.y / c.z;
                float q = c.x / c.z;
                float r = sqrt(p * p * 0.25 - q);
                float z1 = -p * 0.5 - r;
                float z2 = -p * 0.5 + r;
                vec4 sw = (z2 < z0) ? vec4(z1, z0, 1.0, 1.0)
                        : (z1 < z0) ? vec4(z0, z1, 0.0, 1.0)
                        : vec4(0.0);
                float quotient = (sw.x * z2 - b.x * (sw.x + z2) + b.y)
                               / ((z2 - sw.y) * (z0 - z1));
                float occludeMask = clamp(sw.z + sw.w * quotient, 0.0, 1.0);

                gl_FragColor = mix(color[0], color[1], occludeMask) * rangeMask * upMask;
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var mvpMatrixId = KglUniformLocation.NONE
    private var sightlineLocalMatrixId = KglUniformLocation.NONE
    private var rangeId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
        sightlineLocalMatrixId = gl.getUniformLocation(program, "sightlineLocalMatrix")
        gl.uniformMatrix4fv(sightlineLocalMatrixId, 1, false, array, 0)
        rangeId = gl.getUniformLocation(program, "range")
        gl.uniform1f(rangeId, 0f)
        colorId = gl.getUniformLocation(program, "color")
        gl.uniform4f(colorId, 1f, 1f, 1f, 1f)
        gl.uniform1i(gl.getUniformLocation(program, "depthSampler"), 0) // GL_TEXTURE0
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /**
     * Loads the per-tile transform `inv(centerTransform) * translate(terrainOrigin)`, which
     * maps a tile-local vertex to centerTransform-local frame so the cube sample direction
     * and L∞ depth are derivable per fragment without per-face state.
     */
    fun loadSightlineLocalMatrix(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(sightlineLocalMatrixId, 1, false, array, 0)
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
        val KEY = SightlineProgramCube::class
    }
}
