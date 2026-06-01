package earth.worldwind.layer.ogc3d.auth

import com.eygraber.uri.Uri
import kotlin.concurrent.Volatile

/**
 * Google Photorealistic 3D Tiles auth. Static `?key=<apiKey>` on every request; the
 * `?session=<token>` from the root response is propagated down the tree.
 */
class GoogleTilesAuthProvider(val apiKey: String) : TilesetAuthProvider {

    override val discriminator: String get() = DISCRIMINATOR

    @Volatile
    private var sessionToken: String? = null

    override fun rewriteRequest(url: String, headers: MutableMap<String, String>): AuthedRequest {
        val withKey = ensureQueryParam(url, KEY_PARAM, apiKey)
        val withSession = sessionToken?.let { ensureQueryParam(withKey, SESSION_PARAM, it) } ?: withKey
        return AuthedRequest(withSession, headers)
    }

    override fun rewriteChildUri(parentUri: String, childUri: String): String {
        // Session is taken from THIS subtree's parent URI first — Google rotates the session
        // per subtree root, and reading the global [sessionToken] would race with concurrent
        // grafts of other subtrees. Falls back to [sessionToken] for the very first root
        // parse, where the root's own URI hasn't yet had a session baked in.
        val token = extractSessionFast(parentUri) ?: sessionToken
        val (hasKey, hasSession) = hasBothQueryParams(childUri, KEY_PARAM, if (token != null) SESSION_PARAM else null)
        if (hasKey && (token == null || hasSession)) return childUri
        val sep0 = if (childUri.indexOf('?') >= 0) '&' else '?'
        val builder = StringBuilder(childUri.length + 96).append(childUri)
        if (!hasKey) builder.append(sep0).append(KEY_PARAM).append('=').append(apiKey)
        if (token != null && !hasSession) {
            // sep0 was either appended above or already in childUri, so the next sep is '&'.
            builder.append('&').append(SESSION_PARAM).append('=').append(token)
        }
        return builder.toString()
    }

    /** Google answers an expired/mismatched `session=` with HTTP 400, not 401/403, so fold
     *  it in — otherwise a timed-out session never triggers recovery. */
    override fun isAuthRejection(statusCode: Int) = statusCode == 400 || statusCode == 401 || statusCode == 403

    /** Drop the cached session so the next root fetch is forced to bootstrap a fresh one.
     *  Called by the fetch queue after a content 401/403 — Google's session tokens are
     *  long-lived but not eternal, and on expiry every tile URL minted under the old
     *  session is dead. */
    override fun invalidateSessionState() { sessionToken = null }

    override fun observeTilesetResponse(requestedUrl: String, responseUrl: String, body: String) {
        // Body-advertised session wins: it's the token THIS subtree's children require.
        // URL fallback covers static tilesets that don't echo the token in the body. The
        // global [sessionToken] is now only the boot fallback for the first root fetch;
        // subsequent grafts source their session from parent-URI in [rewriteChildUri], so
        // concurrent observeTilesetResponse calls no longer cross-pollinate subtrees.
        SESSION_REGEX.find(body)?.groupValues?.get(1)?.let { sessionToken = it; return }
        extractSession(responseUrl)?.let { sessionToken = it; return }
        extractSession(requestedUrl)?.let { sessionToken = it }
    }

    private fun extractSession(url: String): String? = try {
        Uri.parse(url).getQueryParameter(SESSION_PARAM)
    } catch (_: Throwable) {
        null
    }

    /** Zero-alloc session= reader; same scan style as [hasQueryParam]. Returns null when
     *  the param is absent OR present without a value (flag-style). */
    private fun extractSessionFast(url: String): String? {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return null
        val end = url.indexOf('#', startIndex = queryStart + 1).let { if (it < 0) url.length else it }
        var idx = queryStart + 1
        val nameLen = SESSION_PARAM.length
        while (idx < end) {
            val tail = idx + nameLen
            if (tail < end && url[tail] == '=' && url.regionMatches(idx, SESSION_PARAM, 0, nameLen)) {
                val valueStart = tail + 1
                val valueEnd = url.indexOf('&', startIndex = valueStart).let { if (it < 0 || it > end) end else it }
                return if (valueEnd > valueStart) url.substring(valueStart, valueEnd) else null
            }
            val nextAmp = url.indexOf('&', startIndex = idx)
            if (nextAmp < 0 || nextAmp >= end) return null
            idx = nextAmp + 1
        }
        return null
    }

    /** Pure-string ensure-query-param; replaces the heavyweight Uri.parse + buildUpon
     *  path that showed up at >10% of parse-pool CPU. ASCII-safe params only. */
    private fun ensureQueryParam(url: String, name: String, value: String): String {
        if (hasQueryParam(url, name)) return url
        val separator = if (url.indexOf('?') >= 0) '&' else '?'
        return "$url$separator$name=$value"
    }

    /** One-shot scan of the query for two param names. [nameB] may be null. */
    private fun hasBothQueryParams(url: String, nameA: String, nameB: String?): Pair<Boolean, Boolean> {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return false to false
        val end = url.indexOf('#', startIndex = queryStart + 1).let { if (it < 0) url.length else it }
        var idx = queryStart + 1
        var foundA = false
        var foundB = nameB == null
        val lenA = nameA.length
        val lenB = nameB?.length ?: 0
        while (idx < end) {
            if (!foundA && segmentMatches(url, idx, end, nameA, lenA)) foundA = true
            if (!foundB && nameB != null && segmentMatches(url, idx, end, nameB, lenB)) foundB = true
            if (foundA && foundB) return true to true
            val nextAmp = url.indexOf('&', startIndex = idx)
            if (nextAmp < 0 || nextAmp >= end) break
            idx = nextAmp + 1
        }
        return foundA to foundB
    }

    /** True when `?name=`, `&name=`, or a flag-style `?name`/`&name` (no value) appears in
     *  the query portion of [url] before any fragment. Avoids substring allocation. */
    private fun hasQueryParam(url: String, name: String): Boolean {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return false
        val end = url.indexOf('#', startIndex = queryStart + 1).let { if (it < 0) url.length else it }
        var idx = queryStart + 1
        val nameLen = name.length
        while (idx < end) {
            if (segmentMatches(url, idx, end, name, nameLen)) return true
            val nextAmp = url.indexOf('&', startIndex = idx)
            if (nextAmp < 0 || nextAmp >= end) return false
            idx = nextAmp + 1
        }
        return false
    }

    /** [name] occupies the segment starting at [idx], ending at `=`, `&`, or [end]. */
    private fun segmentMatches(url: String, idx: Int, end: Int, name: String, nameLen: Int): Boolean {
        val tail = idx + nameLen
        if (tail > end) return false
        if (!url.regionMatches(idx, name, 0, nameLen)) return false
        return tail == end || url[tail] == '=' || url[tail] == '&'
    }

    companion object {
        const val DISCRIMINATOR = "GoogleTilesAuthProvider"
        private const val KEY_PARAM = "key"
        private const val SESSION_PARAM = "session"
        private val SESSION_REGEX = Regex("[?&]session=([^&\"'\\s]+)")
    }
}
