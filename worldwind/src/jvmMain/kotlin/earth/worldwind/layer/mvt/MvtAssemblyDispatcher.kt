package earth.worldwind.layer.mvt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

actual val mvtAssemblyDispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
) { r -> Thread(r).apply { isDaemon = true; priority = Thread.MIN_PRIORITY; name = "mvt-assembly" } }.asCoroutineDispatcher()
