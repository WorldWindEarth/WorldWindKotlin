package earth.worldwind.layer.ogc3d.tileset

/**
 * Hand-rolled streaming parser for tileset.json documents. Reads only the fields the
 * engine consumes (everything else is skipped at the lexer level) and constructs the
 * [DocTileset] in-memory model directly — no @Serializable descriptors, no reflection,
 * no intermediate `JsonElement` tree.
 *
 * Replaces the kotlinx.serialization path in [TilesetParser.parse]. simpleperf showed
 * `Json.decodeFromString` + the chained `sanitizeNonFiniteStringLiterals` consuming
 * ~42% of parse-pool CPU on Google Photoreal workloads; this implementation collapses
 * both into a single linear pass.
 *
 * The lexer handles the quoted non-finite tokens (`"NaN"` / `"Infinity"` /
 * `"-Infinity"` / `"+Infinity"`) inline when a value position expects a number, so the
 * standalone sanitize step is gone. Bare-token forms (`NaN` / `Infinity` …) are also
 * accepted — kotlinx allowed both via `allowSpecialFloatingPointValues`.
 *
 * Tolerates explicit `null` in any position where a value would otherwise be required
 * (kotlinx behavior via `coerceInputValues`) by reading the `null` and substituting
 * the field's default. Unknown keys are skipped entirely via [skipValue].
 */
internal class StreamingTilesetReader(private val s: String) {
    private var i: Int = 0
    private val n: Int = s.length

    fun parseTileset(): DocTileset {
        var asset = DocAsset()
        var geometricError = 0.0
        var root = DocTile()
        var extensionsUsed: List<String> = emptyList()
        readObject { key ->
            when (key) {
                "asset" -> if (peekIsObject()) asset = parseAsset() else skipValue()
                "geometricError" -> geometricError = readDoubleOrNull() ?: 0.0
                "root" -> if (peekIsObject()) root = parseTile() else skipValue()
                "extensionsUsed" -> if (peekIsArray()) extensionsUsed = readStringArray() else skipValue()
                else -> skipValue()
            }
        }
        return DocTileset(asset = asset, geometricError = geometricError, root = root, extensionsUsed = extensionsUsed)
    }

    private fun parseAsset(): DocAsset {
        var version = "1.0"
        var gltfUpAxis: String? = null
        readObject { key ->
            when (key) {
                "version" -> if (peekIsString()) version = readString() else skipValue()
                "gltfUpAxis" -> if (peekIsString()) gltfUpAxis = readString() else skipValue()
                else -> skipValue()
            }
        }
        return DocAsset(version = version, gltfUpAxis = gltfUpAxis)
    }

    private fun parseTile(): DocTile {
        var boundingVolume = DocBoundingVolume()
        var geometricError = 0.0
        var refine: String? = null
        var transform: List<Double> = emptyList()
        var content: DocContent? = null
        var children: List<DocTile> = emptyList()
        var viewerRequestVolume: DocBoundingVolume? = null
        var implicitTiling: ImplicitTilingDescription? = null
        readObject { key ->
            when (key) {
                "boundingVolume" -> if (peekIsObject()) boundingVolume = parseBoundingVolume() else skipValue()
                "geometricError" -> geometricError = readDoubleOrNull() ?: 0.0
                "refine" -> if (peekIsString()) refine = readString() else skipValue()
                "transform" -> if (peekIsArray()) transform = readDoubleArray() else skipValue()
                "content" -> if (peekIsObject()) content = parseContent() else skipValue()
                "children" -> if (peekIsArray()) children = readArrayOf { parseTile() } else skipValue()
                "viewerRequestVolume" -> if (peekIsObject()) viewerRequestVolume = parseBoundingVolume() else skipValue()
                "implicitTiling" -> if (peekIsObject()) implicitTiling = parseImplicitTiling() else skipValue()
                else -> skipValue()
            }
        }
        return DocTile(
            boundingVolume = boundingVolume,
            geometricError = geometricError,
            refine = refine,
            transform = transform,
            content = content,
            children = children,
            viewerRequestVolume = viewerRequestVolume,
            implicitTiling = implicitTiling,
        )
    }

    private fun parseBoundingVolume(): DocBoundingVolume {
        var region: List<Double> = emptyList()
        var box: List<Double> = emptyList()
        var sphere: List<Double> = emptyList()
        readObject { key ->
            when (key) {
                "region" -> if (peekIsArray()) region = readDoubleArray() else skipValue()
                "box" -> if (peekIsArray()) box = readDoubleArray() else skipValue()
                "sphere" -> if (peekIsArray()) sphere = readDoubleArray() else skipValue()
                else -> skipValue()
            }
        }
        return DocBoundingVolume(region = region, box = box, sphere = sphere)
    }

