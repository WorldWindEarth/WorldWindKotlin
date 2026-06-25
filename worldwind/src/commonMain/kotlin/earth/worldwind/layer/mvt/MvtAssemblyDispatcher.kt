package earth.worldwind.layer.mvt

import kotlinx.coroutines.CoroutineDispatcher

/** Low-priority dispatcher for the CPU-heavy MVT decode + tessellation, so the OS scheduler hands
 *  cores to the render/GL threads first. Background-priority threads on Android, MIN_PRIORITY on the
 *  JVM, plain default elsewhere. Shared process-wide; the per-layer assembly semaphore still bounds
 *  concurrency. */
expect val mvtAssemblyDispatcher: CoroutineDispatcher
