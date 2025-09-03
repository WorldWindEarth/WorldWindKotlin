package earth.worldwind.formats

internal const val DEFAULT_DENSITY = 1.0f
internal const val HIGHLIGHT_INCREMENT = 4f
internal const val DEFAULT_IMAGE_SCALE = 1.0
internal const val DEFAULT_PLACEMARK_ICON_SIZE = 24.0
internal const val METERS_PER_LATITUDE_DEGREE = 111_320.0
internal const val DEFAULT_LABEL_VISIBILITY_THRESHOLD = 0.0

internal fun isValidHttpsUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val httpsUrlRegex = Regex(
        pattern = "^https://[\\w.-]+(?:\\.[\\w.-]+)+(?:/\\S*)?$",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    return httpsUrlRegex.matches(url)
}

internal fun forceHttps(url: String) = url.replace("http://", "https://")