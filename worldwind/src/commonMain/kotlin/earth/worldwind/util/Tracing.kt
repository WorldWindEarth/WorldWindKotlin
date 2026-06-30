package earth.worldwind.util

/**
 * Platform tracing bridge for Perfetto / systrace. Android wires this to
 * `android.os.Trace.beginSection` / `endSection`; other platforms compile to no-ops.
 *
 * Use to mark engine phases that should appear on the systrace timeline:
 * ```
 * traceSection("RenderFrame") {
 *     // ... renderFrame body ...
 * }
 * ```
 *
 * Keep section names ≤ 127 chars (Android limit), ASCII-only, no nested-NUL bytes.
 * Cost on Android: ~1-2 µs per begin/end pair when systrace is recording; ~50 ns
 * when not.
 */
expect inline fun <T> traceSection(name: String, block: () -> T): T

/** Async-section pair — [traceAsyncBegin] / [traceAsyncEnd] form a single horizontal
 *  bar in the Perfetto timeline, spanning threads and lasting from begin to end. Each
 *  in-flight bar is identified by `(name, cookie)`; cookies should be unique per
 *  in-flight instance. Use to chart the per-tile lifecycle (queued → fetched → parsed
 *  → uploaded) so individual tiles' delays are visible on the timeline. */
expect fun traceAsyncBegin(name: String, cookie: Int)
expect fun traceAsyncEnd(name: String, cookie: Int)

/** Counter track — value plotted as a line graph against time on the Perfetto timeline.
 *  Use for instantaneous metrics (in-flight fetches, channel depths, install cap) so
 *  saturation moments line up visually with per-frame marker stalls. Requires Android
 *  API 29+; older platforms compile to no-ops. */
expect fun traceCounter(name: String, value: Long)
