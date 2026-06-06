package earth.worldwind.shape.milstd2525

/**
 * wasm actual: a JS `WeakRef` can't reliably hold a Kotlin/Wasm heap object — it must be boxed in a
 * `JsReference` whose box has no strong holder, so the `WeakRef` can clear while the boxed object is
 * still in use (premature symbol-cache eviction → re-rasterization). The sole consumer is the bounded
 * MIL-STD-2525 symbol cache, so retain strongly: bounded memory, no premature eviction.
 */
actual class WeakRef<T : Any> actual constructor(private val element: T) {
    actual fun deref(): T? = element
}
