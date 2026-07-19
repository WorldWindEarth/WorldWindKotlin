package earth.worldwind.layer.ogc3d.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.GL_ALIASED_POINT_SIZE_RANGE
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Colour-pass shader for 3D Tiles Gaussian-splat content. Renders one `GL_POINTS` per splat
 * with an anisotropic, screen-space-projected covariance footprint and a Gaussian falloff in
 * the fragment — the standard 3DGS "EWA splat" rasterizer pipeline.
 *
 * Per-splat math: the rotation quaternion + 3-axis scale define a tile-local 3D Gaussian
 * with covariance `Σ_local = R · diag(s)² · Rᵀ`. The vertex shader transforms `R` through
 * the modelview's 3×3 (giving eye-space covariance `Σ_eye = T·Tᵀ`, `T = W·R·diag(s)`), then
 * projects through the perspective Jacobian
 * `J = [[f/z, 0, -f·x/z²], [0, f/z, -f·y/z²]]` to a screen-space 2×2 covariance
 * `Σ_2d = J · Σ_eye · Jᵀ`. The standard 3DGS `+0.3 px` low-pass anti-alias term is added
 * to the diagonal. The point radius is alpha-aware — `r = σ·√(2·ln(α/minAlpha))`, ~3σ for
 * opaque splats, shrinking for faint ones — so fragments the discard threshold would kill
 * are never rasterized. The inverted 2D conic, pre-scaled by the post-clamp `gl_PointSize`
 * squared (driver-bounded by `GL_ALIASED_POINT_SIZE_RANGE`), is passed as a mediump varying;
 * the fragment stage runs fully in fp16 and uses `exp(-½·dᵀ·Σ⁻¹·d)` on `gl_PointCoord`
 * offsets directly.
 *
 * Deliberate scope limits:
 *  - **Per-splat solid colour**: reads RGBA straight from the codec output; no SH evaluation.
 *    The codec preserves higher-band SH coefficients on
 *    `earth.worldwind.layer.ogc3d.content.GaussianContent.sphericalHarmonics` for a
 *    view-dependent path on top of this one.
 *  - **No shadow / sightline receivers**: a typical Gaussian scene is self-shadowed; routing
 *    the GL_POINTS path through cascade receivers adds noise without much fidelity gain.
 *
 * Caller must enable premultiplied-alpha blending (`GL_ONE`, `GL_ONE_MINUS_SRC_ALPHA`) before
 * draw — the fragment shader emits `(rgb * a * falloff, a * falloff)` so back-to-front sorted
 * splats accumulate correctly without over-darkening overlaps.
 */
class Ogc3dTilesGaussianProgram : AbstractShaderProgram() {

    override var programSources: Array<String> = arrayOf(VERTEX_SHADER, FRAGMENT_SHADER)

    override val attribBindings: Array<String> = arrayOf(
        "vertexPosition",
        "vertexScale",
        "vertexColor",
        "vertexRotation",
    )

    private var mvMatrixId = KglUniformLocation.NONE
    private var projMatrixId = KglUniformLocation.NONE
    private var focalLengthPixelsId = KglUniformLocation.NONE
    private var splatSizeMultiplierId = KglUniformLocation.NONE
    private var maxPointSizeId = KglUniformLocation.NONE
    private var minAlphaId = KglUniformLocation.NONE
    private var qPosCenterId = KglUniformLocation.NONE
    private var qPosHalfRangeId = KglUniformLocation.NONE
    private var pickModeId = KglUniformLocation.NONE
    private var pickColorId = KglUniformLocation.NONE

