package earth.worldwind.util

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

// Cap at ~half the cores (2..4) so per-feature build can't saturate every core and starve render/GC.
private val contentAssemblyParallelism = (Runtime.getRuntime().availableProcessors() / 2 - 1).coerceIn(2, 4)

actual val contentAssemblyDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
    contentAssemblyParallelism
) { r ->
    Thread {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }.apply { isDaemon = true; name = "content-assembly" }
}.asCoroutineDispatcher()
