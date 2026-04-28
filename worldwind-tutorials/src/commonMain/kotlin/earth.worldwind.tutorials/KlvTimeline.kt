package earth.worldwind.tutorials

/**
 * One KLV (STANAG 4609 / MISB ST 0601) telemetry sample. Beyond the four image-frame
 * corners on the ground (`tlLat..blLon` in degrees), each sample carries the **camera
 * pose** at the moment of capture — sensor world position, platform body angles, sensor
 * angles relative to the platform body, and field of view — which is what an honest
 * perspective projection onto terrain needs.
 *
 * Pose fields are [Double.NaN] when absent (some lighter-weight UAS payloads omit them
 * entirely, others fill some but not others). Consumers should treat NaN as "interpolate
 * from neighbours" or "compute from geometry" (e.g., estimate altitude from frame-center
 * distance + relative-elevation angle).
 *
 * Mutable: a single [KlvSample] is reused per [KlvTimeline.sampleAt] call to avoid
 * per-tick heap churn. Treat parsed-list samples as read-only.
 */
class KlvSample(
    var tMs: Long = 0L,
    // Image-frame corners on the ground (degrees).
    var tlLat: Double = 0.0, var tlLon: Double = 0.0,
    var trLat: Double = 0.0, var trLon: Double = 0.0,
    var brLat: Double = 0.0, var brLon: Double = 0.0,
    var blLat: Double = 0.0, var blLon: Double = 0.0,
    // Sensor (camera) world position. NaN if absent.
    //  * `sAlt` = MSL altitude (KLV Tag 15), meters above mean sea level.
    //  * `sHae` = ellipsoid height (KLV Tag 75), meters above the WGS84 ellipsoid.
    // Some platforms (e.g. BlackHornet) emit only [sHae]. Consumers should prefer [sAlt]
    // when available and fall back to [sHae] otherwise; the geoid undulation between them
    // is small enough (~tens of metres) for typical drone-footprint projections.
    var sLat: Double = Double.NaN, var sLon: Double = Double.NaN,
    var sAlt: Double = Double.NaN, var sHae: Double = Double.NaN,
    // Platform body angles (degrees). NaN if absent.
    var pHdg: Double = Double.NaN, var pPit: Double = Double.NaN, var pRol: Double = Double.NaN,
    // Sensor look angles relative to platform body (degrees). NaN if absent.
    var rAz: Double = Double.NaN, var rEl: Double = Double.NaN, var rRol: Double = Double.NaN,
    // Sensor field of view (degrees). NaN if absent.
    var hFov: Double = Double.NaN, var vFov: Double = Double.NaN,
)

/**
 * Pre-extracted KLV timeline produced by `tools/ExtractKlv.java` from an MPEG-TS with
 * embedded ST 0601 metadata. JSON is a flat array of samples (~10 Hz for the bundled
 * drone clip).
 *
 * [sampleAt] writes a smoothly-interpolated sample into a caller-supplied [KlvSample]:
 *  * **Position channels** (corners, sensor lat/lon/alt) interpolate via Catmull-Rom for
 *    C¹-continuous motion through each KLV sample. Linear when [useCatmullRom] is false.
 *  * **Angular channels** (heading, pitch, roll, az, el) use *wrap-aware* linear blend —
 *    crossing 359°→1° (or ±180°) takes the shorter arc, not a 358° spin.
 *  * **NaN channels** propagate as NaN; consumers decide how to handle missing data
 *    (estimate from geometry, fall back to a default, etc.).
 */
