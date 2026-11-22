package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Terrain overlay pass that draws sensor frames on the ground: each terrain fragment is carried
 * into up to [MAX_FRAMES] sensor frames (see [earth.worldwind.sensor.frameTransform]), like a
 * shadow-map lookup with the sensor as the light, and tinted where it falls inside one. A band
 * [outline width][loadOutlineWidth] screen pixels wide along the frame's edge is drawn in the
 * frame's outline colour, the inside in its fill colour (transparent for none), so the outline
 * follows the terrain exactly at any zoom.
 *
 * A frame with a cell in the depth atlas ([earth.worldwind.draw.DrawableSensorFrameDepth]) is
 * also occluded, the way a shadow receiver is: the fragment's depth in the sensor's frame is
 * compared with the nearest terrain the sensor saw in that direction, and ground behind it gets
 * the occluded colour in place of the fill. Hardware depth-compare samplers where the platform
 * has them (bilinear 2×2 filtering for free), a raw depth tap otherwise.
 *
 * Kept cheap for weak mobile GPUs, where a fragment outside every frame is the common case:
 * it is rejected with clip-space comparisons before any division, and the outline's screen width
 * comes from a single derivative. (Measured on a PowerVR GE8320 these, with the overlay's
 * scissor, take the pass from about 39 to 14 ms in a timed run, the same as an empty shader;
 * per-tile shader variants and medium precision gained nothing there.)
 *
 * Vertices arrive tile-origin-relative; the per-tile frame matrices fold the tile origin in.
 */
class SensorFrameProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform mat4 frameMatrix[$MAX_FRAMES];

            attribute vec4 vertexPoint;

            varying vec4 frameClip0;
            varying vec4 frameClip1;
            varying vec4 frameClip2;
            varying vec4 frameClip3;

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                /* Linear in the vertex, so interpolating the clip coordinates is exact on each triangle */
                frameClip0 = frameMatrix[0] * vertexPoint;
                frameClip1 = frameMatrix[1] * vertexPoint;
                frameClip2 = frameMatrix[2] * vertexPoint;
                frameClip3 = frameMatrix[3] * vertexPoint;
            }
        """.trimIndent(),
        """
            /* HP: the frame coordinates' precision, high wherever the platform has it */
            #if defined(GL_ES) && !defined(GL_FRAGMENT_PRECISION_HIGH)
            #define HP mediump
            #else
            #define HP highp
            #endif
            #ifdef GL_ES
            precision HP float;
            #endif

            uniform int frameCount;
            uniform float outlineWidth;
            uniform vec4 outlineColor[$MAX_FRAMES];
            uniform vec4 fillColor[$MAX_FRAMES];
            uniform vec4 occludedColor[$MAX_FRAMES];
            /* x = tan(horizontal FOV / 2), y = tan(vertical FOV / 2), z = range, w = depth near plane, metres */
            uniform HP vec4 frameParams[$MAX_FRAMES];
            /* Depth atlas cell: xy = origin, z = size (texture coordinates), w = 1 when occluded */
            uniform HP vec4 depthCell[$MAX_FRAMES];
            #ifdef WW_SHADOW_SAMPLERS
            uniform highp sampler2DShadow frameDepth;
            #else
            uniform highp sampler2D frameDepth;
            #endif

            varying HP vec4 frameClip0;
            varying HP vec4 frameClip1;
            varying HP vec4 frameClip2;
            varying HP vec4 frameClip3;

            /*
             * 1 where the sensor sees this fragment, 0 where terrain nearer to the sensor hides it.
             * The bias is a world distance turned into depth units: half a metre plus two depth
             * texels' width on the ground at this distance, which absorbs the rasterisation
             * differences between the depth pass and this pass (the caster pass adds slope-scaled
             * polygon offset as well). Explicit level of detail: the lookup sits behind
             * per-fragment early-outs.
             */
            float frameVisibility(HP vec4 clip, HP vec2 ndc, HP vec4 params, HP vec4 cell) {
                if (cell.w < 0.5) return 1.0;
                HP vec2 uv = cell.xy + clamp(ndc * 0.5 + 0.5, 0.0, 1.0) * cell.z;
                HP float depth = 0.5 * clip.z / clip.w + 0.5;
                HP float texel = 2.0 * clip.w * max(params.x, params.y) / $CELL_PIXELS_FLOAT;
                HP float bias = params.w * (0.5 + 2.0 * texel) / (clip.w * clip.w);
                HP float reference = clamp(depth - bias, 0.0, 1.0);
            #ifdef WW_SHADOW_SAMPLERS
                return textureLod(frameDepth, vec3(uv, reference), 0.0);
            #else
                return step(reference, texture2D(frameDepth, uv).r);
            #endif
            }

            /* Premultiplied tint of one frame at this fragment; vec4(0) outside it. */
            vec4 frameTint(HP vec4 clip, HP vec4 params, HP vec4 cell, vec4 outline, vec4 fill, vec4 occluded) {
                /*
                 * Outside the frame, with a 5% margin for the outline, or behind the sensor: a few
                 * comparisons, no division. Fragments a quad straddling the frame's edge shares
                 * all lie within the margin, so the derivatives below still see a whole quad.
                 */
                if (clip.w <= 0.0 || abs(clip.x) > clip.w * 1.05 || abs(clip.y) > clip.w * 1.05) return vec4(0.0);
                HP vec2 ndc = clip.xy / clip.w;
                /* d: distance to the frame's nearest side in screen pixels, positive inside */
                HP float e = 1.0 - max(abs(ndc.x), abs(ndc.y));
            #ifdef WW_HAS_DERIVATIVES
                HP float d = e / max(fwidth(e), 1e-6);
            #else
                HP float d = e / 2e-3;
            #endif
                /*
                 * The range is the frame's far edge, drawn like its sides: the slant distance comes
                 * back from the frame matrix, which divided the lateral offsets by tan(FOV/2), and
                 * the metres left to the range turn into screen pixels the same way as e.
                 */
                HP vec2 lateral = clip.xy * params.xy;
                HP float er = params.z - sqrt(dot(lateral, lateral) + clip.w * clip.w);
            #ifdef WW_HAS_DERIVATIVES
                d = min(d, er / max(fwidth(er), 1e-6));
            #else
                d = min(d, er / 2.0);
            #endif
                if (d < -0.5) return vec4(0.0);
                float visible = frameVisibility(clip, ndc, params, cell);
                float inside = clamp(d + 0.5, 0.0, 1.0);
                float line = inside * clamp(outlineWidth + 0.5 - d, 0.0, 1.0);
                /* The outline is the frame itself, drawn whether or not the sensor sees the ground there */
                return outline * line + mix(occluded, fill, visible) * inside * (1.0 - line);
            }

            void main() {
                vec4 color = vec4(0.0);
                vec4 t;
                if (frameCount > 0) { t = frameTint(frameClip0, frameParams[0], depthCell[0], outlineColor[0], fillColor[0], occludedColor[0]); color = t + color * (1.0 - t.a); }
                if (frameCount > 1) { t = frameTint(frameClip1, frameParams[1], depthCell[1], outlineColor[1], fillColor[1], occludedColor[1]); color = t + color * (1.0 - t.a); }
                if (frameCount > 2) { t = frameTint(frameClip2, frameParams[2], depthCell[2], outlineColor[2], fillColor[2], occludedColor[2]); color = t + color * (1.0 - t.a); }
                if (frameCount > 3) { t = frameTint(frameClip3, frameParams[3], depthCell[3], outlineColor[3], fillColor[3], occludedColor[3]); color = t + color * (1.0 - t.a); }
                gl_FragColor = color;
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    // As the shadow receivers: modern GLSL with depth-compare samplers where available
    override fun glslVersion(dc: DrawContext) = ShadowReceiverGlsl.glslPrefix(dc, true)

    private var mvpMatrixId = KglUniformLocation.NONE
    private var frameMatrixId = KglUniformLocation.NONE
    private var frameCountId = KglUniformLocation.NONE
    private var outlineWidthId = KglUniformLocation.NONE
    private var outlineColorId = KglUniformLocation.NONE
    private var fillColorId = KglUniformLocation.NONE
    private var occludedColorId = KglUniformLocation.NONE
    private var frameParamsId = KglUniformLocation.NONE
    private var depthCellId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        frameMatrixId = gl.getUniformLocation(program, "frameMatrix")
        frameCountId = gl.getUniformLocation(program, "frameCount")
        outlineWidthId = gl.getUniformLocation(program, "outlineWidth")
        outlineColorId = gl.getUniformLocation(program, "outlineColor")
        fillColorId = gl.getUniformLocation(program, "fillColor")
        occludedColorId = gl.getUniformLocation(program, "occludedColor")
        frameParamsId = gl.getUniformLocation(program, "frameParams")
        depthCellId = gl.getUniformLocation(program, "depthCell")
        gl.uniform1i(gl.getUniformLocation(program, "frameDepth"), DEPTH_UNIT)
        gl.uniform1i(frameCountId, 0)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /** [matrices] holds [MAX_FRAMES] column-major matrices, 16 floats each. */
    fun loadFrameMatrices(matrices: FloatArray) = gl.uniformMatrix4fv(frameMatrixId, MAX_FRAMES, false, matrices, 0)

    /** Colours premultiplied; params and depth cells as in the shader; 4 floats per frame each. */
    fun loadFrames(
        count: Int, outlineColors: FloatArray, fillColors: FloatArray, occludedColors: FloatArray,
        params: FloatArray, depthCells: FloatArray,
    ) {
        gl.uniform1i(frameCountId, count)
        gl.uniform4fv(outlineColorId, MAX_FRAMES, outlineColors, 0)
        gl.uniform4fv(fillColorId, MAX_FRAMES, fillColors, 0)
        gl.uniform4fv(occludedColorId, MAX_FRAMES, occludedColors, 0)
        gl.uniform4fv(frameParamsId, MAX_FRAMES, params, 0)
        gl.uniform4fv(depthCellId, MAX_FRAMES, depthCells, 0)
    }

    fun loadOutlineWidth(pixels: Float) = gl.uniform1f(outlineWidthId, pixels)

    companion object {
        /** Frames per draw; four keeps the varyings and uniforms within GLES 2's minimums. */
        const val MAX_FRAMES = 4
        /**
         * Texture unit of the depth atlas: clear of the shadow cascades (1-4) and the sightline
         * cube (5); the pass parks a plain texture back on it when done.
         */
        const val DEPTH_UNIT = 6
        private const val CELL_PIXELS = earth.worldwind.draw.DrawContext.SENSOR_FRAME_DEPTH_CELL
        internal val CELL_PIXELS_FLOAT = "$CELL_PIXELS.0"
    }
}
