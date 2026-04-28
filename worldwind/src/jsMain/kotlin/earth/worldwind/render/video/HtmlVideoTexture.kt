package earth.worldwind.render.video

import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Texture
import earth.worldwind.util.kgl.GL_RGBA
import earth.worldwind.util.kgl.GL_TEXTURE_2D
import earth.worldwind.util.kgl.GL_UNSIGNED_BYTE
import earth.worldwind.util.kgl.WebKgl
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement

/**
 * JS-only [earth.worldwind.render.Texture] that mirrors a playing `HTMLVideoElement` onto a
 * `GL_TEXTURE_2D` sampler. Each [bindTexture] call uploads the latest decoded frame via the
 * native WebGL `texImage2D(target, level, internalFormat, format, type, HTMLVideoElement)`
 * overload — the browser handles decoding and color-space conversion; no CPU pixel copy.
 *
 * Sampled with the standard [earth.worldwind.render.program.SurfaceQuadShaderProgram] /
 * `sampler2D` path — no extension or OES variant. Caller controls playback (`video.play()`
 * / `video.pause()` / `video.currentTime`); we skip uploads while
 * `video.readyState < HAVE_CURRENT_DATA` so the texture stays at the last successful frame
 * during seeks/buffering.
 *
 * **Redraw triggering.** Other backends in this set call
 * [WorldWind.requestRedraw] from their `submitFrame` path; here, the texture has nothing to
 * publish — it draws straight from `HTMLVideoElement` on each bind. We instead register a
 * `requestVideoFrameCallback` listener (per-decoded-frame, no busy-poll), with a `requestAnimationFrame`
 * fallback for browsers that don't expose it (older Firefox). Each callback triggers
 * `WorldWind.requestRedraw()`, which fires the engine's render path → [bindTexture] →
 * `texImage2D(... video)` → fresh frame on screen.
 */
class HtmlVideoTexture(
    val video: HTMLVideoElement,
    width: Int,
    height: Int,
) : Texture(width, height, format = GL_RGBA, type = GL_UNSIGNED_BYTE, isRT = false) {

    init {
        // Browser uploads pixels in image-source order (top-left origin) into a GL
        // texture (bottom-left origin), so the V axis is inverted vs. what the
        // surface-quad shader expects. Y-flip via texCoordMatrix — same convention as
        // ImageTexture.
        coordTransform.setToVerticalFlip()
        installFrameRedrawTrigger()
    }

    /** No-op; storage is supplied by the first `texImage2D(... video)` upload in [bindTexture]. */
    override fun allocTexImage(dc: DrawContext) { /* video supplies storage */ }

    override fun bindTexture(dc: DrawContext): Boolean {
        if (!super.bindTexture(dc)) return false
        // HAVE_CURRENT_DATA = 2: at least the current frame is decoded and renderable.
        // While loading we keep showing the last successful upload (zeros on first frame).
        if (video.readyState.toInt() < 2) return true
        val gl = (dc.gl as? WebKgl)?.gl ?: return true
        // The DOM has `WebGLRenderingContext.texImage2D(target, level, internalFormat,
        // format, type, source: TexImageSource)` (where TexImageSource includes
        // HTMLVideoElement), but the legacy Khronos Kotlin binding doesn't expose it.
        // [VideoTexImage2DContext] re-declares just that one overload as an `external`
        // interface; `unsafeCast` is a tag — at runtime it's the same JS object, but the
        // Kotlin source is type-checked instead of routed through `asDynamic()`.
        gl.unsafeCast<VideoTexImage2DContext>().texImage2D(
            GL_TEXTURE_2D, 0, GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, video
        )
        return true
    }

    /**
     * Schedule a one-shot per-video-frame callback that fans out a redraw and re-arms
     * itself. We prefer `HTMLVideoElement.requestVideoFrameCallback` (Chrome 83+, Safari
     * 15.4+, Firefox 132+) — it fires once per decoded frame and only while playing. On
     * older browsers, fall back to `requestAnimationFrame` driven from the `playing` event,
     * stopping when the video pauses or ends.
     */
    private fun installFrameRedrawTrigger() {
        val v = video.unsafeCast<RequestVideoFrameCallbackHost>()
        if (jsTypeof(v.requestVideoFrameCallback).unsafeCast<String>() == "function") {
            lateinit var rearm: () -> Unit
            rearm = {
                v.requestVideoFrameCallback { _, _ ->
                    WorldWind.requestRedraw()
                    if (!video.paused && !video.ended) rearm()
                }
            }
            video.addEventListener("playing", { rearm() })
        } else {
            // requestAnimationFrame fallback — drive a self-rearming raf loop while playing.
            lateinit var rafLoop: () -> Unit
            rafLoop = {
                if (!video.paused && !video.ended) {
                    WorldWind.requestRedraw()
                    window.requestAnimationFrame { rafLoop() }
                }
            }
            video.addEventListener("playing", { rafLoop() })
        }
    }
}

/** `typeof x` JS operator wrapper. */
private fun jsTypeof(value: Any?): Any? = js("typeof value")

/**
 * Typed view of the WebGL context's `texImage2D(target, level, internalFormat, format,
 * type, source: TexImageSource)` overload. We only need [HTMLVideoElement] as the source
 * type — the full `TexImageSource` union isn't on the legacy Khronos binding either, so we
 * just declare the concrete one we use. Kotlin/JS forbids nested `external` declarations
 * so this lives at file scope (file-private).
 */
private external interface VideoTexImage2DContext {
    fun texImage2D(
        target: Int, level: Int, internalformat: Int,
        format: Int, type: Int, source: HTMLVideoElement,
    )
}

/**
 * Typed view of `HTMLVideoElement.requestVideoFrameCallback(cb)`. The callback receives
 * `(now, metadata)` — we don't use either; we just want the wakeup. Same `unsafeCast`
 * tag-only pattern as [VideoTexImage2DContext].
 */
private external interface RequestVideoFrameCallbackHost {
    val requestVideoFrameCallback: Any?
    fun requestVideoFrameCallback(callback: (Double, Any) -> Unit): Int
}
