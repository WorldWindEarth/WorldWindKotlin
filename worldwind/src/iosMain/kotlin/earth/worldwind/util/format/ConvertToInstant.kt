package earth.worldwind.util.format

import kotlinx.datetime.toKotlinInstant
import platform.Foundation.NSDate
import kotlin.time.Instant

actual fun convertToInstant(t: Any): Instant = when (t) {
    is NSDate -> t.toKotlinInstant()
    else -> throw IllegalArgumentException("Can't convert to Instant: $t")
}