    private fun parseContent(): DocContent {
        var uri: String? = null
        var urlLegacy: String? = null
        var boundingVolume: DocBoundingVolume? = null
        readObject { key ->
            when (key) {
                "uri" -> if (peekIsString()) uri = readString() else skipValue()
                "url" -> if (peekIsString()) urlLegacy = readString() else skipValue()
                "boundingVolume" -> if (peekIsObject()) boundingVolume = parseBoundingVolume() else skipValue()
                else -> skipValue()
            }
        }
        return DocContent(uri = uri, urlLegacy = urlLegacy, boundingVolume = boundingVolume)
    }

    private fun parseImplicitTiling(): ImplicitTilingDescription {
        var subdivisionScheme = SubdivisionScheme.QUADTREE
        var subtreeLevels = 1
        var availableLevels = 1
        var subtrees = ImplicitUriTemplate()
        readObject { key ->
            when (key) {
                "subdivisionScheme" -> {
                    if (peekIsString()) {
                        val str = readString()
                        subdivisionScheme = if (str == "OCTREE") SubdivisionScheme.OCTREE else SubdivisionScheme.QUADTREE
                    } else skipValue()
                }
                "subtreeLevels" -> subtreeLevels = (readDoubleOrNull() ?: 1.0).toInt()
                "availableLevels" -> availableLevels = (readDoubleOrNull() ?: 1.0).toInt()
                "subtrees" -> if (peekIsObject()) subtrees = parseImplicitUriTemplate() else skipValue()
                else -> skipValue()
            }
        }
        return ImplicitTilingDescription(
            subdivisionScheme = subdivisionScheme,
            subtreeLevels = subtreeLevels,
            availableLevels = availableLevels,
            subtrees = subtrees,
        )
    }

    private fun parseImplicitUriTemplate(): ImplicitUriTemplate {
        var uri = ""
        readObject { key ->
            when (key) {
                "uri" -> if (peekIsString()) uri = readString() else skipValue()
                else -> skipValue()
            }
        }
        return ImplicitUriTemplate(uri = uri)
    }

    // ===== Generic object / array drivers =====

    /** Reads `{ key: value, key: value, ... }`. Yields each unprocessed key to [body];
     *  the lambda is responsible for consuming the value (or calling [skipValue]). */
    private inline fun readObject(body: (String) -> Unit) {
        skipWs()
        if (i < n && s[i] == 'n') {
            // explicit null in object position — kotlinx's coerceInputValues maps to default
            consumeKeyword("null")
            return
        }
        expect('{')
        skipWs()
        if (consume('}')) return
        while (true) {
            val key = readString()
            skipWs()
            expect(':')
            body(key)
            skipWs()
            if (consume(',')) { skipWs(); continue }
            expect('}')
            return
        }
    }

    /** Reads `[elem, elem, ...]` collecting elements via [readElement]. */
    private inline fun <T> readArrayOf(readElement: () -> T): List<T> {
        expect('[')
        skipWs()
        if (consume(']')) return emptyList()
        val list = ArrayList<T>()
        while (true) {
            list.add(readElement())
            skipWs()
            if (consume(',')) { skipWs(); continue }
            expect(']')
            return list
        }
    }

    private fun readDoubleArray(): List<Double> {
        expect('[')
        skipWs()
        if (consume(']')) return emptyList()
        val list = ArrayList<Double>()
        while (true) {
            list.add(readDoubleOrNull() ?: 0.0)
            skipWs()
            if (consume(',')) { skipWs(); continue }
            expect(']')
            return list
        }
    }

    private fun readStringArray(): List<String> {
        expect('[')
        skipWs()
        if (consume(']')) return emptyList()
        val list = ArrayList<String>()
        while (true) {
            list.add(readString())
            skipWs()
            if (consume(',')) { skipWs(); continue }
            expect(']')
            return list
        }
    }

    // ===== Value lexer =====

