package earth.worldwind.frame

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.RenderContext
import earth.worldwind.render.RenderResourceCache
import kotlin.math.sqrt

open class BasicFrameMetrics : FrameMetrics {
    private val drawLock = Any()
    protected val renderMetrics = TimeMetrics()
    protected val drawMetrics = TimeMetrics()
    protected val renderResourceCacheMetrics = CacheMetrics()
    val renderTime get() = renderMetrics.time
    val renderTimeAverage get() = computeTimeAverage(renderMetrics)
    val renderTimeStdDev get() = computeTimeStdDev(renderMetrics)
    val renderTimeTotal get() = renderMetrics.timeSum
    val renderCount get() = renderMetrics.count
    val drawTime get(): Long { synchronized(drawLock) { return drawMetrics.time } }
    val drawTimeAverage get(): Double { synchronized(drawLock) { return computeTimeAverage(drawMetrics) } }
    val drawTimeStdDev get(): Double { synchronized(drawLock) { return computeTimeStdDev(drawMetrics) } }
    val drawTimeTotal get(): Long { synchronized(drawLock) { return drawMetrics.timeSum } }
    val drawCount get(): Long { synchronized(drawLock) { return drawMetrics.count } }
    val renderResourceCacheCapacity get() = renderResourceCacheMetrics.capacity
    val renderResourceCacheUsedCapacity get() = renderResourceCacheMetrics.usedCapacity
    val renderResourceCacheEntryCount get() = renderResourceCacheMetrics.entryCount

    override fun beginRendering(rc: RenderContext) {
        val now = System.currentTimeMillis()
        markBegin(renderMetrics, now)
    }

    override fun endRendering(rc: RenderContext) {
        val now = System.currentTimeMillis()
        markEnd(renderMetrics, now)
        assembleCacheMetrics(renderResourceCacheMetrics, rc.renderResourceCache)
    }

    override fun beginDrawing(dc: DrawContext) {
        val now = System.currentTimeMillis()
        synchronized(drawLock) { markBegin(drawMetrics, now) }
    }

    override fun endDrawing(dc: DrawContext) {
        val now = System.currentTimeMillis()
        synchronized(drawLock) { markEnd(drawMetrics, now) }
    }

    override fun reset() {
        resetTimeMetrics(renderMetrics)
        synchronized(drawLock) { resetTimeMetrics(drawMetrics) }
    }

    protected open fun markBegin(metrics: TimeMetrics, timeMillis: Long) {
        metrics.begin = timeMillis
    }

    protected open fun markEnd(metrics: TimeMetrics, timeMillis: Long) {
        metrics.time = timeMillis - metrics.begin
        metrics.timeSum += metrics.time
        metrics.timeSumOfSquares += metrics.time * metrics.time
        metrics.count++
    }

    protected open fun resetTimeMetrics(metrics: TimeMetrics) {
        // reset the metrics collected across multiple frames
        metrics.timeSum = 0
        metrics.timeSumOfSquares = 0
        metrics.count = 0
    }

    protected open fun computeTimeAverage(metrics: TimeMetrics) =
        if (metrics.count > 0) metrics.timeSum / metrics.count.toDouble() else 0.0

    protected open fun computeTimeStdDev(metrics: TimeMetrics): Double {
        return if (metrics.count > 0) {
            val average = metrics.timeSum.toDouble() / metrics.count.toDouble()
            val variable = metrics.timeSumOfSquares.toDouble() / metrics.count.toDouble() - average * average
            sqrt(variable)
        } else 0.0
    }

    protected open fun assembleCacheMetrics(metrics: CacheMetrics, cache: RenderResourceCache) {
        metrics.capacity = cache.capacity
        metrics.usedCapacity = cache.usedCapacity
        metrics.entryCount = cache.entryCount
    }

    protected open fun printCacheMetrics(metrics: CacheMetrics, out: StringBuilder) {
        out.append("capacity=").append("%,.0f".format(metrics.capacity / 1024.0)).append("KB")
        out.append(", usedCapacity=").append("%,.0f".format(metrics.usedCapacity / 1024.0)).append("KB")
        out.append(", entryCount=").append(metrics.entryCount)
    }

    protected open fun printTimeMetrics(metrics: TimeMetrics, out: StringBuilder) {
        out.append("lastTime=").append(metrics.time).append("ms")
        out.append(", totalTime=").append(metrics.timeSum).append("ms")
        out.append(", count=").append(metrics.count)
        out.append(", avg=").append("%.1f".format(computeTimeAverage(metrics))).append("ms")
        out.append(", stdDev=").append("%.1f".format(computeTimeStdDev(metrics))).append("ms")
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

    override fun toString(): String {
        val sb = StringBuilder("FrameMetrics")
        sb.append("{renderMetrics={")
        printTimeMetrics(renderMetrics, sb)
        sb.append("}, drawMetrics={")
        printTimeMetrics(drawMetrics, sb)
        sb.append("}, renderResourceCacheMetrics={")
        printCacheMetrics(renderResourceCacheMetrics, sb)
        sb.append("}")
        return sb.toString()
    }
}