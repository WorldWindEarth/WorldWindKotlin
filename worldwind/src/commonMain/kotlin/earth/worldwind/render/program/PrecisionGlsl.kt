package earth.worldwind.render.program

/**
 * Fragment-shader precision convention. Every GLES fragment shader defaults to `mediump float`
 * so colour, texture-coordinate and lighting math runs on the fp16 ALU path (twice the fp32 rate
 * on Adreno 5xx / Mali and half the register pressure). Anything that carries world-scale
 * magnitudes - camera-relative positions, view depths, shadow / sightline projections, tile
 * texture transforms - is qualified `highp` explicitly, and the shared receiver splices wrap
 * themselves in [FRAGMENT_HIGHP] / [FRAGMENT_MEDIUMP] so their internals keep fp32 regardless of
 * the including shader.
 */
object PrecisionGlsl {
    /** Switches the fragment default to highp; a no-op on ES2 parts without fragment highp. */
    const val FRAGMENT_HIGHP = "#if defined(GL_ES) && defined(GL_FRAGMENT_PRECISION_HIGH)\nprecision highp float;\n#endif"

    /** Restores the engine-wide mediump fragment default after a [FRAGMENT_HIGHP] block. */
    const val FRAGMENT_MEDIUMP = "#ifdef GL_ES\nprecision mediump float;\n#endif"
}
