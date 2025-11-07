package earth.worldwind.util.format

import kotlinx.datetime.*
import kotlin.time.Instant

internal class StringFormat(val format: String, val args: Array<out Any?>) {

    private var pos = 0
    private var specStart = -1
    private val result = StringBuilder()
    private var currentIndex = 0

    fun process(): StringFormat {
        while (pos < format.length) {
            val ch = format[pos++]
            if (ch == '%') {
                specStart = when {
                    specStart == pos - 1 -> {
                        result.append(ch)
                        -1
                    }
                    specStart < 0 -> pos
                    else -> invalidFormat("unexpected %")
                }
            } else {
                if (specStart >= 0) {
                    pos--
                    Specification(this, currentIndex++).scan()
                } else result.append(ch)
            }
        }
        return this
    }

    internal fun nextChar(): Char {
        if (pos >= format.length) invalidFormat("unexpected end of string inside format specification")
        return format[pos++]
    }

    internal fun invalidFormat(reason: String): Nothing {
        throw IllegalArgumentException("bad format: $reason at ofset ${pos - 1} of \"$format\"")
    }

    override fun toString() = result.toString()

    internal fun getNumber(index: Int): Number = notNullArg(index)

    internal fun getText(index: Int) = args[index]!!.toString()

    internal fun getCharacter(index: Int): Char = notNullArg(index)

    internal fun specificationDone(text: String) {
        result.append(text)
        specStart = -1
    }

    fun getLocalDateTime(index: Int): LocalDateTime {
        val t = notNullArg<Any>(index)
        return when(t) {
            is Instant -> t.toLocalDateTime(TimeZone.currentSystemDefault())
            is LocalDateTime -> t
            is LocalDate -> t.atTime(0,0,0)
            else -> convertToInstant(t).toLocalDateTime(TimeZone.currentSystemDefault())
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T>notNullArg(index: Int) = args[index]!! as T

    fun pushbackArgumentIndex() { currentIndex-- }
}

fun String.format(vararg args: Any?): String = StringFormat(this, args).process().toString()

expect fun convertToInstant(t: Any): Instant