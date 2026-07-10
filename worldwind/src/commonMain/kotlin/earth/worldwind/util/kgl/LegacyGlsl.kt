package earth.worldwind.util.kgl

/**
 * Rewrites GLSL ES 1.00 syntax in-place so it compiles under `#version 300 es` — run by
 * Android / iOS `shaderSource` when a program opts into [Kgl.glslVersion3]. Same mapping as
 * `JoglKgl.translateLegacyGlsl` / `JsWebKgl.translateLegacyGlsl` — keep them in sync;
 * idempotent for already-modern sources. Fragments get a `precision mediump` default
 * injected (ES 3.00 has none); a shader's own later `precision` block still overrides it.
 */
fun translateLegacyGlslToGles3(source: String, isVertex: Boolean): String {
    var s = source
        .replace("attribute ", "in ")
        // Order matters: `texture2DProj(` must rewrite before `texture2D(` so the longer
        // legacy name routes to the modern projective overload, not to plain `texture(`.
        .replace("texture2DProj(", "textureProj(")
        .replace("textureCubeProj(", "textureProj(")
        .replace("texture2D(", "texture(")
        .replace("textureCube(", "texture(")
        // Strip `#extension` directives for features promoted to core in ES 3.00 — the
        // directive would draw an "extension is not supported" error/warning. Whole-line match.
        .replace(Regex("^\\s*#extension\\s+GL_OES_standard_derivatives.*(?:\\r?\\n)?", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*#extension\\s+GL_EXT_frag_depth.*(?:\\r?\\n)?", RegexOption.MULTILINE), "")
    s = if (isVertex) s.replace("varying ", "out ") else s.replace("varying ", "in ")
    if (!isVertex) {
        // Skip the version line and any leading #extension / blank lines — the preamble must
        // sit after them (ES 3.00 requires extensions before any non-extension token).
        var insertAt = s.indexOf('\n') + 1
        while (insertAt < s.length) {
            val lineEnd = s.indexOf('\n', insertAt).let { if (it < 0) s.length else it }
            val line = s.substring(insertAt, lineEnd).trimStart()
            if (line.isEmpty() || line.startsWith("#extension")) {
                insertAt = if (lineEnd < s.length) lineEnd + 1 else s.length
            } else break
        }
        val needsFragOut = "gl_FragColor" in s
        val preamble = "precision mediump float;\nprecision mediump int;\n" +
            if (needsFragOut) "out vec4 _wwFragColor;\n" else ""
        s = s.substring(0, insertAt) + preamble + s.substring(insertAt)
        if (needsFragOut) s = s.replace("gl_FragColor", "_wwFragColor")
    }
    return s
}
