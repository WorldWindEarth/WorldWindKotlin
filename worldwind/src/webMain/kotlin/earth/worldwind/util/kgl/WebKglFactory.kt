package earth.worldwind.util.kgl

import org.khronos.webgl.WebGLRenderingContext

/**
 * Constructs the platform [Kgl] backed by a WebGL context. `WebKgl` itself stays per-target (its
 * js and wasm implementations diverge on WebGL interop — `dynamic` zero-copy views on js vs typed
 * `JsAny` copies on wasm), so the shared [earth.worldwind.WorldWindow] reaches it through this
 * expect/actual factory instead of naming the concrete class.
 */
internal expect fun createWebKgl(gl: WebGLRenderingContext): Kgl
