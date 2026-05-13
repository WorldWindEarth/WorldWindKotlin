package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Packs a 24-bit normalized depth-buffer value into the red, green and blue channels of an
 * `RGBA8` color attachment so it can be retrieved with `glReadPixels(GL_RGBA,
 * GL_UNSIGNED_BYTE)`. WebGL1 / GLES2 do not allow reading depth-component textures with
 * `glReadPixels`, so the engine renders the pick-pass depth texture through this program
 * onto a colour attachment, then unpacks the bytes on the CPU.
 *
 * Encoding: red carries the high byte, green the middle byte, blue the low byte. The 24-bit
 * pack matches DEPTH24's source precision on WebGL2 / GLES3+ — see
 * [earth.worldwind.draw.DrawContext.ensurePickFramebuffer]. WebGL1 / GLES2 depth attachments
 * are at most 16-bit so the low byte is zero there; the unpack is identical either way.
 *
 * The vertex shader expects a unit-square triangle strip at attribute 0 (see
 * [earth.worldwind.draw.DrawContext.unitSquareBuffer]) and rasterises the full output
 * framebuffer.
 */
class DepthToColorProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        """
        attribute vec2 aPos;
        varying vec2 vUV;
        void main() {
            vUV = aPos;
            gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
        }
        """.trimIndent(),
        """
        precision highp float;

        uniform sampler2D uDepth;
        varying vec2 vUV;

        // Pack a depth value in [0, 1] into three 8-bit channels (red = high byte,
        // green = middle byte, blue = low byte). Mirror of the unpack step in
        // DrawContext.readPixelDepth: depth = r/255 + g/255^2 + b/255^3.
        vec3 packD24(float depth) {
            float scaled = depth * 255.0;
            float hi = floor(scaled);
            float remMid = (scaled - hi) * 255.0;
            float mid = floor(remMid);
            float lo = remMid - mid;                    // [0, 1), framebuffer quantizes to byte
            return vec3(hi / 255.0, mid / 255.0, lo);
        }

        void main() {
            float d = texture2D(uDepth, vUV).r;
            gl_FragColor = vec4(packD24(d), 1.0);
        }
        """.trimIndent()
    )

    override val attribBindings = arrayOf("aPos")

    private var depthLoc = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        depthLoc = gl.getUniformLocation(program, "uDepth")
        gl.uniform1i(depthLoc, 0)
    }
}
