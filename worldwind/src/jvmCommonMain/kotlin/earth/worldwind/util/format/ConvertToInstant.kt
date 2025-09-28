package earth.worldwind.util.format

import kotlin.time.Instant

actual fun convertToInstant(t: Any) = when (t) {
    is java.time.LocalDateTime -> Instant.fromEpochSeconds(t.atZone(java.time.ZoneId.systemDefault()).toEpochSecond())
    is java.time.ZonedDateTime -> Instant.fromEpochSeconds(t.toEpochSecond())
    is java.time.Instant -> Instant.fromEpochMilliseconds(t.toEpochMilli())
    is java.util.Date -> Instant.fromEpochMilliseconds(t.time)
    else -> throw IllegalArgumentException("Can't convert to LocalDateTime: $t")
}
