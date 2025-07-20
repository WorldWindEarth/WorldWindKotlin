package earth.worldwind.util

import java.util.concurrent.ConcurrentLinkedQueue

actual open class SynchronizedPool<T> : Pool<T> {
    private val queue = ConcurrentLinkedQueue<T>()

    actual override fun acquire(): T? = queue.poll()

    actual override fun release(instance: T?) {
        queue.offer(instance)
    }
}