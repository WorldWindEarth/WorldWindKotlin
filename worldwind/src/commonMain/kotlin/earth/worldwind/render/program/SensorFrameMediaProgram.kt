package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * [SensorFrameProgram] for one frame that carries media: the ground the sensor sees inside the
 * frame shows the media, sampled where the fragment falls in the frame, the way the sensor's
 * picture falls on the ground, cut where a ridge hides the ground exactly as the fill is (or not
 * cut, and faded at the frame's sides, see [loadMedia]). The
 * frame's fill and outline are drawn over the picture as usual. One frame per draw, in the
 * first slot; the media on texture unit [SensorFrameProgram.MEDIA_UNIT].
 *
 * Derived from the parent's sources, so the frame maths stays in one place; [SensorFrameMediaProgramOES]
 * is the same for an external texture.
 */
open class SensorFrameMediaProgram : SensorFrameProgram() {
    init {
        val src = programSources
        val main = "void main() {\n    vec4 color = vec4(0.0);"
        require(main in src[1]) { "sensor frame shader changed" }
        programSources = arrayOf(src[0], src[1].replace(main, MEDIA_GLSL + "void main() {\n    vec4 color = frameMedia(frameClip0, frameParams[0], depthCell[0]);"))
    }

    private var mediaTexCoordId = KglUniformLocation.NONE
    private var mediaOpacityId = KglUniformLocation.NONE
    private var mediaShapeId = KglUniformLocation.NONE
    private val array3 = FloatArray(9)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mediaTexCoordId = gl.getUniformLocation(program, "mediaTexCoord")
        mediaOpacityId = gl.getUniformLocation(program, "mediaOpacity")
        mediaShapeId = gl.getUniformLocation(program, "mediaShape")
        gl.uniform2f(mediaShapeId, 1f, 0f)
        gl.uniform1i(gl.getUniformLocation(program, "media"), MEDIA_UNIT)
    }

    /**
     * The media texture's coordinate transform and its opacity, 0 to 1; whether it is cut to the
     * ground the sensor sees, and the width of its soft edge as a fraction of the frame's half
     * width (0 for a hard edge).
     */
    fun loadMedia(texCoord: Matrix3, opacity: Float, clipped: Boolean = true, softEdge: Float = 0f) {
        texCoord.transposeToArray(array3, 0)
        gl.uniformMatrix3fv(mediaTexCoordId, 1, false, array3, 0)
        gl.uniform1f(mediaOpacityId, opacity)
        gl.uniform2f(mediaShapeId, if (clipped) 1f else 0f, softEdge)
    }

    companion object {
        private val MEDIA_GLSL = """
            uniform sampler2D media;
            uniform mat3 mediaTexCoord;
            uniform float mediaOpacity;
            /* x = 1 to cut the picture to the seen ground, 0 to lay it on all the ground in the frame; y = soft edge, a fraction of the half width */
            uniform vec2 mediaShape;

            /*
             * Premultiplied picture of a frame at this fragment: the media where the sensor sees
             * the ground inside the frame, vec4(0) elsewhere. Same rejection as the tint, without
             * the outline's margin.
             */
            vec4 frameMedia(HP vec4 clip, HP vec4 params, HP vec4 cell) {
                HP float ec = clip.w - max(abs(clip.x), abs(clip.y));
                HP vec2 lateral = clip.xy * params.xy;
                HP float er = params.z - sqrt(dot(lateral, lateral) + clip.w * clip.w);
                if (clip.w <= 0.0 || ec < 0.0 || er < 0.0) return vec4(0.0);
                HP vec2 ndc = clip.xy / clip.w;
                float visible = mix(1.0, frameVisibility(clip, ndc, params, cell), mediaShape.x);
                /* Faded towards the frame's sides over the soft edge's width */
                float edge = 1.0 - max(abs(ndc.x), abs(ndc.y));
                float soft = mediaShape.y > 0.0 ? clamp(edge / mediaShape.y, 0.0, 1.0) : 1.0;
                vec2 uv = (mediaTexCoord * vec3(ndc * 0.5 + 0.5, 1.0)).xy;
                return texture2D(media, uv) * (mediaOpacity * visible * soft);
            }

        """.trimIndent() + "\n"
    }
}

/**
 * [SensorFrameMediaProgram] sampling an external texture (`GL_TEXTURE_EXTERNAL_OES`, a video
 * surface on Android). The extension goes right after the version line, where GLSL ES wants it.
 */
class SensorFrameMediaProgramOES : SensorFrameMediaProgram() {
    init {
        val src = programSources
        programSources = arrayOf(src[0], src[1].replace("sampler2D media", "samplerExternalOES media"))
    }

    override fun glslVersion(dc: DrawContext): String {
        val prefix = super.glslVersion(dc)
        val extension = if (prefix.startsWith("#version 3")) {
            "#extension GL_OES_EGL_image_external_essl3 : require\n"
        } else "#extension GL_OES_EGL_image_external : require\n"
        if (!prefix.startsWith("#version")) return extension + prefix
        val line = prefix.indexOf('\n') + 1
        return prefix.substring(0, line) + extension + prefix.substring(line)
    }
}
