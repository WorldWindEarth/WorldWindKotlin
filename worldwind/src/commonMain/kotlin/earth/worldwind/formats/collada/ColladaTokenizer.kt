@file:OptIn(XmlUtilInternal::class)

package earth.worldwind.formats.collada

import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader

/** Growable primitive float buffer — avoids boxing and the giant `List<String>` token list. */
internal class FloatBuf(initial: Int = 1024) {
    var array = FloatArray(initial)
        private set
    var size = 0
        private set
    fun add(v: Float) {
        if (size == array.size) array = array.copyOf(array.size + (array.size shr 1).coerceAtLeast(1))
        array[size++] = v
    }
    fun toArray(): FloatArray = array.copyOf(size)
}

/** Growable primitive int buffer for `<p>`/`<vcount>` index streams. */
internal class IntBuf(initial: Int = 1024) {
    var array = IntArray(initial)
        private set
    var size = 0
        private set
    fun add(v: Int) {
        if (size == array.size) array = array.copyOf(array.size + (array.size shr 1).coerceAtLeast(1))
        array[size++] = v
    }
    fun toArray(): IntArray = array.copyOf(size)
}

internal enum class XmlToken { START, END, TEXT, EOF }

/**
 * Minimal pull tokenizer over a streaming [Reader] for the COLLADA XML subset (no namespaces we
 * use, no mixed-content in the elements we read). Structural elements are surfaced as START/END/
 * TEXT tokens; the heavy `<float_array>`/`<p>`/`<vcount>` bodies are consumed via [readFloats]/
 * [readInts] which tokenize numbers straight out of the char window into a primitive buffer —
 * never building a body String or the full-document String. This is what keeps a 159 MB `.dae`
 * within a mobile heap.
 */
internal class ColladaTokenizer(private val reader: Reader) {
    var name: String = ""
        private set
    var text: String = ""
        private set
    var selfClosing: Boolean = false
        private set
    private var attributes: Map<String, String> = emptyMap()

    private val buf = CharArray(8192)
    private var pos = 0
    private var limit = 0
    private var pendingEnd: String? = null
    private val sb = StringBuilder(32)

    fun attr(n: String): String? = attributes[n]

    /** Snapshot of the current START element's attributes (for building a structural DOM subtree). */
    fun copyAttributes(): Map<String, String> = if (attributes.isEmpty()) emptyMap() else HashMap(attributes)

    private fun fill(): Boolean {
        limit = reader.read(buf, 0, buf.size)
        pos = 0
        return limit > 0
    }

    private fun peek(): Int {
        if (pos >= limit && !fill()) return -1
        return buf[pos].code
    }

    private fun read(): Int {
        if (pos >= limit && !fill()) return -1
        return buf[pos++].code
    }

    fun next(): XmlToken {
        pendingEnd?.let { name = it; pendingEnd = null; return XmlToken.END }
        // Peek: a '<' starts a tag; anything else is text content up to the next '<'.
        val c = peek()
        if (c == -1) return XmlToken.EOF
        return if (c == '<'.code) readTag() else readText()
    }

    private fun readText(): XmlToken {
        sb.setLength(0)
        while (true) {
            val c = peek()
            if (c == -1 || c == '<'.code) break
            pos++
            sb.append(c.toChar())
        }
        text = decodeEntities(sb.toString())
        return XmlToken.TEXT
    }

    private fun readTag(): XmlToken {
        read() // consume '<'
        when (peek()) {
            '/'.code -> { read(); return readEndTag() }
            '?'.code -> { skipUntil("?>"); return next() }      // <?xml ...?>
            '!'.code -> {                                        // <!-- --> or <![CDATA[ ]]> or <!DOCTYPE
                read()
                if (peek() == '-'.code) { skipUntil("-->"); return next() }
                if (peek() == '['.code) { skipUntil("]]>"); return next() }
                skipUntil(">"); return next()
            }
        }
        return readStartTag()
    }

    private fun readStartTag(): XmlToken {
        sb.setLength(0)
        while (true) {
            val c = read()
            if (c == -1) break
            val ch = c.toChar()
            if (ch.isWhitespace() || ch == '>' || ch == '/') { backIfTagDelimiter(ch); break }
            sb.append(ch)
        }
        name = localName(sb.toString())
        attributes = readAttributes()
        if (selfClosing) pendingEnd = name // surface a synthetic END so depth tracking stays balanced
        return XmlToken.START
    }

