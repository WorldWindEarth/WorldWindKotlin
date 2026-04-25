package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.util.kgl.KglUniformLocation

class DepthToColorProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(
        """
        attribute vec2 aPos;
        varying vec2 vUV;
        void main() {
            vUV = aPos;
            gl_Position = vec4(vUV * vec2(2.0, 2.0) - 1.0, 0.0, 1.0);
        }
        """.trimIndent(),
        """
        precision highp float;
        
        uniform sampler2D uDepth;
        varying vec2 vUV;
        
        vec3 packD16(float depth) {
            float r = depth;
            float g = fract(depth * 255.0);
            return vec3(r - g / 255.0, g, 0.0);
        }
        
        void main() {
            float d = texture2D(uDepth, vUV).r;
            // Using the Red and Green channels for 16-bit precision
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