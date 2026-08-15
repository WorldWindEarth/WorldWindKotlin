package earth.worldwind.util

import kotlinx.coroutines.CoroutineDispatcher

/** Low-priority dispatcher for CPU-heavy content assembly (MVT decode + tessellation, 3D Tiles
 *  parse + texture decode + mesh prep), so the OS scheduler hands cores to the render/GL threads
 *  first. Background-priority threads on Android, MIN_PRIORITY on the JVM, plain default elsewhere.
 *  Shared process-wide; per-layer semaphores / fetch permits still bound in-flight work. */
expect val contentAssemblyDispatcher: CoroutineDispatcher