    /** After the tag name, `read()` may have consumed a delimiter; re-handle it in the attr loop. */
    private var unread = -1
    private fun backIfTagDelimiter(ch: Char) { unread = ch.code }

    private fun nextRaw(): Int {
        if (unread != -1) { val u = unread; unread = -1; return u }
        return read()
    }

    private fun readAttributes(): Map<String, String> {
        var attrs: MutableMap<String, String>? = null
        selfClosing = false
        while (true) {
            var c = nextRaw()
            while (c != -1 && c.toChar().isWhitespace()) c = nextRaw()
            if (c == -1) break
            val ch = c.toChar()
            if (ch == '>') break
            if (ch == '/') { selfClosing = true; if (nextRaw().toChar() == '>') { } ; break }
            // attribute name
            sb.setLength(0)
            sb.append(ch)
            while (true) {
                val a = nextRaw()
                if (a == -1) break
                val ac = a.toChar()
                if (ac == '=' || ac.isWhitespace()) break
                sb.append(ac)
            }
            val attrName = localName(sb.toString())
            // skip to opening quote
            var q = nextRaw()
            while (q != -1 && q.toChar() != '"' && q.toChar() != '\'') q = nextRaw()
            val quote = q
            sb.setLength(0)
            while (true) {
                val v = nextRaw()
                if (v == -1 || v == quote) break
                sb.append(v.toChar())
            }
            if (attrs == null) attrs = HashMap(4)
            attrs[attrName] = decodeEntities(sb.toString())
        }
        return attrs ?: emptyMap()
    }

    private fun readEndTag(): XmlToken {
        sb.setLength(0)
        while (true) {
            val c = read()
            if (c == -1 || c == '>'.code) break
            sb.append(c.toChar())
        }
        name = localName(sb.toString().trim())
        return XmlToken.END
    }

    private fun skipUntil(marker: String) {
        // Sliding window over the last [marker.length] chars — correct for markers with repeated
        // prefixes (`-->`, `]]>`), unlike a single-index match which can overrun to EOF.
        val n = marker.length
        val window = CharArray(n)
        var len = 0
        while (true) {
            val c = read()
            if (c == -1) return
            val ch = c.toChar()
            if (len < n) window[len++] = ch else {
                for (k in 1 until n) window[k - 1] = window[k]
                window[n - 1] = ch
            }
            if (len == n) {
                var match = true
                for (j in 0 until n) if (window[j] != marker[j]) { match = false; break }
                if (match) return
            }
        }
    }

    private fun localName(raw: String): String {
        val colon = raw.indexOf(':')
        return if (colon >= 0) raw.substring(colon + 1) else raw
    }

    /**
     * Fast path: call immediately after the START of a numeric leaf element. Streams its body into
     * [sink] tokenizing floats out of the char window, then consumes the matching end tag. No body
     * String, no token list. Lenient parsing (bad token → 0f) so a malformed real-world model
     * degrades instead of crashing.
     */
    fun readFloats(sink: FloatBuf) {
        if (selfClosing) return // end tag already queued by readStartTag
        sb.setLength(0)
        while (true) {
            val c = peek()
            if (c == -1 || c == '<'.code) break
            pos++
            val ch = c.toChar()
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') flushFloat(sink)
            else sb.append(ch)
        }
        flushFloat(sink)
        consumeEndTag()
        pendingEnd = name // uniform: consumer always sees START → read → END
    }

    fun readInts(sink: IntBuf) {
        if (selfClosing) return // end tag already queued by readStartTag
        sb.setLength(0)
        while (true) {
            val c = peek()
            if (c == -1 || c == '<'.code) break
            pos++
            val ch = c.toChar()
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') flushInt(sink)
            else sb.append(ch)
        }
        flushInt(sink)
        consumeEndTag()
        pendingEnd = name // uniform: consumer always sees START → read → END
    }

    private fun flushFloat(sink: FloatBuf) {
        if (sb.isNotEmpty()) { sink.add(sb.toString().toFloatOrNull() ?: 0f); sb.setLength(0) }
    }

    private fun flushInt(sink: IntBuf) {
        if (sb.isNotEmpty()) { sink.add(sb.toString().toIntOrNull() ?: 0); sb.setLength(0) }
    }

    /** Consumes a `</name>` end tag that [readFloats]/[readInts] stopped in front of. */
    private fun consumeEndTag() {
        if (peek() != '<'.code) return
        read() // '<'
        if (peek() == '/'.code) read()
        while (true) {
            val c = read()
            if (c == -1 || c == '>'.code) break
        }
    }

    private fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }
}