    /** Reads a string literal. Handles `\"`, `\\`, `\/`, `\n`, `\r`, `\t`, `\b`, `\f`,
     *  and `\uXXXX`. Uses substring-fast-path for the (overwhelmingly common) case
     *  with no escapes — zero allocation beyond the resulting String. */
    private fun readString(): String {
        skipWs()
        if (i >= n || s[i] != '"') parseError("expected string")
        i++
        val start = i
        var sb: StringBuilder? = null
        while (i < n) {
            val c = s[i]
            if (c == '"') {
                val result = if (sb != null) sb.toString() else s.substring(start, i)
                i++
                return result
            }
            if (c == '\\') {
                if (sb == null) {
                    sb = StringBuilder(i - start + 16)
                    sb.append(s, start, i)
                }
                i++
                if (i >= n) parseError("unterminated string escape")
                when (val esc = s[i]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('')
                    'u' -> {
                        if (i + 4 >= n) parseError("bad unicode escape")
                        val code = runCatching { s.substring(i + 1, i + 5).toInt(16) }
                            .getOrElse { parseError("bad unicode escape") }
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> parseError("bad escape \\$esc")
                }
                i++
            } else {
                if (sb != null) sb.append(c)
                i++
            }
        }
        parseError("unterminated string")
    }

    /** Read a JSON number or any of the non-finite variants (bare or quoted).
     *  Returns null when the slot held an explicit `null` token. */
    private fun readDoubleOrNull(): Double? {
        skipWs()
        if (i >= n) parseError("expected number")
        val c = s[i]
        when (c) {
            '"' -> {
                val str = readString()
                return when (str) {
                    "NaN" -> Double.NaN
                    "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
                    "-Infinity" -> Double.NEGATIVE_INFINITY
                    else -> str.toDoubleOrNull() ?: parseError("bad numeric string '$str'")
                }
            }
            'n' -> { consumeKeyword("null"); return null }
            'N' -> { consumeKeyword("NaN"); return Double.NaN }
            'I' -> { consumeKeyword("Infinity"); return Double.POSITIVE_INFINITY }
        }
        val start = i
        if (c == '-' || c == '+') {
            i++
            if (i < n && s[i] == 'I') { consumeKeyword("Infinity"); return if (c == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY }
        }
        while (i < n) {
            val ch = s[i]
            if (ch in '0'..'9' || ch == '.' || ch == 'e' || ch == 'E' || ch == '-' || ch == '+') i++
            else break
        }
        if (i == start) parseError("expected number")
        val token = s.substring(start, i)
        return token.toDoubleOrNull() ?: parseError("bad number '$token'")
    }

    /** Skip a JSON value (string / number / object / array / true / false / null,
     *  plus bare NaN / Infinity variants). Caller has already consumed `key:`. */
    private fun skipValue() {
        skipWs()
        if (i >= n) return
        when (s[i]) {
            '{' -> skipObject()
            '[' -> skipArray()
            '"' -> { readString() }
            't' -> consumeKeyword("true")
            'f' -> consumeKeyword("false")
            'n' -> consumeKeyword("null")
            'N' -> consumeKeyword("NaN")
            'I' -> consumeKeyword("Infinity")
            else -> {
                // number, possibly preceded by sign + Infinity literal
                if (s[i] == '-' || s[i] == '+') {
                    i++
                    if (i < n && s[i] == 'I') { consumeKeyword("Infinity"); return }
                }
                while (i < n) {
                    val ch = s[i]
                    if (ch in '0'..'9' || ch == '.' || ch == 'e' || ch == 'E' || ch == '-' || ch == '+') i++
                    else break
                }
            }
        }
    }

    private fun skipObject() {
        expect('{')
        skipWs()
        if (consume('}')) return
        while (true) {
            readString()
            skipWs()
            expect(':')
            skipValue()
            skipWs()
            if (consume(',')) { skipWs(); continue }
            expect('}')
            return
        }
    }

    private fun skipArray() {
        expect('[')
        skipWs()
        if (consume(']')) return
        while (true) {
            skipValue()
            skipWs()
            if (consume(',')) { skipWs(); continue }
            expect(']')
            return
        }
    }

    // ===== Lexer primitives =====

    private fun skipWs() {
        while (i < n) {
            val c = s[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
        }
    }

    private fun expect(c: Char) {
        if (i >= n || s[i] != c) parseError("expected '$c'")
        i++
    }

    private fun consume(c: Char): Boolean {
        if (i < n && s[i] == c) { i++; return true }
        return false
    }

    private fun consumeKeyword(kw: String) {
        if (i + kw.length > n || !s.regionMatches(i, kw, 0, kw.length)) parseError("expected '$kw'")
        i += kw.length
    }

    private fun peekIsObject(): Boolean { skipWs(); return i < n && s[i] == '{' }
    private fun peekIsArray(): Boolean { skipWs(); return i < n && s[i] == '[' }
    private fun peekIsString(): Boolean { skipWs(); return i < n && s[i] == '"' }

    private fun parseError(msg: String): Nothing {
        val ctxStart = maxOf(0, i - 30)
        val ctxEnd = minOf(n, i + 30)
        throw IllegalStateException(
            "tileset.json parse error at offset $i: $msg; near \"...${s.substring(ctxStart, ctxEnd)}...\""
        )
    }
}
