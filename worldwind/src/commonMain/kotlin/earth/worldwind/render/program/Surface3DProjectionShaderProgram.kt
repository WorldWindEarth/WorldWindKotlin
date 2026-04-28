package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Surface-projection shader that drapes a source image onto terrain via a 4x4
 * camera-frustum matrix in WGS84 ECEF, evaluated per fragment. Companion to
 * [SurfaceQuadShaderProgram] (the 2D-homography path); used by the 3D path on
 * [earth.worldwind.shape.ProjectedMediaSurface] when the shape carries an
 * `imageProjection` matrix.
 *
 * Pipeline per terrain tile:
 *  * CPU pre-multiplies the world-space `imageProjection` by the tile's local-frame
 *    translation, producing `imageProjectionLocal` - a tile-local matrix that maps a
 *    terrain vertex's tile-local 3D position to image clip space. Keeps the per-vertex
 *    math in float-precise small magnitudes; world ECEF coordinates would lose
 *    sub-metre precision in single-precision float.
 *  * Vertex shader emits gl_Position via the standard `mvpMatrix` AND a perspective
 *    `imageClip` varying = `imageProjectionLocal * vec4(pointA.xyz, 1.0)`.
 *  * Fragment shader divides `imageClip.xy / imageClip.w` for perspective-correct UV
 *    in image space, applies `texCoordMatrix` (e.g. the source texture's vertical
 *    flip), samples the source texture. Discards fragments behind the camera
 *    (`imageClip.w <= 0`) and outside the unit-square image bounds (UV < 0 or > 1) so
 *    only terrain inside the camera's frustum receives the image.
 *
 * No skin-texture stage: this shader runs the source texture directly against the
 * terrain triangulation. That trades the surface-drawable's batching ability for
 * correct projection on relief-heavy terrain.
 */
open class Surface3DProjectionShaderProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform mat4 imageProjectionLocal;

            attribute vec4 pointA;

            // Image-space clip coords for this vertex. Default GLSL varying interpolation
            // is perspective-correct, which is exactly what we need for the per-fragment
            // perspective divide in the fragment stage.
            varying vec4 imageClip;

            void main() {
                gl_Position = mvpMatrix * vec4(pointA.xyz, 1.0);
                imageClip = imageProjectionLocal * vec4(pointA.xyz, 1.0);
            }
        """.trimIndent(),
        """
            #ifdef GL_ES
            precision highp float;
            #endif

            uniform mat3 texCoordMatrix;
            uniform vec4 color;
            uniform float opacity;
            // Width (in normalised UV units) of the inner soft-fade margin where the
            // projection ramps from 0 to 1 alpha. 0 = hard cutoff at the unit-square
            // boundary (default; fragment-coherent uniform branch makes this free).
            uniform float fadeMargin;
            uniform sampler2D texSampler;

            varying vec4 imageClip;

            void main() {
                // Behind the camera: never inside the captured image.
                if (imageClip.w <= 0.0) discard;

                vec2 uv = imageClip.xy / imageClip.w;
                uv = (texCoordMatrix * vec3(uv, 1.0)).xy;

                // Outside the image frustum: terrain not seen by the camera; transparent.
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) discard;

                float fade = 1.0;
                if (fadeMargin > 0.0) {
                    float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
                    fade = smoothstep(0.0, fadeMargin, edge);
                }

                gl_FragColor = color * texture2D(texSampler, uv) * (opacity * fade);
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("pointA")

    private val mvpMatrix = Matrix4()
    private val imageProjectionLocal = Matrix4()
    private val texCoordMatrix = Matrix3()
    private val color = Color()
    private var opacity = 1.0f
    private var fadeMargin = 0.0f
    private var mvpMatrixId = KglUniformLocation.NONE
    private var imageProjectionLocalId = KglUniformLocation.NONE
    private var texCoordMatrixId = KglUniformLocation.NONE
    private var colorId = KglUniformLocation.NONE
    private var opacityId = KglUniformLocation.NONE
    private var fadeMarginId = KglUniformLocation.NONE
    private var texSamplerId = KglUniformLocation.NONE

    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        mvpMatrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)

        imageProjectionLocalId = gl.getUniformLocation(program, "imageProjectionLocal")
        imageProjectionLocal.transposeToArray(array, 0)
        gl.uniformMatrix4fv(imageProjectionLocalId, 1, false, array, 0)

        texCoordMatrixId = gl.getUniformLocation(program, "texCoordMatrix")
        texCoordMatrix.transposeToArray(array, 0)
        gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)

        colorId = gl.getUniformLocation(program, "color")
        val alpha = color.alpha
        gl.uniform4f(colorId, color.red * alpha, color.green * alpha, color.blue * alpha, alpha)

        opacityId = gl.getUniformLocation(program, "opacity")
        gl.uniform1f(opacityId, opacity)

        fadeMarginId = gl.getUniformLocation(program, "fadeMargin")
        gl.uniform1f(fadeMarginId, fadeMargin)

        texSamplerId = gl.getUniformLocation(program, "texSampler")
        gl.uniform1i(texSamplerId, 0) // GL_TEXTURE0
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /**
     * Tile-local image projection: world-ECEF `imageProjection` premultiplied by a
     * translation to the current terrain tile's vertex origin. Recomputed per tile by
     * the drawable so per-vertex math stays float-precise. Skips the upload when the
     * matrix matches the previously uploaded value - common for static photos and
     * paused video that re-bind the same surface drawable every render frame.
     */
    fun loadImageProjectionLocal(matrix: Matrix4) {
        if (imageProjectionLocal != matrix) {
            imageProjectionLocal.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix4fv(imageProjectionLocalId, 1, false, array, 0)
        }
    }

    fun loadTexCoordMatrix(matrix: Matrix3) {
        if (texCoordMatrix != matrix) {
            texCoordMatrix.copy(matrix)
            matrix.transposeToArray(array, 0)
            gl.uniformMatrix3fv(texCoordMatrixId, 1, false, array, 0)
        }
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

    /**
     * Soft-edge fade margin in normalised UV units (0..0.5). 0 (default) renders a hard
     * cutoff at the unit-square boundary - the cheapest path, since the shader's
     * uniform-controlled branch then skips the fade math entirely. Non-zero values ramp
     * alpha 0..1 across the inner [0..fadeMargin] strip on each side, blending the
     * projection into surrounding terrain.
     */
    fun loadFadeMargin(fadeMargin: Float) {
        if (this.fadeMargin != fadeMargin) {
            this.fadeMargin = fadeMargin
            gl.uniform1f(fadeMarginId, fadeMargin)
        }
    }

    companion object {
        val KEY = Surface3DProjectionShaderProgram::class
    }
}