    private val mvMatrix = Matrix4()
    private val projMatrix = Matrix4()
    private val matrixArray = FloatArray(16)
    private var focalLengthPixels = 1f
    private var splatSizeMultiplier = 1f
    private var maxPointSize = 256f
    private var minAlpha = 0.01f
    private var qPosCenterX = 0f; private var qPosCenterY = 0f; private var qPosCenterZ = 0f
    private var qPosHalfRangeX = 0f; private var qPosHalfRangeY = 0f; private var qPosHalfRangeZ = 0f
    private var pickMode = false
    private var pickColorR = 0f; private var pickColorG = 0f; private var pickColorB = 0f; private var pickColorA = 1f

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvMatrixId = gl.getUniformLocation(program, "mvMatrix")
        mvMatrix.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(mvMatrixId, 1, false, matrixArray, 0)
        projMatrixId = gl.getUniformLocation(program, "projMatrix")
        projMatrix.transposeToArray(matrixArray, 0)
        gl.uniformMatrix4fv(projMatrixId, 1, false, matrixArray, 0)
        focalLengthPixelsId = gl.getUniformLocation(program, "focalLengthPixels")
        gl.uniform1f(focalLengthPixelsId, focalLengthPixels)
        splatSizeMultiplierId = gl.getUniformLocation(program, "splatSizeMultiplier")
        gl.uniform1f(splatSizeMultiplierId, splatSizeMultiplier)
        // Driver-reported aliased point-size ceiling. Adreno 740 reports ~1023; Mali / desktop
        // drivers vary. 256 is a conservative GLES2 baseline if the query returns garbage.
        val range = dc.gl.getParameterfv(GL_ALIASED_POINT_SIZE_RANGE)
        maxPointSize = if (range.size >= 2 && range[1].isFinite() && range[1] > 256f) range[1] else 256f
        maxPointSizeId = gl.getUniformLocation(program, "maxPointSize")
        gl.uniform1f(maxPointSizeId, maxPointSize)
        minAlphaId = gl.getUniformLocation(program, "minAlpha")
        gl.uniform1f(minAlphaId, minAlpha)
        qPosCenterId = gl.getUniformLocation(program, "qPosCenter")
        gl.uniform3f(qPosCenterId, qPosCenterX, qPosCenterY, qPosCenterZ)
        qPosHalfRangeId = gl.getUniformLocation(program, "qPosHalfRange")
        gl.uniform3f(qPosHalfRangeId, qPosHalfRangeX, qPosHalfRangeY, qPosHalfRangeZ)
        pickModeId = gl.getUniformLocation(program, "pickMode")
        gl.uniform1i(pickModeId, if (pickMode) 1 else 0)
        pickColorId = gl.getUniformLocation(program, "pickColor")
        gl.uniform4f(pickColorId, pickColorR, pickColorG, pickColorB, pickColorA)
    }

    /** Per-tile position dequant. Vertex shader computes
     *  `modelPos = vertexPosition * halfRange + center`, where vertexPosition is the GPU-
     *  normalized signed int16 in [-1, +1]. center = (min+max)/2, halfRange = (max-min)/2
     *  along each axis of the tile's bounding box. */
    fun loadPositionDequant(
        centerX: Float, centerY: Float, centerZ: Float,
        halfRangeX: Float, halfRangeY: Float, halfRangeZ: Float,
    ) {
        if (qPosCenterX != centerX || qPosCenterY != centerY || qPosCenterZ != centerZ) {
            qPosCenterX = centerX; qPosCenterY = centerY; qPosCenterZ = centerZ
            gl.uniform3f(qPosCenterId, centerX, centerY, centerZ)
        }
        if (qPosHalfRangeX != halfRangeX || qPosHalfRangeY != halfRangeY || qPosHalfRangeZ != halfRangeZ) {
            qPosHalfRangeX = halfRangeX; qPosHalfRangeY = halfRangeY; qPosHalfRangeZ = halfRangeZ
            gl.uniform3f(qPosHalfRangeId, halfRangeX, halfRangeY, halfRangeZ)
        }
    }

    /** Tile-local → eye-space (modelview * tileToWorld). */
    fun loadModelview(matrix: Matrix4) {
        if (mvMatrix != matrix) {
            mvMatrix.copy(matrix)
            matrix.transposeToArray(matrixArray, 0)
            gl.uniformMatrix4fv(mvMatrixId, 1, false, matrixArray, 0)
        }
    }

    /** Eye-space → clip-space. */
    fun loadProjection(matrix: Matrix4) {
        if (projMatrix != matrix) {
            projMatrix.copy(matrix)
            matrix.transposeToArray(matrixArray, 0)
            gl.uniformMatrix4fv(projMatrixId, 1, false, matrixArray, 0)
        }
    }

    /** Focal length in pixels = `viewportHeight / (2 * tan(fov / 2))`. The vertex shader
     *  uses this to convert metric splat scale to projected `gl_PointSize`. */
    fun loadFocalLengthPixels(value: Float) {
        if (focalLengthPixels != value) {
            focalLengthPixels = value
            gl.uniform1f(focalLengthPixelsId, value)
        }
    }

    /** Global multiplier applied on top of the per-splat projected size. Defaults to 1.0;
     *  callers tune up/down to control oversplatting density (higher = fluffier blend, lower
     *  = crisper edges with visible point gaps). */
    fun loadSplatSizeMultiplier(value: Float) {
        if (splatSizeMultiplier != value) {
            splatSizeMultiplier = value
            gl.uniform1f(splatSizeMultiplierId, value)
        }
    }

    /** Fragments whose Gaussian-weighted alpha falls below this threshold are discarded — a
     *  fillrate optimisation that skips negligible contributions instead of drawing
     *  them. Defaults to 0.01. */
    fun loadMinAlpha(value: Float) {
        if (minAlpha != value) {
            minAlpha = value
            gl.uniform1f(minAlphaId, value)
        }
    }

    /** When `true`, the fragment discards the Gaussian fringe (α<0.5) so depth writes only
     *  on the splat core. Pick depth-readback then unprojects to the splat surface (e.g. a
     *  building roof) instead of the terrain behind it. */
    fun loadPickMode(value: Boolean) {
        if (pickMode != value) {
            pickMode = value
            gl.uniform1i(pickModeId, if (value) 1 else 0)
        }
    }

    /** Unique pick-ID RGBA emitted on the splat core when [loadPickMode] is `true`. */
    fun loadPickColor(color: Color) {
        if (pickColorR != color.red || pickColorG != color.green ||
            pickColorB != color.blue || pickColorA != color.alpha) {
            pickColorR = color.red; pickColorG = color.green
            pickColorB = color.blue; pickColorA = color.alpha
            gl.uniform4f(pickColorId, pickColorR, pickColorG, pickColorB, pickColorA)
        }
    }

    companion object {
        fun get(rc: RenderContext): Ogc3dTilesGaussianProgram =
            rc.getShaderProgram { Ogc3dTilesGaussianProgram() }

        /** Hard vertex-cull alpha floor, interpolated into the shader as the minimum
         *  `cutAlpha`. Chosen just above 1/255 (~0.00392) so a splat whose uint8 alpha is 1
         *  sits at-or-below the floor — which makes decode-time pruning of alpha-byte <= 1
         *  splats (`GaussianContent.PRUNED_ALPHA_MAX_BYTE`, derived from this constant)
         *  pixel-identical for ANY runtime `minAlpha` setting. */
        internal const val HARD_CULL_ALPHA = 0.004f

        private val VERTEX_SHADER: String = """
            uniform mat4 mvMatrix;
            uniform mat4 projMatrix;
            uniform float focalLengthPixels;
            uniform float splatSizeMultiplier;
            uniform float maxPointSize;
            /* Per-tile position dequant: GPU sees vertexPosition as normalized signed int16
               in [-1, +1] per axis; we map back to tile-local model coords via
               modelPos = vertexPosition * qPosHalfRange + qPosCenter. */
            uniform vec3 qPosCenter;
            uniform vec3 qPosHalfRange;
            /* Shared with the fragment stage — precision must match there (mediump). */
            uniform mediump float minAlpha;

            attribute vec3 vertexPosition;
            attribute vec3 vertexScale;
            attribute vec4 vertexColor;
            attribute vec4 vertexRotation;

            /* lowp matches the 8-bit normalized source attribute. */
            varying lowp vec4 splatColor;
            /* xyz = inverse 2D screen-space covariance, pre-scaled by the post-clamp point size
               squared so the fragment evaluates it directly on gl_PointCoord offsets in fp16.
               w   = post-clamp gl_PointSize (<= 0 flags a culled splat). */
            varying mediump vec4 conicAndSize;

            void main() {
                vec3 modelPos = vertexPosition * qPosHalfRange + qPosCenter;
                vec4 eyePos4 = mvMatrix * vec4(modelPos, 1.0);
                /* Discard splats behind the near plane to avoid the Jacobian's 1/z blowing up. */
                if (eyePos4.z >= 0.0) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    splatColor = vec4(0.0);
                    conicAndSize = vec4(1.0, 0.0, 1.0, 0.0);
                    gl_PointSize = 1.0;
                    return;
                }
                gl_Position = projMatrix * eyePos4;

                /* Quaternion (x, y, z, w) -> R * diag(scale) directly, no mat3 R intermediate. */
                float qx = vertexRotation.x;
                float qy = vertexRotation.y;
                float qz = vertexRotation.z;
                float qw = vertexRotation.w;
                float xx = qx * qx, yy = qy * qy, zz = qz * qz;
                float xy = qx * qy, xz = qx * qz, yz = qy * qz;
                float wx = qw * qx, wy = qw * qy, wz = qw * qz;
                mat3 RS = mat3(
                    vec3(1.0 - 2.0 * (yy + zz), 2.0 * (xy + wz),       2.0 * (xz - wy)) * vertexScale.x,
                    vec3(2.0 * (xy - wz),       1.0 - 2.0 * (xx + zz), 2.0 * (yz + wx)) * vertexScale.y,
                    vec3(2.0 * (xz + wy),       2.0 * (yz - wx),       1.0 - 2.0 * (xx + yy)) * vertexScale.z
                );

                mat3 W = mat3(mvMatrix);
                mat3 T = W * RS;

                /* Eye-space covariance Sigma_eye = T * T^T. Sigma_{i,j} = sum_k T_{i,k} * T_{j,k} =
                   dot(row_i, row_j). GLSL mat3 is column-major (T[i] is a column), so
                   `dot(T[i], T[j])` would compute T^T*T -- for any rotation T that's S^2, which
                   makes every splat a fixed axis-aligned ellipsoid in eye space and the splat
                   orientation no longer tracks the camera. Extract rows explicitly. */
                vec3 row0 = vec3(T[0][0], T[1][0], T[2][0]);
                vec3 row1 = vec3(T[0][1], T[1][1], T[2][1]);
                vec3 row2 = vec3(T[0][2], T[1][2], T[2][2]);
                float s00 = dot(row0, row0);
                float s01 = dot(row0, row1);
                float s02 = dot(row0, row2);
                float s11 = dot(row1, row1);
                float s12 = dot(row1, row2);
                float s22 = dot(row2, row2);

                /* Jacobian J of the perspective projection at eye position. WorldWind has
                   the camera looking down -Z, so use z = -eyePos.z as positive depth. */
                float z = -eyePos4.z;
                float invZ = 1.0 / z;
                float fInvZ = focalLengthPixels * invZ;
                float jx = -fInvZ * eyePos4.x * invZ;
                float jy = -fInvZ * eyePos4.y * invZ;
                /* Sigma_2d = J * Sigma_eye * J^T. Symbolically expanded for the 2x3 Jacobian.
                   row0 = (fInvZ, 0, jx); row1 = (0, fInvZ, jy). */
                float a = fInvZ * fInvZ * s00 + 2.0 * fInvZ * jx * s02 + jx * jx * s22;
                float b = fInvZ * fInvZ * s01 + fInvZ * jx * s12 + fInvZ * jy * s02 + jx * jy * s22;
                float c = fInvZ * fInvZ * s11 + 2.0 * fInvZ * jy * s12 + jy * jy * s22;

                /* Low-pass anti-alias offset on the diagonal (3DGS standard). Ensures the
                   covariance is invertible even for splats projecting smaller than a pixel. */
                a += 0.3;
                c += 0.3;

                float det = a * c - b * b;
                if (det <= 0.0) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    splatColor = vec4(0.0);
                    conicAndSize = vec4(1.0, 0.0, 1.0, 0.0);
                    gl_PointSize = 1.0;
                    return;
                }
                float invDet = 1.0 / det;

                /* Bounding pixel radius from the max eigenvalue of Sigma_2d.
                   lambda_{max/min} = (a+c)/2 +/- sqrt(((a-c)/2)^2 + b^2). */
                float mid = (a + c) * 0.5;
                float disc = sqrt(max(mid * mid - det, 0.0));
                float lambdaMax = mid + disc;
                /* Alpha-aware cutoff instead of a flat 3sigma: rasterize only out to where this
                   splat's tail crosses the fragment discard threshold, r = sigma*sqrt(2*ln(a/minAlpha)).
                   Opaque splats keep ~3sigma; faint ones shrink, cutting blended fill that the
                   fragment would discard anyway. Splats already below the threshold cull here. */
                float cutAlpha = max(minAlpha, $HARD_CULL_ALPHA);
                float tail = 2.0 * log(max(vertexColor.a, cutAlpha) / cutAlpha);
                if (tail <= 0.0) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    splatColor = vec4(0.0);
                    conicAndSize = vec4(1.0, 0.0, 1.0, 0.0);
                    gl_PointSize = 1.0;
                    return;
                }
                float radiusPixels = sqrt(tail * lambdaMax);
                float splatPointSize = clamp(2.0 * radiusPixels * splatSizeMultiplier, 1.0, maxPointSize);
                gl_PointSize = splatPointSize;

                /* Sigma^-1 off-diagonal is -b/det; storing +b/det absorbs the fragment's
                   gl_PointCoord Y-flip (d.y -> -d.y) into this sign. The clamped point size is
                   folded in (conic *= size^2) so the fragment works on gl_PointCoord-range
                   values and can run entirely in mediump (fp16) on mobile GPUs. */
                vec3 conicPx = vec3(c, b, a) * (invDet * splatPointSize * splatPointSize);
                /* Uniform rescale keeps Sigma^-1 positive-definite while bounding components to
                   the fp16 range; only near-degenerate size-clamped splats are affected. */
                float conicMax = max(abs(conicPx.x), max(abs(conicPx.y), abs(conicPx.z)));
                conicPx *= min(1.0, 60000.0 / max(conicMax, 1.0e-6));
                conicAndSize = vec4(conicPx, splatPointSize);
                splatColor = vertexColor;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER: String = """
            /* Whole stage fits fp16 — the vertex shader pre-scales the conic to gl_PointCoord
               range, and mediump runs at twice the fp32 ALU rate on mobile GPUs. */
            #ifdef GL_ES
            precision mediump float;
            #endif

            uniform mediump float minAlpha;
            uniform bool pickMode;
            uniform vec4 pickColor;

            varying lowp vec4 splatColor;
            varying mediump vec4 conicAndSize;

            void main() {
                if (conicAndSize.w <= 0.0) discard;

                /* gl_PointCoord stays Y-down; vertex shader pre-flipped conicAndSize.y. */
                vec2 d = gl_PointCoord - vec2(0.5);

                /* Mahalanobis distance squared d^T * (size^2 * Sigma^-1) * d. The d-products are
                   computed first so every intermediate stays inside the fp16 range. */
                float mahal = dot(vec3(d.x * d.x, 2.0 * d.x * d.y, d.y * d.y), conicAndSize.xyz);
                float falloff = exp(-0.5 * mahal);

                float a = splatColor.a * falloff;

                if (pickMode) {
                    /* Halo discarded so depth-write lands on the splat core. Pick-color
                       output relies on alpha=1 + GL_ONE/GL_ONE_MINUS_SRC_ALPHA collapsing
                       to src-replace, so the framebuffer holds the exact pick ID. */
                    if (a < 0.5) discard;
                    gl_FragColor = pickColor;
                    return;
                }

                if (a < minAlpha) discard;

                /* Premultiplied output. Caller must set blendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                   so back-to-front-sorted splats accumulate as standard over-compositing. */
                gl_FragColor = vec4(splatColor.rgb * a, a);
            }
        """.trimIndent()
    }
}
