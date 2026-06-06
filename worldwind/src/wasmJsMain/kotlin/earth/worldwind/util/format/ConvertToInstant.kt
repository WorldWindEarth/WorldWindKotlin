package earth.worldwind.util.format

import kotlin.time.Instant

// On Kotlin/Wasm a JS Date arrives as a JsAny and cannot satisfy the `t: Any` contract, so the
// kotlinx-datetime types handled by StringFormat upstream are the only values that reach here.
actual fun convertToInstant(t: Any): Instant =
    throw IllegalArgumentException("Can't convert to Instant: $t")
