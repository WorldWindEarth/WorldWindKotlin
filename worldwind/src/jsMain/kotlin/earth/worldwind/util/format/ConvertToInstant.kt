package earth.worldwind.util.format

import kotlin.time.Instant
import kotlin.js.Date

actual fun convertToInstant(t: Any) = when(t) {
    is Date -> Instant.fromEpochMilliseconds(t.getTime().toLong())
    else -> throw IllegalArgumentException("Can't convert to LocalDateTime: $t")
}
