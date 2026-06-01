package earth.worldwind.layer.ogc3d.content.spz

import earth.worldwind.layer.ogc3d.content.GaussianLoader
import earth.worldwind.layer.ogc3d.content.GaussianPayload
import kotlin.concurrent.Volatile
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Concrete [GaussianLoader] for Niantic's SPZ wire format — the de-facto Gaussian-splat
 * exchange format. Spec: <https://github.com/nianticlabs/spz>. Decoder matches the
 * reference `src/cc/load-spz.cc` for versions 1, 2, and 3.
 *
 * Wire layout: 16-byte header (`NGSP` magic, version, numPoints, shDegree, fractionalBits,
 * flags, reserved) followed by a gzipped per-splat body. Body order: positions (9 B each,
 * int24 fixed-point), alpha (1 B), colour (3 B DC SH), scale (3 B log), rotation
 * (3 B v1/v2 or 4 B v3 smallest-three), optional higher SH bands.
 *
 * V3 differences from v1/v2: scale packs to log-domain via `(g.scale + 10) * 16` (vs.
 * v1's `(g.scale + 127.5) * 16`), and rotation uses smallest-three quaternion encoding
 * (4 B holding three 10-bit fields + a 2-bit largest-index, vs. v1's 3 B XYZ + W from
 * sqrt). Using v1 formulas on v3 data yields ~8× oversized splats with random orientation.
 *
 * @param defaultRtcCenter Optional global RTC center applied when the payload omits one.
 *  Production SPZ writers typically embed the RTC center in the surrounding 3D-Tiles
 *  content envelope; this constructor parameter is the fallback for stand-alone `.spz`.
 * @param options Per-attribute skip flags. Default decodes every field; pass
 *  [SpzDecodeOptions.ROUND_SPLAT] when the renderer only needs centres + scales + rgba.
 */
class SpzGaussianLoader(
    private val defaultRtcCenter: DoubleArray? = null,
    private val options: SpzDecodeOptions = SpzDecodeOptions.FULL,
) : GaussianLoader {

    override fun supports(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_SIZE) return false
        // 'N' 'G' 'S' 'P' little-endian = 0x5053474e
        return bytes[0] == 'N'.code.toByte() &&
            bytes[1] == 'G'.code.toByte() &&
            bytes[2] == 'S'.code.toByte() &&
            bytes[3] == 'P'.code.toByte()
    }

    override fun parse(bytes: ByteArray): GaussianPayload {
        require(supports(bytes)) { "not an SPZ payload" }
        val inflater = inflater
            ?: error("SpzGaussianLoader.inflater not set — register a platform inflater at startup")
        val (version, numPoints, shDegree, fractionalBits) = readHeader(bytes)
        val compressedSize = bytes.size - HEADER_SIZE
        val compressed = ByteArray(compressedSize).also { bytes.copyInto(it, 0, HEADER_SIZE, bytes.size) }
        val body = inflater.inflate(compressed)
        return decodeBody(body, numPoints, shDegree, fractionalBits, version)
    }

    /** Decode an SPZ stream whose body is already inflated — the 16-byte header is in
     *  clear at byte 0 and the per-splat body follows uncompressed. Used by the glTF
     *  wrapper (`KHR_gaussian_splatting_compression_spz_2`), which gzips header+body as a
     *  single bufferView; raw `.spz` files keep the header in clear with only the body
     *  gzipped and go through [parse] instead. */
    internal fun parseUncompressedStream(bytes: ByteArray): GaussianPayload {
        require(supports(bytes)) { "not an SPZ payload" }
        val (version, numPoints, shDegree, fractionalBits) = readHeader(bytes)
        val body = ByteArray(bytes.size - HEADER_SIZE).also { bytes.copyInto(it, 0, HEADER_SIZE, bytes.size) }
        return decodeBody(body, numPoints, shDegree, fractionalBits, version)
    }

    private data class Header(val version: Int, val numPoints: Int, val shDegree: Int, val fractionalBits: Int)

    private fun readHeader(bytes: ByteArray): Header {
        val version = bytes.readUInt32LE(4)
        require(version in 1..3) { "unsupported SPZ version: $version" }
        val numPoints = bytes.readUInt32LE(8)
        require(numPoints in 0..MAX_POINTS) { "implausible SPZ point count: $numPoints" }
        val shDegree = bytes[12].toInt() and 0xFF
        require(shDegree in 0..3) { "SPZ shDegree out of range: $shDegree" }
        val fractionalBits = bytes[13].toInt() and 0xFF
        // `1 shl fractionalBits` wraps mod 32; bound to spec range.
        require(fractionalBits in 0..24) { "SPZ fractionalBits out of range: $fractionalBits" }
        return Header(version, numPoints, shDegree, fractionalBits)
    }

    /** Internal so tests can exercise the wire format without a real gzip provider. */
    internal fun decodeBody(
        body: ByteArray, numPoints: Int, shDegree: Int, fractionalBits: Int, version: Int = 1,
    ): GaussianPayload {
        val coeffsPerChannel = ((shDegree + 1) * (shDegree + 1))
        // v1/v2: rotation packed as XYZ uint8 with W reconstructed from sum-of-squares.
        // v3:    rotation packed as 4-byte smallest-three (top 2 bits = largest-component
        //        index; three 10-bit (sign+9-bit-mag) fields encode the other components).
        val rotationStride = if (version >= 3) 4 else 3
        // Size check in Long; per-point Int math is safe once body size is validated.
        val expected = numPoints.toLong() * 9L + // positions
            numPoints.toLong() + // alpha
            numPoints.toLong() * 3L + // colour DC
            numPoints.toLong() * 3L + // scale
            numPoints.toLong() * rotationStride.toLong() + // rotation
            numPoints.toLong() * 3L * (coeffsPerChannel - 1).toLong() // sh extras
        require(body.size.toLong() >= expected) {
            "SPZ body smaller than expected: ${body.size} < $expected for shDegree=$shDegree version=$version"
        }
        val shExtraBytes = numPoints * 3 * (coeffsPerChannel - 1)

        val positions = FloatArray(numPoints * 3)
        val alphas = if (options.skipOpacities) FloatArray(0) else FloatArray(numPoints)
        val rgba = ByteArray(numPoints * 4)
        val scales = FloatArray(numPoints * 3)
        val rotations = if (options.skipRotations) FloatArray(0) else FloatArray(numPoints * 4)

        val scaleDiv = 1f / (1 shl fractionalBits).toFloat()
        var cursor = 0

        for (i in 0 until numPoints) {
            // 3 × int24 LE → float
            for (axis in 0..2) {
                val a = body[cursor].toInt() and 0xFF
                val b = body[cursor + 1].toInt() and 0xFF
                val c = body[cursor + 2].toInt() and 0xFF
                var raw = a or (b shl 8) or (c shl 16)
                // Sign-extend from 24 bits.
                if (raw and 0x800000 != 0) raw = raw or 0xFF000000.toInt()
                positions[i * 3 + axis] = raw * scaleDiv
                cursor += 3
            }
        }

        // Alpha bytes precede colour; remember the offset so the colour pass can still pull
        // raw alpha for rgba[3] when [skipOpacities] short-circuits the FloatArray fill.
        val alphaCursor = cursor
        if (options.skipOpacities) {
            cursor += numPoints
        } else {
            for (i in 0 until numPoints) {
                alphas[i] = OPACITY_LUT[body[cursor].toInt() and 0xFF]
                cursor += 1
            }
        }

        // DC colour: pre-baked [COLOR_LUT] folds the SH degree-0 render and the clamp/scale
        // round-trip. RGBA alpha is the alpha byte through the same coerceIn(0,1)*255 path
        // ([OPACITY_TO_RGBA_LUT]), independent of [skipOpacities].
        for (i in 0 until numPoints) {
            rgba[i * 4 + 0] = COLOR_LUT[body[cursor].toInt() and 0xFF]
            rgba[i * 4 + 1] = COLOR_LUT[body[cursor + 1].toInt() and 0xFF]
            rgba[i * 4 + 2] = COLOR_LUT[body[cursor + 2].toInt() and 0xFF]
            rgba[i * 4 + 3] = OPACITY_TO_RGBA_LUT[body[alphaCursor + i].toInt() and 0xFF]
            cursor += 3
        }

        val scaleLut = if (version >= 3) SCALE_LUT_V3 else SCALE_LUT_V12
        for (i in 0 until numPoints) {
            scales[i * 3 + 0] = scaleLut[body[cursor].toInt() and 0xFF]
            scales[i * 3 + 1] = scaleLut[body[cursor + 1].toInt() and 0xFF]
            scales[i * 3 + 2] = scaleLut[body[cursor + 2].toInt() and 0xFF]
            cursor += 3
        }

        if (options.skipRotations) {
            cursor += numPoints * rotationStride
        } else if (version >= 3) {
            // V3 smallest-three quaternion: bits 30..31 = largest-component index,
            // bits 0..29 = three 10-bit (sign + 9-bit-mag scaled by 1/sqrt(2) / 511) fields
            // for the other components in MSB-aligned order. Largest is `sqrt(1-Σsq)`,
            // canonicalised positive (no sign sent). Mirrors `unpackQuaternionSmallestThree`.
            for (i in 0 until numPoints) {
                val b0 = body[cursor].toInt() and 0xFF
                val b1 = body[cursor + 1].toInt() and 0xFF
                val b2 = body[cursor + 2].toInt() and 0xFF
                val b3 = body[cursor + 3].toInt() and 0xFF
                var comp = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                val iLargest = (comp ushr 30) and 0x3
                var sumSq = 0f
                for (axis in 3 downTo 0) {
                    if (axis != iLargest) {
                        val mag = comp and SMALLEST3_MASK
                        val negbit = (comp ushr 9) and 0x1
                        comp = comp ushr 10
                        var v = V3_QUAT_MAG_LUT[mag]
                        if (negbit == 1) v = -v
                        rotations[i * 4 + axis] = v
                        sumSq += v * v
                    }
                }
                rotations[i * 4 + iLargest] = sqrt((1f - sumSq).coerceAtLeast(0f))
                cursor += 4
            }
        } else {
            // SPZ v1/v2: XYZ stored, W reconstructed as positive sqrt.
            for (i in 0 until numPoints) {
                val x = V12_QUAT_LUT[body[cursor].toInt() and 0xFF]
                val y = V12_QUAT_LUT[body[cursor + 1].toInt() and 0xFF]
                val z = V12_QUAT_LUT[body[cursor + 2].toInt() and 0xFF]
                rotations[i * 4] = x
                rotations[i * 4 + 1] = y
                rotations[i * 4 + 2] = z
                rotations[i * 4 + 3] = sqrt((1f - (x * x + y * y + z * z)).coerceAtLeast(0f))
                cursor += 3
            }
        }

        val sh = if (shExtraBytes > 0 && !options.skipSphericalHarmonics) {
            FloatArray(shExtraBytes).also { out ->
                for (idx in 0 until shExtraBytes) {
                    out[idx] = SH_REST_LUT[body[cursor + idx].toInt() and 0xFF]
                }
            }
        } else null

        return GaussianPayload(
            splatCount = numPoints,
            centers = positions,
            scales = scales,
            rotations = rotations,
            opacities = alphas,
            rgba = rgba,
            sphericalHarmonics = sh,
            sphericalHarmonicsBands = shDegree,
            rtcCenter = defaultRtcCenter,
        )
    }

    companion object {
        const val HEADER_SIZE = 16

        // Sanity bound. SPZ scenes in the wild top out around tens of millions of splats;
        // anything beyond ~250M is almost certainly corrupted.
        private const val MAX_POINTS = 256_000_000

        // 1 / (2 * sqrt(pi)) — degree-0 spherical-harmonic basis constant.
        private const val SH_C0 = 0.28209479177387814f

        // SPZ DC-colour pack scale per the Niantic reference encoder. Combined with the
        // degree-0 SH render (`displayed = SH_C0 · dc + 0.5`) it amplifies the DC byte into a
        // recognisable colour range. Setting it to SH_C0 would collapse the formula to a
        // straight `raw/255` byte copy — convenient but loses every observable saturation
        // because the encoder packs the actual colour signal into a narrow [0.479, 0.521]
        // displayed range, expecting the render formula to amplify it back out.
        private const val SPZ_COLOR_SCALE = 0.15f

        // Combined unpack + degree-0 SH render: displayed_rgb = (raw - 127.5) * factor + 0.5.
        private const val COLOR_RENDER_FACTOR = SH_C0 / SPZ_COLOR_SCALE / 255f

        // SPZ v3 smallest-three quaternion field width: 9 bits magnitude per component.
        private const val SMALLEST3_MASK = (1 shl 9) - 1
        // 1 / sqrt(2) — peak magnitude of any non-largest quaternion component.
        private const val SQRT_HALF = 0.70710677f

        // ---- decode LUTs: built once, indexed by raw input byte / field ----

        /** V3 scale: `exp(raw / 16 - 10)`. */
        private val SCALE_LUT_V3: FloatArray = FloatArray(256) { exp(it / 16f - 10f) }

        /** V1/V2 scale: `exp((raw - 127.5) / 16)`. */
        private val SCALE_LUT_V12: FloatArray = FloatArray(256) { exp((it - 127.5f) / 16f) }

        /** Opacity unpack: `raw / 255` (pack stores `round(sigmoid(logit) * 255)`). */
        private val OPACITY_LUT: FloatArray = FloatArray(256) { it / 255f }

        /** DC colour + degree-0 SH render + clamp/scale back to [0,255] as a byte. */
        private val COLOR_LUT: ByteArray = ByteArray(256) { raw ->
            val v = (raw - 127.5f) * COLOR_RENDER_FACTOR + 0.5f
            (v.coerceIn(0f, 1f) * 255f).toInt().toByte()
        }

        /** Alpha → premultiplied rgba alpha byte: same clamp/scale path as [COLOR_LUT]. */
        private val OPACITY_TO_RGBA_LUT: ByteArray = ByteArray(256) { raw ->
            (OPACITY_LUT[raw].coerceIn(0f, 1f) * 255f).toInt().toByte()
        }

        /** V1/V2 quaternion component: `(raw - 127.5) / 127.5` in [-1, 1]. */
        private val V12_QUAT_LUT: FloatArray = FloatArray(256) { (it - 127.5f) / 127.5f }

        /** V3 smallest-three magnitude: 9-bit mag → component magnitude in [0, 1/√2]. */
        private val V3_QUAT_MAG_LUT: FloatArray = FloatArray(SMALLEST3_MASK + 1) {
            SQRT_HALF * it.toFloat() / SMALLEST3_MASK.toFloat()
        }

        /** SH rest coefficient: `(raw - 127.5) / 128`. */
        private val SH_REST_LUT: FloatArray = FloatArray(256) { (it - 127.5f) / 128f }

        /**
         * Platform inflater registered at app startup. See [SpzInflater]. Null = no SPZ
         * decompression support; [supports] still works, but [parse] throws on the first SPZ
         * tile fetched.
         */
        @Volatile
        var inflater: SpzInflater? = null

        private fun ByteArray.readUInt32LE(offset: Int): Int =
            (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
    }
}
