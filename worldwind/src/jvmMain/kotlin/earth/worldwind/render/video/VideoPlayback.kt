package earth.worldwind.render.video

/**
 * Common playback contract for the JVM video textures (VLCJ, JavaCV, FFmpeg, JavaFX).
 * Every JVM `*VideoTexture` exposes the same minimal API; this interface formalizes that
 * so tutorial / app code can target one type instead of carrying a per-backend adapter.
 *
 * The engine doesn't depend on this interface — it's a convenience for callers that want
 * to swap backends at runtime.
 */
interface VideoPlayback {
    /** Current playback time in milliseconds since playback started. */
    val timeMs: Long

    /** Start (or resume) playback. Re-entrant — calling on an already-running player is a no-op. */
    fun play()

    /** Pause playback. The current frame stays on the texture; resumable via [play]. */
    fun pause()

    /** Stop playback and rewind to the start. */
    fun stop()

    /** Free decoder + native resources. After [release] the texture is unusable. */
    fun release()
}
