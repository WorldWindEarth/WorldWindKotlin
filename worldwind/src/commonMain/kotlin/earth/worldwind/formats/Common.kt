package earth.worldwind.formats

internal fun isValidHttpsUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val httpsUrlRegex = Regex(
        pattern = "^https://[\\w.-]+(?:\\.[\\w.-]+)+(?:/\\S*)?$",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    return httpsUrlRegex.matches(url)
}

internal fun forceHttps(url: String) = url.replace("http://", "https://")