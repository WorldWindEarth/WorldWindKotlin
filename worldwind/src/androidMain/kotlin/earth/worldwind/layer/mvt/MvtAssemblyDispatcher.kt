package earth.worldwind.layer.mvt

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

actual val mvtAssemblyDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
) { r ->
    Thread {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        r.run()
    }.apply { isDaemon = true; name = "mvt-assembly" }
}.asCoroutineDispatcher()
