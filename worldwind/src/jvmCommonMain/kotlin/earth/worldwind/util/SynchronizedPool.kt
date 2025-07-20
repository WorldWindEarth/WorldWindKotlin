package earth.worldwind.util

import java.util.concurrent.ConcurrentLinkedQueue

actual open class SynchronizedPool<T> : Pool<T> {
    private val queue = ConcurrentLinkedQueue<T>()

    override fun acquire(): T? = queue.poll()

    override fun release(instance: T?) {
        queue.offer(instance)
    }
}