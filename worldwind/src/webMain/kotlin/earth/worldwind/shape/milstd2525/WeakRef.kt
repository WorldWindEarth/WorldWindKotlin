package earth.worldwind.shape.milstd2525

/**
 * A weak reference used by the MIL-STD-2525 symbol cache so unused
 * [earth.worldwind.shape.PlacemarkAttributes] bundles can be reclaimed. js uses a native JS `WeakRef`;
 * wasm retains strongly (a JS `WeakRef` can't reliably hold a Kotlin/Wasm heap object — see the wasm
 * actual), which is sound because the cache is bounded to the finite set of MIL-STD symbol codes.
 */
expect class WeakRef<T : Any>(element: T) {
    fun deref(): T?
}
