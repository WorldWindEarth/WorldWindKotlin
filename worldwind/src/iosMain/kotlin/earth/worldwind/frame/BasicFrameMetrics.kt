package earth.worldwind.frame

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.RenderContext
import earth.worldwind.render.RenderResourceCache
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * iOS port of `BasicFrameMetrics`. Differences from the JVM/Android port:
 * - `System.currentTimeMillis()` → `Clock.System.now().toEpochMilliseconds()`
 * - The `synchronized(drawLock)` blocks are dropped. iOS dispatches GL work on a dedicated
 *   queue managed by [earth.worldwind.WorldWindow]; render and draw aren't expected to run
 *   on different threads simultaneously. If that changes (e.g. a background tile-draw
 *   thread), wrap the draw* methods with an `AtomicReference`-based guard.
 */
@OptIn(ExperimentalTime::class)
open class BasicFrameMetrics : FrameMetrics {
    protected val renderMetrics = TimeMetrics()
    protected val drawMetrics = TimeMetrics()
    protected val renderResourceCacheMetrics = CacheMetrics()
    val renderTime get() = renderMetrics.time
    val renderTimeAverage get() = computeTimeAverage(renderMetrics)
    val renderTimeStdDev get() = computeTimeStdDev(renderMetrics)
    val renderTimeTotal get() = renderMetrics.timeSum
    val renderCount get() = renderMetrics.count
    val drawTime get() = drawMetrics.time
    val drawTimeAverage get() = computeTimeAverage(drawMetrics)
    val drawTimeStdDev get() = computeTimeStdDev(drawMetrics)
    val drawTimeTotal get() = drawMetrics.timeSum
    val drawCount get() = drawMetrics.count
    val renderResourceCacheCapacity get() = renderResourceCacheMetrics.capacity
    val renderResourceCacheUsedCapacity get() = renderResourceCacheMetrics.usedCapacity
    val renderResourceCacheEntryCount get() = renderResourceCacheMetrics.entryCount

    private fun nowMs() = Clock.System.now().toEpochMilliseconds()

    override fun beginRendering(rc: RenderContext) { markBegin(renderMetrics, nowMs()) }

    override fun endRendering(rc: RenderContext) {
        markEnd(renderMetrics, nowMs())
        assembleCacheMetrics(renderResourceCacheMetrics, rc.renderResourceCache)
    }

    override fun beginDrawing(dc: DrawContext) { markBegin(drawMetrics, nowMs()) }
    override fun endDrawing(dc: DrawContext) { markEnd(drawMetrics, nowMs()) }

    override fun reset() {
        resetTimeMetrics(renderMetrics)
        resetTimeMetrics(drawMetrics)
    }

    protected open fun markBegin(metrics: TimeMetrics, timeMillis: Long) { metrics.begin = timeMillis }

    protected open fun markEnd(metrics: TimeMetrics, timeMillis: Long) {
        metrics.time = timeMillis - metrics.begin
        metrics.timeSum += metrics.time
        metrics.timeSumOfSquares += metrics.time * metrics.time
        metrics.count++
    }

    protected open fun resetTimeMetrics(metrics: TimeMetrics) {
        metrics.timeSum = 0
        metrics.timeSumOfSquares = 0
        metrics.count = 0
    }

    protected open fun computeTimeAverage(metrics: TimeMetrics) =
        if (metrics.count > 0) metrics.timeSum / metrics.count.toDouble() else 0.0

    protected open fun computeTimeStdDev(metrics: TimeMetrics): Double {
        return if (metrics.count > 0) {
            val avg = metrics.timeSum.toDouble() / metrics.count.toDouble()
            val variance = metrics.timeSumOfSquares.toDouble() / metrics.count.toDouble() - avg * avg
            sqrt(variance)
        } else 0.0
    }

    protected open fun assembleCacheMetrics(metrics: CacheMetrics, cache: RenderResourceCache) {
        metrics.capacity = cache.capacity
        metrics.usedCapacity = cache.usedCapacity
        metrics.entryCount = cache.entryCount
    }

    protected class CacheMetrics {
        var capacity = 0L
        var usedCapacity = 0L
        var entryCount = 0
    }

    protected class TimeMetrics {
        var begin = 0L
        var time = 0L
        var timeSum = 0L
        var timeSumOfSquares = 0L
        var count = 0L
    }

    override fun toString(): String = buildString {
        append("FrameMetrics{renderMetrics={")
        append("lastTime=").append(renderMetrics.time).append("ms")
        append(", count=").append(renderMetrics.count)
        append("}, drawMetrics={")
        append("lastTime=").append(drawMetrics.time).append("ms")
        append(", count=").append(drawMetrics.count)
        append("}, cache={")
        append("capacity=").append(renderResourceCacheMetrics.capacity)
        append(", used=").append(renderResourceCacheMetrics.usedCapacity)
        append(", entries=").append(renderResourceCacheMetrics.entryCount)
        append("}}")
    }
}
