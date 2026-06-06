package earth.worldwind.shape.milstd2525

/** js actual: the native JS `WeakRef` (object identity, true weak semantics). */
actual external class WeakRef<T : Any> actual constructor(element: T) {
    actual fun deref(): T?
}
