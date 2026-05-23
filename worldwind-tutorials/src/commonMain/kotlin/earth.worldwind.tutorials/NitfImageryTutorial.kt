package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.nitf.Nitf
import earth.worldwind.formats.nitf.NitfImage
import earth.worldwind.formats.nitf.NitfImageReader
import earth.worldwind.formats.nitf.toImageSource
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.shape.SurfaceImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Synthesises a small RGB NITF 2.1 image in memory at startup, parses it with
 * [Nitf]/[NitfImageReader], and drapes the decoded pixels over the globe as a
 * [SurfaceImage] via the platform-specific [toImageSource] bridge.
 *
 * The synthetic content is a 256 × 256 RGB image laid out as an 8 × 8 grid of
 * 32-pixel blocks, with a horizontal hue ramp, a vertical brightness ramp, and
 * faint concentric rings — visual cues that make block seams obvious if the
 * decoder ever mis-orders blocks. Georeferenced to a patch over Mt Etna so the
 * imagery lands somewhere recognisable.
 *
 * Cross-platform: the synthesis, parse, and decode all live in `commonMain`.
 * The only per-platform piece is `NitfImage.toImageSource()`, which builds the
 * platform-native bitmap (`BufferedImage` / `Bitmap` / `ImageBitmap` / `UIImage`)
 * — same plumbing every SurfaceImage tutorial uses.
 */
class NitfImageryTutorial(engine: WorldWind) : AbstractTutorial(engine) {

    private val layer = RenderableLayer("NITF imagery")

    override fun start() {
        super.start()
        val bytes = synthesiseSampleBytes()
        val nitf = Nitf.parse(bytes)
        val seg = nitf.imageSegments.firstOrNull()
            ?: error("Synthetic NITF had no image segments")
        val argb = NitfImageReader.decodeArgb(seg, bytes)
        val sector = seg.sector
            ?: error("Synthetic NITF image segment had no IGEOLO sector")
        val image = NitfImage(seg.numCols.toInt(), seg.numRows.toInt(), argb, sector)
        layer.addRenderable(SurfaceImage(sector, image.toImageSource()))
        engine.layers.addLayer(layer)
        positionCamera(sector)
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
        layer.clearRenderables()
    }

    private fun positionCamera(sector: Sector) {
        val lat = sector.centroidLatitude.inDegrees
        val lon = sector.centroidLongitude.inDegrees
        val span = max(sector.deltaLatitude.inDegrees, sector.deltaLongitude.inDegrees)
        // 1° ≈ 111 km — pick a viewing altitude proportional to the image's
        // ground footprint so a 0.1° image isn't viewed from 500 km out.
        val altitudeMeters = (span * 111_000.0 * 4.0).coerceAtLeast(50_000.0)
        engine.camera.set(
            lat.degrees, lon.degrees, altitudeMeters,
            AltitudeMode.ABSOLUTE,
            heading = Angle.ZERO, tilt = Angle.ZERO, roll = Angle.ZERO,
        )
        WorldWind.requestRedraw()
    }

    // --- Synthetic NITF generator ------------------------------------------

