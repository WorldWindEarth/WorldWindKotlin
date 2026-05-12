package earth.worldwind.render.video

import android.graphics.SurfaceTexture
import android.view.Surface
import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.render.OesExternalTexture

/**
 * Android-specific [OesExternalTexture] that bridges a `SurfaceTexture` / `Surface` consumer
 * (typically `MediaPlayer`, `MediaCodec`, or `Camera2`) to the WorldWind GL pipeline. The
 * lifecycle is GL-thread-driven:
 *
 *  1. Caller constructs [MediaSurfaceTexture] from the main thread and assigns it to a
 *     [earth.worldwind.shape.ProjectedMediaSurface.texture] (or any consumer that calls [bindTexture]).
 *  2. On the first render frame, `super.bindTexture(dc)` allocates the GL texture name; we
 *     wrap it in a `SurfaceTexture` + `Surface` and fire [onSurfaceReady] so the caller can
 *     hand the [surface] to its media producer.
 *  3. Each subsequent [bindTexture] call invokes `surfaceTexture.updateTexImage()` to pull
 *     the latest frame from the producer into the bound OES texture.
 *
 * **Context-loss recovery.** When the host activity goes through pause/resume the
 * GLSurfaceView's GL context is destroyed and rebuilt with a fresh GL name. The decoder
 * keeps writing into the old `Surface` (whose backing `SurfaceTexture` is bound to the
 * dead GL name), and frames silently disappear. Two recovery signals: the GL name id
 * changing across a bind, or `updateTexImage` throwing because its captured EGLContext
 * is no longer current. Either causes the wrap to be dropped; the next bind allocates a
 * fresh `SurfaceTexture` + `Surface` and re-fires [onSurfaceReady] so the caller can
 * rebind the player.
 *
 * `setOnFrameAvailableListener` fires on the listener's caller thread by default; producer
 * frames typically arrive on a binder / decoder thread. The listener requests a redraw
 * (so the GL thread picks up the new frame) and then invokes the user-supplied
 * [onFrameAvailable] hook for any extra wiring. Do not touch GL state from the listener —
 * it does not run on the GL thread.
 */
open class MediaSurfaceTexture(width: Int, height: Int) : OesExternalTexture(width, height) {

    init {
        // Producers fill the SurfaceTexture in image-source order (top-left origin), but GL
        // textures sample bottom-left-origin — flip V via texCoordMatrix. SurfaceTexture
        // exposes a per-frame transform matrix via getTransformMatrix() that's typically a
        // Y-flip for MediaPlayer; we collapse that to a static [coordTransform] flip. If a
        // producer ever needs a non-flip transform (rotated camera, …) bring in the
        // dynamic getTransformMatrix path instead.
        coordTransform.setToVerticalFlip()
    }

    private var surfaceTexture: SurfaceTexture? = null
    /** Last GL name id we wrapped — used to detect context-loss regeneration. */
    private var boundNameId: Int = -1

    /** `Surface` wrapping the underlying [SurfaceTexture]. Null until [onSurfaceReady] fires. */
    var surface: Surface? = null
        private set

    /**
     * Optional hook invoked after [WorldWind.requestRedraw] whenever the producer reports
     * a new frame. Fires on whatever thread the producer chooses — usually a binder /
     * decoder thread, NOT the GL thread; do not touch GL state here.
     */
    var onFrameAvailable: (() -> Unit)? = null

    /**
     * Invoked on the GL thread after [surface] becomes available — the first time when the
     * GL name is initially allocated, and again after a GL context loss when the name is
     * regenerated. The caller must hand [surface] to its media producer (e.g.
     * `MediaPlayer.setSurface(surface)`) on each invocation; the previous Surface is no
     * longer valid.
     */
    var onSurfaceReady: (() -> Unit)? = null

    override fun bindTexture(dc: DrawContext): Boolean {
        if (!super.bindTexture(dc)) return false
        if (name.id != boundNameId) {
            // Drop the previous producer side first so an in-flight updateTexImage can't
            // race with deletion, then wrap the freshly allocated GL name.
            releaseBindings()
            val st = SurfaceTexture(name.id).also { surfaceTexture = it }
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener {
                // Trigger a fresh render frame so the GL thread re-runs bindTexture and
                // updateTexImage picks up the new frame.
                WorldWind.requestRedraw()
                onFrameAvailable?.invoke()
            }
            surface = Surface(st)
            boundNameId = name.id
            onSurfaceReady?.invoke()
        }
        // Pull the latest producer frame into the OES texture we just bound. After an
        // activity pause/resume the driver sometimes reuses the same texture id under a
        // freshly created EGL context — the id check above won't catch that, but
        // `updateTexImage` will throw `invalid current EGLContext`. Treat the throw as
        // a "context cycled" signal: drop the cached wrap so the next bind allocates
        // fresh state on the current context.
        return try {
            surfaceTexture?.updateTexImage()
            true
        } catch (_: IllegalStateException) {
            releaseBindings()
            false
        }
    }

    /** Releases the cached `SurfaceTexture` + `Surface` and resets the bind-tracking state. */
    private fun releaseBindings() {
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        boundNameId = -1
    }

    override fun deleteTexture(dc: DrawContext) {
        // Drop the producer side first so updateTexImage can't race with deletion.
        releaseBindings()
        super.deleteTexture(dc)
    }
}
