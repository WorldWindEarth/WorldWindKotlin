package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Terrain overlay pass that marks the ground a sensor cannot see inside its frame, hatched or
 * filled, from one of two sources:
 *  - the viewshed's hidden-ground mask ([earth.worldwind.sensor.ViewshedMask]): each terrain
 *    fragment is taken into the sensor's own polar grid, azimuth and ground distance from the
 *    sensor, and looked up there, so the area is the viewshed's, the same on every map and at
 *    every zoom;
 *  - the depth atlas ([earth.worldwind.draw.DrawableSensorFrameDepth]): the shadow-map test the
 *    seen tint uses, inverted, inside the frame, so it follows the map's drawn relief.
 *
 * The lines are diagonal, a fixed number of screen pixels apart, so they neither scale nor move
 * with the zoom; a line as wide as the spacing fills the area. Their colour is one of:
 *  - [CONTRAST_FIXED], the hatch colour;
 *  - [CONTRAST_AUTO], black over light ground and white over dark ground, at the hatch colour's
 *    opacity, chosen per pixel from the luminance of what is drawn under it: the pass reads a
 *    copy of the framebuffer ([DrawContext.sceneCopyTexture]) taken just before it draws;
 *  - [CONTRAST_TWO_TONE], a white line with a one-pixel black edge on either side, which reads on
 *    any ground without knowing what is under it: the fallback where the framebuffer cannot be
 *    copied (a multisampled one).
 *
 * Vertices arrive tile-origin-relative; [loadOrigin] gives the tile origin relative to the sensor,
 * composed in double on the CPU, so the fragment's offset from the sensor stays within float
 * precision, and [loadFrameMatrix] the frame transform with the tile origin folded in.
 */