    private fun synthesiseSampleBytes(): ByteArray {
        val w = 256
        val h = 256
        val nbpr = 8
        val nbpc = 8
        val nppbh = 32
        val nppbv = 32

        // Visual content: horizontal hue ramp + vertical brightness ramp +
        // faint concentric rings around the centre to verify the block grid
        // re-assembles without seams.
        val cx = w / 2.0
        val cy = h / 2.0
        val rMax = hypot(cx, cy)

        val pixels = IntArray(w * h * 3)
        for (r in 0 until h) {
            for (c in 0 until w) {
                val hue = c.toDouble() / w
                val bright = 0.35 + 0.6 * (1.0 - r.toDouble() / h)
                val ringPhase = hypot(c - cx, r - cy) / rMax * 6.0
                val ringMod = 0.85 + 0.15 * cos(ringPhase * PI * 2)
                val rgb = hsvToRgb(hue, 0.85, (bright * ringMod).coerceIn(0.0, 1.0))
                val p = (r * w + c) * 3
                pixels[p] = (rgb ushr 16) and 0xFF
                pixels[p + 1] = (rgb ushr 8) and 0xFF
                pixels[p + 2] = rgb and 0xFF
            }
        }

        return buildRgbNitf(
            width = w, height = h,
            blocksPerRow = nbpr, blocksPerCol = nbpc,
            pixelsPerBlockH = nppbh, pixelsPerBlockV = nppbv,
            sectorMinLat = 37.20, sectorMaxLat = 37.72,
            sectorMinLon = 14.78, sectorMaxLon = 15.30,
            pixel = { band, row, col -> pixels[(row * w + col) * 3 + band] },
        )
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): Int {
        val hh = (h % 1.0) * 6.0
        val i = hh.toInt()
        val f = hh - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        val (rr, gg, bb) = when (i) {
            0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t)
            3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q)
        }
        val ri = (min(1.0, max(0.0, rr)) * 255.0).roundToInt()
        val gi = (min(1.0, max(0.0, gg)) * 255.0).roundToInt()
        val bi = (min(1.0, max(0.0, bb)) * 255.0).roundToInt()
        return (ri shl 16) or (gi shl 8) or bi
    }

    /**
     * Minimal NITF 2.1 writer for the synthetic sample. Mirrors the unit-test
     * fixture's structure: file header → image subheader → image data.
     * RGB / IC=NC / IMODE B, ICORDS=D for decimal-degree IGEOLO. Self-contained
     * — no `String.format` so it works under any default locale.
     */
    private fun buildRgbNitf(
        width: Int, height: Int,
        blocksPerRow: Int, blocksPerCol: Int,
        pixelsPerBlockH: Int, pixelsPerBlockV: Int,
        sectorMinLat: Double, sectorMaxLat: Double,
        sectorMinLon: Double, sectorMaxLon: Double,
        pixel: (band: Int, row: Int, col: Int) -> Int,
    ): ByteArray {
        val nbands = 3
        val bytesPerSample = 1
        val pixelsPerBlock = pixelsPerBlockH * pixelsPerBlockV
        val imageDataBytes = blocksPerRow * blocksPerCol * nbands * pixelsPerBlock * bytesPerSample
        val lish = imageSubheaderLength(nbands, hasIgeolo = true)
        val hl = 388 + 16
        val fl = hl + lish + imageDataBytes

        val out = ByteArray(fl)
        var p = 0

        fun writeAscii(s: String, len: Int) {
            val padded = s.padEnd(len, ' ').substring(0, len)
            for (i in 0 until len) out[p + i] = padded[i].code.toByte()
            p += len
        }
        fun writeAsciiRJ(s: String, len: Int, padChar: Char = '0') {
            val final = if (s.length >= len) s.substring(s.length - len) else s.padStart(len, padChar)
            for (i in 0 until len) out[p + i] = final[i].code.toByte()
            p += len
        }

        writeAscii("NITF", 4)
        writeAscii("02.10", 5)
        writeAsciiRJ("3", 2)
        writeAscii("BF01", 4)
        writeAscii("TUTORIAL", 10)
        writeAscii("20260523000000", 14)
        writeAscii("WorldWind NITF tutorial sample", 80)
        p += 167
        writeAsciiRJ("0", 5)
        writeAsciiRJ("0", 5)
        writeAscii("0", 1)
        p += 3
        writeAscii("WW", 24)
        writeAscii("", 18)
        writeAsciiRJ(fl.toString(), 12)
        writeAsciiRJ(hl.toString(), 6)
        writeAsciiRJ("1", 3)
        writeAsciiRJ(lish.toString(), 6)
        writeAsciiRJ(imageDataBytes.toString(), 10)
        writeAsciiRJ("0", 3)
        writeAsciiRJ("0", 3)
        writeAsciiRJ("0", 3)
        writeAsciiRJ("0", 3)
        writeAsciiRJ("0", 3)
        writeAsciiRJ("0", 5)
        writeAsciiRJ("0", 5)

        writeAscii("IM", 2)
        writeAscii("SAMPLE001", 10)
        writeAscii("20260523000000", 14)
        writeAscii("WORLDWIND TUT TGT", 17)
        writeAscii("WorldWind NITF sample - RGB ramp + concentric rings", 80)
        p += 167
        writeAscii("0", 1)
        writeAscii("worldwind-kotlin", 42)
        writeAsciiRJ(height.toString(), 8)
        writeAsciiRJ(width.toString(), 8)
        writeAscii("INT", 3)
        writeAscii("RGB", 8)
        writeAscii("VIS", 8)
        writeAsciiRJ("8", 2)
        writeAscii("R", 1)
        writeAscii("D", 1)
        writeAscii(
            decimalIgeolo(sectorMinLat, sectorMaxLat, sectorMinLon, sectorMaxLon),
            60
        )
        writeAsciiRJ("0", 1)
        writeAscii("NC", 2)
        writeAsciiRJ("3", 1)
        for (b in 0 until 3) {
            writeAscii(if (b == 0) "R " else if (b == 1) "G " else "B ", 2)
            writeAscii("VIS", 6)
            writeAscii("N", 1)
            writeAscii("", 3)
            writeAsciiRJ("0", 1)
        }
        writeAsciiRJ("0", 1)
        writeAscii("B", 1)
        writeAsciiRJ(blocksPerRow.toString(), 4)
        writeAsciiRJ(blocksPerCol.toString(), 4)
        writeAsciiRJ(pixelsPerBlockH.toString(), 4)
        writeAsciiRJ(pixelsPerBlockV.toString(), 4)
        writeAsciiRJ("8", 2)
        writeAsciiRJ("1", 3)
        writeAsciiRJ("0", 3)
        writeAscii("0000000000", 10)
        writeAscii("1.0 ", 4)
        writeAsciiRJ("0", 5)
        writeAsciiRJ("0", 5)

        for (by in 0 until blocksPerCol) {
            for (bx in 0 until blocksPerRow) {
                for (band in 0 until nbands) {
                    for (py in 0 until pixelsPerBlockV) {
                        for (px in 0 until pixelsPerBlockH) {
                            val row = by * pixelsPerBlockV + py
                            val col = bx * pixelsPerBlockH + px
                            out[p] = (pixel(band, row, col) and 0xFF).toByte()
                            p += 1
                        }
                    }
                }
            }
        }
        check(p == out.size) { "synthesised NITF write expected ${out.size} bytes, wrote $p" }
        return out
    }

    private fun imageSubheaderLength(nbands: Int, hasIgeolo: Boolean): Int {
        var len = 2 + 10 + 14 + 17 + 80 + 167 + 1 + 42 + 8 + 8 + 3 + 8 + 8 + 2 + 1 + 1
        if (hasIgeolo) len += 60
        len += 1 + 2 + 1
        len += nbands * (2 + 6 + 1 + 3 + 1)
        len += 1 + 1 + 4 + 4 + 4 + 4 + 2 + 3 + 3 + 10 + 4 + 5 + 5
        return len
    }

    private fun decimalIgeolo(latS: Double, latN: Double, lonW: Double, lonE: Double): String =
        formatCorner(latN, lonW) + formatCorner(latN, lonE) +
            formatCorner(latS, lonE) + formatCorner(latS, lonW)

    private fun formatCorner(lat: Double, lon: Double): String =
        signedFixed(lat, intDigits = 2) + signedFixed(lon, intDigits = 3)

    /** Format a signed decimal degree value as `±DD.DDD` (or `±DDD.DDD` for longitude).
     *  Avoids `String.format`, whose default locale isn't guaranteed to use `.` as
     *  decimal separator on all hosts. */
    private fun signedFixed(value: Double, intDigits: Int): String {
        val sign = if (value < 0) "-" else "+"
        val absVal = abs(value)
        val whole = absVal.toInt()
        val frac = ((absVal - whole) * 1000.0).roundToInt().coerceIn(0, 999)
        return sign + whole.toString().padStart(intDigits, '0') + "." + frac.toString().padStart(3, '0')
    }
}
