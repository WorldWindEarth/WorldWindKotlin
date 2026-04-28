package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Surface-quad fragment shader that maps any planar quadrilateral footprint to a unit-square
 * image space via a 2D **homography** (3x3 perspective transform). The homography is the
 * inverse of the perspective projection a planar source image has on a planar ground patch,
 * and is uniquely determined by the four corner correspondences. For rectangular footprints
 * the homography reduces to an affine transform, identical to bilinear interpolation, so
 * axis-aligned [earth.worldwind.shape.ProjectedMediaSurface] usages don't change. For
 * trapezoidal footprints (as produced by a tilted drone gimbal) the homography is the
 * mathematically correct interior interpolation; bilinear gives a different, incorrect
 * result that diverges from a true perspective the further you stray from the corners.
 *
 * The matrix is computed CPU-side from the four ground corners + the unit-square image
 * corners and uploaded as the [homography] uniform per draw. The vertex shader passes the
 * geographic position to the fragment shader as a varying; the fragment stage applies the
 * homography to the interpolated geographic position and divides by `w` to land in image
 * UV space, then runs the standard `texCoordMatrix` and texture sample.
 */
open class SurfaceQuadShaderProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform bool enableTexture;

            attribute vec4 pointA;

            // Geographic position in the local frame the homography was solved against
            // (corner positions relative to vertexOrigin). Bilinear-interpolated across the
            // quad's two triangles by the rasterizer; the perspective math lives in the
            // fragment stage.
            varying vec2 P;

            void main() {
                // Transform the vertex position by the modelview-projection matrix.
                gl_Position = mvpMatrix * vec4(pointA.xyz, 1.0);

                if (enableTexture) {
                    P = pointA.xy;
                }
            }
        """.trimIndent(),
        """
            #ifdef GL_ES
            precision highp float;
            #endif

            uniform mat3 homography;
            uniform mat3 texCoordMatrix;
            uniform bool enablePickMode;
            uniform bool enableTexture;
            uniform vec4 color;
            uniform float opacity;
            uniform sampler2D texSampler;

            varying vec2 P;

            void main() {
                // Apply the ground-to-image homography to this fragment's local
                // geographic position. The third coordinate carries the perspective
                // denominator; dividing by it yields UV in [0,1]^2 across the quad's
                // interior with the perspective foreshortening a planar capture would
                // produce.
                vec3 uvh = homography * vec3(P, 1.0);
                vec2 uv = uvh.xy / uvh.z;
                uv = (texCoordMatrix * vec3(uv, 1.0)).xy;

                if (enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's Alpha component (rounded to 0.0 or 1.0). */
                    float texMask = floor(texture2D(texSampler, uv).a + 0.5);
                    gl_FragColor = color * texMask;
                } else if (!enablePickMode && enableTexture) {
                    /* Modulate the RGBA color with the 2D texture's RGBA color. */
                    gl_FragColor = color * texture2D(texSampler, uv) * opacity;
                } else {
                    /* Return the RGBA color as-is. */
                    gl_FragColor = color * opacity;
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("pointA")

    private var enablePickMode = false
    private var enableTexture = false
    private val mvpMatrix = Matrix4()
    private val texCoordMatrix = Matrix3()
    private val homography = Matrix3()
    private val color = Color()
    private var opacity = 1.0f
    private var mvpMatrixId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var enablePickModeId = KglUniformLocation.NONE
    private var enableTextureId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE
    private var homographyId = KglUniformLocation.NONE

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

        enablePickModeId = gl.getUniformLocation(program, "enablePickMode")
        gl.uniform1i(enablePickModeId, if (enablePickMode) 1 else 0)
        enableTextureId = gl.getUniformLocation(program, "enableTexture")
        gl.uniform1i(enableTextureId, if (enableTexture) 1 else 0)

        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        texCoordMatrix.transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0

        homographyId = gl.getUniformLocation(program, "homography")
        homography.transposeToArray(array, 0) // 3 x 3 identity matrix
        gl.uniformMatrix3fv(homographyId, 1, false, array, 0)
    }

    fun enablePickMode(enable: Boolean) {
        if (enablePickMode != enable) {
            enablePickMode = enable
            gl.uniform1i(enablePickModeId, if (enable) 1 else 0)
        }
    }

    fun enableTexture(enable: Boolean) {
        if (enableTexture != enable) {
            enableTexture = enable
            gl.uniform1i(enableTextureId, if (enable) 1 else 0)
        }
    }

    /**
     * Load the ground-to-image [Matrix3] for this draw. The fragment shader applies it to
     * each fragment's local (lon, lat) and divides by `w` to land in unit-square image
     * space. Skips the upload when the matrix matches what's already on the GPU - covers
     * the common case of static photos and paused video, where re-binding the same
     * surface drawable on every render frame would otherwise re-upload an unchanged value.
     */
    fun loadHomography(matrix: Matrix3) {
        if (homography != matrix) {
            homography.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix3fv(homographyId, 1, false, array, 0)
        }
    }

    fun loadTexCoordMatrix(matrix: Matrix3) {
        if (texCoordMatrix != matrix) {
            texCoordMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        }
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

    companion object {
        val KEY = SurfaceQuadShaderProgram::class
    }
}
