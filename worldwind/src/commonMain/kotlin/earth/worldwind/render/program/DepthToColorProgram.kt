package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * Packs a 16-bit normalized depth-buffer value into the red and green channels of an
 * `RGBA8` color attachment so it can be retrieved with `glReadPixels(GL_RGBA,
 * GL_UNSIGNED_BYTE)`. WebGL1 / GLES2 do not allow reading depth-component textures with
 * `glReadPixels`, so the engine renders the pick-pass depth texture through this program
 * onto a colour attachment, then unpacks the bytes on the CPU.
 *
 * Encoding: red carries the upper byte of the 16-bit depth, green the lower byte. The
 * vertex shader expects a unit-square triangle strip at attribute 0 (see
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

        // Pack a depth value in [0, 1] into two 8-bit channels (red = high byte,
        // green = low byte). Mirror of the unpack step in DrawContext.readPixelDepth.
        vec3 packD16(float depth) {
            float scaled = depth * 255.0;
            float hi = floor(scaled) / 255.0;
            float lo = fract(scaled);
            return vec3(hi, lo, 0.0);
        }

        void main() {
            float d = texture2D(uDepth, vUV).r;
            gl_FragColor = vec4(packD16(d), 1.0);
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

    companion object {
        val KEY = DepthToColorProgram::class
    }
}
