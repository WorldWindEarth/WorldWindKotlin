package earth.worldwind.util.kgl

import org.khronos.webgl.TexImageSource

/**
 * The web (js + wasmJs) [Kgl], adding the one WebGL capability the common interface can't express:
 * uploading a DOM image source (image / canvas / video / ImageBitmap) straight to texture level 0
 * via `texImage2D(target, level, internalFormat, format, type, source)`, which has no buffer
 * equivalent. Shared render code ([earth.worldwind.render.image.ImageTexture],
 * [earth.worldwind.render.video.HtmlVideoTexture]) reaches it by casting `dc.gl as WebKgl`; the
 * concrete implementations (JsWebKgl on js, WasmJsWebKgl on wasmJs) stay per-target because the rest
 * of their WebGL interop diverges (js `dynamic` zero-copy views vs wasm typed `JsAny` copies).
 */
interface WebKgl : Kgl {
    fun texImage2D(target: Int, level: Int, internalFormat: Int, format: Int, type: Int, source: TexImageSource?)
}
