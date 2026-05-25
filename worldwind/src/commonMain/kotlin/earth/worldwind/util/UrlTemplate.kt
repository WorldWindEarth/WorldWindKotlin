package earth.worldwind.util

import earth.worldwind.util.locale.language
import kotlin.random.Random

/**
 * Slippy-map URL template with the WorldWind dialect of substitution tokens:
 *   * `{x}` / `{y}` / `{z}` — tile coordinates.
 *   * `{lang}` — current locale language code.
 *   * `{rand=a,b,c}` — random choice from the supplied comma-separated values.
 *   * `{i}` — `x % 4 + y % 4 * 4`, the 16-server tile-hash pattern some XYZ providers use.
 *
 * Parses the template once at construction; [buildUrl] is cheap to call per tile.
 */
class UrlTemplate(val template: String) {
    private val parts: List<String> = parseUrl(template)
    private val randomToken: String? = parts.find { it.startsWith(RAND_PREFIX) }
    private val randomValues: List<String>? =
        randomToken?.removeSurrounding(RAND_PREFIX, "}")?.split(",")

    /** Substitute `(z, x, y)` plus dialect tokens into the template. */
    fun buildUrl(z: Int, x: Int, y: Int): String {
        val sb = StringBuilder()
        for (part in parts) {
            sb.append(
                when (part) {
                    X_COORDINATE -> x
                    Y_COORDINATE -> y
                    Z_COORDINATE -> z
                    LANGUAGE -> language
                    randomToken -> randomValues?.get(Random.nextInt(randomValues.size))
                    I_EXPRESSION -> x % 4 + y % 4 * 4
                    else -> part
                }
            )
        }
        return sb.toString()
    }

    private companion object {
        const val OPEN_BRACKET = '{'
        const val CLOSED_BRACKET = '}'
        const val I_EXPRESSION = "{i}"
        const val X_COORDINATE = "{x}"
        const val Y_COORDINATE = "{y}"
        const val Z_COORDINATE = "{z}"
        const val LANGUAGE = "{lang}"
        const val RAND_PREFIX = "{rand="

        fun parseUrl(serverUrl: String): List<String> {
            val result = mutableListOf<String>()
            val builder = StringBuilder()
            serverUrl.toCharArray().forEachIndexed { index, char ->
                if (char == OPEN_BRACKET) {
                    result.add(builder.toString())
                    builder.clear()
                }
                builder.append(char)
                if (char == CLOSED_BRACKET) {
                    result.add(builder.toString())
                    builder.clear()
                }
                if (index == serverUrl.length - 1 && char != CLOSED_BRACKET) {
                    result.add(builder.toString())
                    builder.clear()
                }
            }
            return result
        }
    }
}
