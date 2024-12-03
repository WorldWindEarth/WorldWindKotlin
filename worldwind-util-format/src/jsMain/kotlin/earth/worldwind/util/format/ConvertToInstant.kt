package earth.worldwind.util.format

import kotlinx.datetime.Instant

actual fun convertToInstant(t: Any) = when(t) {
    is kotlin.js.Date -> Instant.fromEpochMilliseconds(t.getTime().toLong())
    else -> throw IllegalArgumentException("Can't convert to LocalDateTime: $t")
}