open class SensorFrameHatchProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        """
            uniform mat4 mvpMatrix;
            uniform mat4 frameMatrix;
            uniform vec3 originRel;
            /* As SensorFrameProgram's frameBend, for the one frame */
            uniform vec4 frameBend[5];

            attribute vec4 vertexPoint;

            varying vec3 rel;
            varying vec4 frameClip;

${SensorFrameProgram.BEND_GLSL.prependIndent("            ")}

            void main() {
                gl_Position = mvpMatrix * vertexPoint;
                rel = originRel + vertexPoint.xyz;
                frameClip = frameMatrix * vertexPoint + frameDrop(frameBend[0], frameBend[1], frameBend[2], frameBend[3], frameBend[4]);
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
            /* x = source: 0 the mask, 1 the depth atlas; y = colour: 0 fixed, 1 from the ground's luminance, 2 two-tone */
            uniform vec2 mode;
            /* As SensorFrameProgram's, for the depth atlas source */
            uniform HP vec4 frameParams;
            uniform HP vec4 depthCell;
            /* Window coordinates to the scene copy's texture coordinates: scale xy, offset zw */
            uniform HP vec4 sceneTransform;
            uniform sampler2D mask;
            uniform sampler2D scene;

            varying HP vec3 rel;
            varying HP vec4 frameClip;

${SensorFrameProgram.DEPTH_GLSL.prependIndent("            ")}

            /* 1 where the viewshed's mask says the ground is hidden */
            float maskHidden() {
                HP vec3 local = vec3(dot(rel, east), dot(rel, north), dot(rel, up));
                /* Clockwise from north, folded into the grid's span from its first column */
                HP float u = mod(atan(local.x, local.y) - grid.x, 6.28318530718) / grid.y;
                HP float v = length(local.xy) / grid.z;
                float inside = step(u, 1.0) * step(v, 1.0);
                vec2 uv = vec2(maskScale.y + u * maskScale.x, maskScale.w + v * maskScale.z);
                return step(0.5, texture2D(mask, uv).r) * inside;
            }

            /* 1 inside the frame and its range where the depth atlas says nearer terrain hides the ground */
            float depthHidden() {
                HP vec4 clip = frameClip;
                if (clip.w <= 0.0 || clip.w < max(abs(clip.x), abs(clip.y))) return 0.0;
                HP vec2 lateral = clip.xy * frameParams.xy;
                if (sqrt(dot(lateral, lateral) + clip.w * clip.w) > frameParams.z) return 0.0;
                return 1.0 - frameVisibility(clip, clip.xy / clip.w, frameParams, depthCell);
            }

            void main() {
                float hidden = mode.x < 0.5 ? maskHidden() : depthHidden();
                if (hidden < 0.5) {
                    gl_FragColor = vec4(0.0);
                    return;
                }
                float d = mod(gl_FragCoord.x + gl_FragCoord.y, hatch.x);
                float core = step(d, hatch.y);
                float alpha = hatchColor.a;
                if (mode.y < 0.5) {
                    gl_FragColor = hatchColor * core;
                } else if (mode.y < 1.5) {
                    vec3 under = texture2D(scene, gl_FragCoord.xy * sceneTransform.xy + sceneTransform.zw).rgb;
                    float luminance = dot(under, vec3(0.299, 0.587, 0.114));
                    /* Darken light ground, lighten dark ground */
                    float shade = 1.0 - step(0.5, luminance);
                    gl_FragColor = vec4(vec3(shade * alpha), alpha) * core;
                } else {
                    /* A one-pixel dark edge each side of the light line, none when the line fills the area */
                    float edge = (1.0 - core) * max(step(d, hatch.y + 1.0), step(hatch.x - 1.0, d));
                    gl_FragColor = vec4(vec3(alpha), alpha) * core + vec4(0.0, 0.0, 0.0, alpha) * edge;
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    // The depth atlas source needs the same samplers as the seen tint
    override fun glslVersion(dc: DrawContext) = ShadowReceiverGlsl.glslPrefix(dc, true)

    private var mvpMatrixId = KglUniformLocation.NONE
    private var frameMatrixId = KglUniformLocation.NONE
    private var originRelId = KglUniformLocation.NONE
    private var frameBendId = KglUniformLocation.NONE
    private var eastId = KglUniformLocation.NONE
    private var northId = KglUniformLocation.NONE
    private var upId = KglUniformLocation.NONE
    private var gridId = KglUniformLocation.NONE
    private var maskScaleId = KglUniformLocation.NONE
    private var hatchColorId = KglUniformLocation.NONE
    private var hatchId = KglUniformLocation.NONE
    private var modeId = KglUniformLocation.NONE
    private var frameParamsId = KglUniformLocation.NONE
    private var depthCellId = KglUniformLocation.NONE
    private var sceneTransformId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        frameMatrixId = gl.getUniformLocation(program, "frameMatrix")
        originRelId = gl.getUniformLocation(program, "originRel")
        frameBendId = gl.getUniformLocation(program, "frameBend")
        gl.uniform4fv(frameBendId, SensorFrameProgram.BEND_VECTORS, FloatArray(SensorFrameProgram.BEND_FLOATS), 0)
        eastId = gl.getUniformLocation(program, "east")
        northId = gl.getUniformLocation(program, "north")
        upId = gl.getUniformLocation(program, "up")
        gridId = gl.getUniformLocation(program, "grid")
        maskScaleId = gl.getUniformLocation(program, "maskScale")
        hatchColorId = gl.getUniformLocation(program, "hatchColor")
        hatchId = gl.getUniformLocation(program, "hatch")
        modeId = gl.getUniformLocation(program, "mode")
        frameParamsId = gl.getUniformLocation(program, "frameParams")
        depthCellId = gl.getUniformLocation(program, "depthCell")
        sceneTransformId = gl.getUniformLocation(program, "sceneTransform")
        gl.uniform1i(gl.getUniformLocation(program, "mask"), MASK_UNIT)
        gl.uniform1i(gl.getUniformLocation(program, "scene"), SCENE_UNIT)
        gl.uniform1i(gl.getUniformLocation(program, "frameDepth"), SensorFrameProgram.DEPTH_UNIT)
    }

    fun loadModelviewProjection(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /** The frame transform with the tile origin folded in, as [SensorFrameProgram.loadFrameMatrices] takes one. */
    fun loadFrameMatrix(matrix: Matrix4) {
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(frameMatrixId, 1, false, array, 0)
    }

    /** The frame's east row, north row and clip changes, [SensorFrameProgram.BEND_FLOATS] floats at [offset], as [SensorFrameProgram.loadFrameBends] takes per frame. */
    fun loadFrameBend(bends: FloatArray, offset: Int) = gl.uniform4fv(frameBendId, SensorFrameProgram.BEND_VECTORS, bends, offset)

    /** The tile's vertex origin relative to the sensor's point, in the globe's Cartesian units. */
    fun loadOrigin(x: Float, y: Float, z: Float) = gl.uniform3f(originRelId, x, y, z)

    /**
     * The rows taking a Cartesian offset from the sensor to its east, north and up metres: nine
     * floats at [offset] in [axes]. On a 3D globe the sensor's unit axes.
     */
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

    /** The frame's params and depth atlas cell, four floats each at [offset], for the depth atlas source. */
    fun loadDepth(params: FloatArray, cells: FloatArray, offset: Int) {
        gl.uniform4f(frameParamsId, params[offset], params[offset + 1], params[offset + 2], params[offset + 3])
        gl.uniform4f(depthCellId, cells[offset], cells[offset + 1], cells[offset + 2], cells[offset + 3])
    }

    /** Premultiplied colour at [offset] in [colors], and the lines' spacing and width in pixels. */
    fun loadHatch(colors: FloatArray, offset: Int, spacing: Float, width: Float) {
        gl.uniform4f(hatchColorId, colors[offset], colors[offset + 1], colors[offset + 2], colors[offset + 3])
        gl.uniform2f(hatchId, spacing, width)
    }

    /** Where the hidden ground comes from, [fromDepth] or the mask, and how its lines are coloured, one of the CONTRAST values. */
    fun loadMode(fromDepth: Boolean, contrast: Int) = gl.uniform2f(modeId, if (fromDepth) 1f else 0f, contrast.toFloat())

    /** The scene copy's size and the window position of its lower left texel. */
    fun loadScene(x: Int, y: Int, width: Int, height: Int) =
        gl.uniform4f(sceneTransformId, 1f / width, 1f / height, -x.toFloat() / width, -y.toFloat() / height)

    companion object {
        /** Texture unit of the mask: the media unit, free once the media pass is done. */
        const val MASK_UNIT = SensorFrameProgram.MEDIA_UNIT
        /**
         * Texture unit of the scene copy: unit 0, which every program binds its own texture to
         * before it draws, since GLES 2 guarantees only eight units and 1 to 7 are taken.
         */
        const val SCENE_UNIT = 0
        const val CONTRAST_FIXED = 0
        const val CONTRAST_AUTO = 1
        const val CONTRAST_TWO_TONE = 2
    }
}
