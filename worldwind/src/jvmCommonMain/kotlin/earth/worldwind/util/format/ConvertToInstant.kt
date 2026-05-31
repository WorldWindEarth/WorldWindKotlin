package earth.worldwind.util.format

import kotlin.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

actual fun convertToInstant(t: Any) = when (t) {
    is LocalDateTime -> Instant.fromEpochSeconds(t.atZone(ZoneId.systemDefault()).toEpochSecond())
    is ZonedDateTime -> Instant.fromEpochSeconds(t.toEpochSecond())
    is java.time.Instant -> Instant.fromEpochMilliseconds(t.toEpochMilli())
    is Date -> Instant.fromEpochMilliseconds(t.time)
    else -> throw IllegalArgumentException("Can't convert to LocalDateTime: $t")
}
