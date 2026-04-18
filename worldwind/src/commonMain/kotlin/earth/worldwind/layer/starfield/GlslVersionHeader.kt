package earth.worldwind.layer.starfield

/**
 * GLSL version directive prepended to fragment shaders.
 * This change allows different shaders implementations to be used for different platforms, as needed.
 * To summarize the GLSL version requirements:
 * Desktop GL requires "#version 120" as the very first line.
 * OpenGL ES (Android, WebGL/JS) must NOT include a version directive (defaults to #version 100).
 */
internal expect val glslVersionHeader: String

