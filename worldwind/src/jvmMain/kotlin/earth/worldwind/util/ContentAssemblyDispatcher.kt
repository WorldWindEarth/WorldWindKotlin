package earth.worldwind.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

// Cap at ~half the cores (2..4) so per-feature build can't saturate every core and starve render/GC.
private val contentAssemblyParallelism = (Runtime.getRuntime().availableProcessors() / 2 - 1).coerceIn(2, 4)

actual val contentAssemblyDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
    contentAssemblyParallelism
) { r -> Thread(r).apply { isDaemon = true; priority = Thread.MIN_PRIORITY; name = "content-assembly" } }.asCoroutineDispatcher()