class KlvTimeline(
    val samples: List<KlvSample>,
    /** Use a centripetal Catmull-Rom spline for position channels instead of linear. */
    private val useCatmullRom: Boolean = true,
) {
    val durationMs: Long get() = samples.lastOrNull()?.tMs ?: 0L

    /** Cached last lookup index — playback is monotonic, so adjacent ticks usually hit the same span. */
    private var lastLo = 0

    /**
     * Resample the timeline at [tMs] and write the interpolated sample into [into]. The
     * same [KlvSample] instance can be reused across calls — no allocations on the hot
     * path. Returns `into` (same object) for chaining; returns `null` only when the
     * timeline is empty.
     */
    fun sampleAt(tMs: Long, into: KlvSample = KlvSample()): KlvSample? {
        if (samples.isEmpty()) return null
        if (tMs <= samples.first().tMs) return into.also { copyFrom(samples.first(), it, tMs) }
        if (tMs >= samples.last().tMs) return into.also { copyFrom(samples.last(), it, tMs) }

        val lo = findLowerIndex(tMs)
        val a = samples[lo]
        val b = samples[lo + 1]
        val span = (b.tMs - a.tMs).toDouble()
        if (span <= 0.0) return into.also { copyFrom(a, it, tMs) }
        val f = (tMs - a.tMs).toDouble() / span

        if (useCatmullRom) {
            val p0 = if (lo > 0) samples[lo - 1] else a
            val p3 = if (lo + 2 < samples.size) samples[lo + 2] else b
            interpolatePositions(p0, a, b, p3, f, into) { x0, x1, x2, x3, t -> catmullRom(x0, x1, x2, x3, t) }
        } else {
            interpolatePositions(a, a, b, b, f, into) { _, x1, x2, _, t -> lerp(x1, x2, t) }
        }
        // Angular channels — wrap-aware linear blend. Catmull-Rom for angles needs careful
        // wrap handling; linear is simple and fine at our 10 Hz sample rate.
        into.pHdg = lerpAngleDeg(a.pHdg, b.pHdg, f, period = 360.0)
        into.pPit = lerpAngle(a.pPit, b.pPit, f) // ±20°, no wrap
        into.pRol = lerpAngle(a.pRol, b.pRol, f) // ±50°, no wrap
        into.rAz = lerpAngleDeg(a.rAz, b.rAz, f, period = 360.0)
        into.rEl = lerpAngle(a.rEl, b.rEl, f)    // ±180° but in practice ±90° for downward-looking
        into.rRol = lerpAngleDeg(a.rRol, b.rRol, f, period = 360.0)
        // FOV interpolates linearly — typically constant, but caller might zoom.
        into.hFov = lerpNullable(a.hFov, b.hFov, f)
        into.vFov = lerpNullable(a.vFov, b.vFov, f)
        into.tMs = tMs
        return into
    }

    /** Find the rightmost index `lo` such that `samples[lo].tMs <= tMs <= samples[lo+1].tMs`. */
    private fun findLowerIndex(tMs: Long): Int {
        if (lastLo + 1 < samples.size && tMs in samples[lastLo].tMs..samples[lastLo + 1].tMs) return lastLo
        if (lastLo + 2 < samples.size && tMs in samples[lastLo + 1].tMs..samples[lastLo + 2].tMs) {
            lastLo += 1; return lastLo
        }
        var lo = 0
        var hi = samples.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].tMs <= tMs) lo = mid else hi = mid
        }
        lastLo = lo
        return lo
    }

    private fun copyFrom(src: KlvSample, dst: KlvSample, tMs: Long) {
        dst.tMs = tMs
        dst.tlLat = src.tlLat; dst.tlLon = src.tlLon
        dst.trLat = src.trLat; dst.trLon = src.trLon
        dst.brLat = src.brLat; dst.brLon = src.brLon
        dst.blLat = src.blLat; dst.blLon = src.blLon
        dst.sLat = src.sLat; dst.sLon = src.sLon
        dst.sAlt = src.sAlt; dst.sHae = src.sHae
        dst.pHdg = src.pHdg; dst.pPit = src.pPit; dst.pRol = src.pRol
        dst.rAz = src.rAz; dst.rEl = src.rEl; dst.rRol = src.rRol
        dst.hFov = src.hFov; dst.vFov = src.vFov
    }

    /**
     * Apply [interp] to each lat/lon/alt position channel. The lambda receives 4 control
     * points for Catmull-Rom (caller passes `(p0,a,b,p3)`) or duplicates `a→a` and `b→b`
     * for linear (`(a,a,b,b)`).
     */
    private inline fun interpolatePositions(
        p0: KlvSample, p1: KlvSample, p2: KlvSample, p3: KlvSample, f: Double, into: KlvSample,
        interp: (x0: Double, x1: Double, x2: Double, x3: Double, t: Double) -> Double,
    ) {
        into.tlLat = interp(p0.tlLat, p1.tlLat, p2.tlLat, p3.tlLat, f)
        into.tlLon = interp(p0.tlLon, p1.tlLon, p2.tlLon, p3.tlLon, f)
        into.trLat = interp(p0.trLat, p1.trLat, p2.trLat, p3.trLat, f)
        into.trLon = interp(p0.trLon, p1.trLon, p2.trLon, p3.trLon, f)
        into.brLat = interp(p0.brLat, p1.brLat, p2.brLat, p3.brLat, f)
        into.brLon = interp(p0.brLon, p1.brLon, p2.brLon, p3.brLon, f)
        into.blLat = interp(p0.blLat, p1.blLat, p2.blLat, p3.blLat, f)
        into.blLon = interp(p0.blLon, p1.blLon, p2.blLon, p3.blLon, f)
        into.sLat = interpNullable(p0.sLat, p1.sLat, p2.sLat, p3.sLat, f, interp)
        into.sLon = interpNullable(p0.sLon, p1.sLon, p2.sLon, p3.sLon, f, interp)
        into.sAlt = interpNullable(p0.sAlt, p1.sAlt, p2.sAlt, p3.sAlt, f, interp)
        into.sHae = interpNullable(p0.sHae, p1.sHae, p2.sHae, p3.sHae, f, interp)
    }

    /** Spline-interp four channels but pass NaN through (NaN in any → NaN out). */
    private inline fun interpNullable(
        p0: Double, p1: Double, p2: Double, p3: Double, f: Double,
        interp: (Double, Double, Double, Double, Double) -> Double,
    ): Double = if (p1.isNaN() || p2.isNaN()) Double.NaN else interp(p0, p1, p2, p3, f)

    private fun lerpNullable(a: Double, b: Double, f: Double): Double =
        if (a.isNaN() || b.isNaN()) Double.NaN else a + (b - a) * f

    private fun lerpAngle(a: Double, b: Double, f: Double): Double =
        if (a.isNaN() || b.isNaN()) Double.NaN else a + (b - a) * f

    /**
     * Wrap-aware angle blend in degrees: takes the shorter arc when [a] and [b] straddle
     * the [period] boundary (e.g. heading 358° → 2° interpolates through 0°, not 180°).
     * Returns NaN if either endpoint is NaN.
     */
    private fun lerpAngleDeg(a: Double, b: Double, f: Double, period: Double): Double {
        if (a.isNaN() || b.isNaN()) return Double.NaN
        var d = b - a
        val half = period * 0.5
        if (d > half) d -= period
        if (d < -half) d += period
        return ((a + d * f) % period + period) % period
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f

    private fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, f: Double): Double {
        val f2 = f * f
        val f3 = f2 * f
        return 0.5 * (
            (2.0 * p1) +
                (-p0 + p2) * f +
                (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * f2 +
                (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * f3
            )
    }

    companion object {
        /**
         * Parse the JSON produced by `tools/ExtractKlv.java`. The schema is fixed (no
         * whitespace / no field reordering, NaN fields written as the JSON literal `null`),
         * so a small token-by-token parser is enough — kotlinx.serialization would be
         * overkill for the tutorials module.
         */
        fun parse(json: String): KlvTimeline {
            val samples = mutableListOf<KlvSample>()
            var i = 0
            while (i < json.length) {
                val open = json.indexOf('{', i)
                if (open < 0) break
                val close = json.indexOf('}', open)
                if (close < 0) break
                samples += parseRecord(json, open + 1, close)
                i = close + 1
            }
            return KlvTimeline(samples)
        }

        /** Parse one `"key":value` body between curly braces into a [KlvSample]. */
        private fun parseRecord(json: String, start: Int, endExclusive: Int): KlvSample {
            val s = KlvSample()
            // Walk through the body, tokenizing `"key": value | [n,n] | null`.
            var p = start
            while (p < endExclusive) {
                // Skip to next quote (start of key) or break.
                while (p < endExclusive && json[p] != '"') p++
                if (p >= endExclusive) break
                val keyEnd = json.indexOf('"', p + 1)
                if (keyEnd < 0 || keyEnd >= endExclusive) break
                val key = json.substring(p + 1, keyEnd)
                p = keyEnd + 1
                while (p < endExclusive && json[p] != ':') p++
                p++ // skip ':'
                while (p < endExclusive && json[p] == ' ') p++

                if (p < endExclusive && json[p] == '[') {
                    // Two-element array: latitude, longitude.
                    val arrEnd = json.indexOf(']', p)
                    val (a, b) = parsePair(json, p + 1, arrEnd)
                    when (key) {
                        "tl" -> { s.tlLat = a; s.tlLon = b }
                        "tr" -> { s.trLat = a; s.trLon = b }
                        "br" -> { s.brLat = a; s.brLon = b }
                        "bl" -> { s.blLat = a; s.blLon = b }
                    }
                    p = arrEnd + 1
                } else {
                    // Scalar: number, integer (tMs), or `null`.
                    val tokenEnd = scanScalarEnd(json, p, endExclusive)
                    val tok = json.substring(p, tokenEnd)
                    if (key == "tMs") s.tMs = tok.toLong()
                    else assignScalar(s, key, if (tok == "null") Double.NaN else tok.toDouble())
                    p = tokenEnd
                }
                while (p < endExclusive && (json[p] == ',' || json[p] == ' ')) p++
            }
            return s
        }

        private fun parsePair(json: String, start: Int, endExclusive: Int): Pair<Double, Double> {
            val comma = json.indexOf(',', start)
            val a = json.substring(start, comma).trim().toDouble()
            val b = json.substring(comma + 1, endExclusive).trim().toDouble()
            return a to b
        }

        private fun scanScalarEnd(json: String, start: Int, endExclusive: Int): Int {
            var p = start
            while (p < endExclusive && json[p] != ',' && json[p] != '}') p++
            return p
        }

        private fun assignScalar(s: KlvSample, key: String, v: Double) {
            when (key) {
                "sLat" -> s.sLat = v
                "sLon" -> s.sLon = v
                "sAlt" -> s.sAlt = v
                "sHae" -> s.sHae = v
                "pHdg" -> s.pHdg = v
                "pPit" -> s.pPit = v
                "pRol" -> s.pRol = v
                "rAz" -> s.rAz = v
                "rEl" -> s.rEl = v
                "rRol" -> s.rRol = v
                "hFov" -> s.hFov = v
                "vFov" -> s.vFov = v
            }
        }
    }
}
