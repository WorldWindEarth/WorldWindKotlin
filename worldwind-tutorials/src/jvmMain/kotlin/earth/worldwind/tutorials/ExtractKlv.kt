@file:JvmName("ExtractKlv")

package earth.worldwind.tutorials

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Standalone KLV → JSON extractor for the video-on-terrain tutorial. Reads an MPEG-TS file,
 * finds the SMPTE 336M / KLV stream (auto-detects via PMT scan, falls back to PID 0x1F1 if
 * no PMT seen), reassembles PES packets, parses each as a MISB ST 0601 UAS Datalink Local
 * Set, extracts the four image-corner positions (full lat/lon if tags 82-89 are present,
 * otherwise frame-center + offset-corners 23-24 + 26-33), and writes a compact JSON
 * timeline keyed by playback-relative milliseconds. Pure Kotlin/JVM, no third-party deps.
 *
 * JSON tMs values are video-relative: t=0 corresponds to the video stream's first PTS,
 * so consumers can look up corners by `mediaPlayer.currentPosition` directly with no
 * per-clip skew constant. KLV samples that precede the first video frame (telemetry
 * pre-roll, common when the platform starts emitting metadata before the camera has
 * locked the first frame) get negative tMs values; [KlvTimeline.sampleAt] handles those
 * naturally — at player time 0 it interpolates between the last pre-roll and first
 * post-roll sample.
 *
 * Run via Gradle (replace classpath as needed) or as a script:
 * ```
 * java -cp worldwind-tutorials.jar earth.worldwind.tutorials.ExtractKlv input.ts output.json
 * ```
 *
 * Programmatic API: call [extractKlvToJson] with file paths or [extractKlvToJsonString] to
 * parse to a string in-memory.
 */

// ST 0601 (UAS Datalink Local Set) Universal Label.
private val ST0601_UL = byteArrayOf(
    0x06, 0x0E, 0x2B, 0x34, 0x02, 0x0B, 0x01, 0x01,
    0x0E, 0x01, 0x03, 0x01, 0x01, 0x00, 0x00, 0x00,
)

// Tag IDs (single-byte BER-OID) we care about.
private const val TAG_PLATFORM_HEADING = 5     // 2-byte uint16, value × (360/65535) °
private const val TAG_PLATFORM_PITCH = 6       // 2-byte int16,  value × (40/65534) °, ±20°
private const val TAG_PLATFORM_ROLL = 7        // 2-byte int16,  value × (100/65534) °, ±50°
private const val TAG_SENSOR_LAT = 13          // 4-byte int32, ±90°
private const val TAG_SENSOR_LON = 14          // 4-byte int32, ±180°
private const val TAG_SENSOR_ALT = 15          // 2-byte uint16, value × (19900/65535) - 900 m MSL
private const val TAG_SENSOR_HAE = 75          // 2-byte uint16, value × (19900/65535) - 900 m above WGS84 ellipsoid
private const val TAG_SENSOR_HFOV = 16         // 2-byte uint16, value × (180/65535) °
private const val TAG_SENSOR_VFOV = 17         // 2-byte uint16, value × (180/65535) °
private const val TAG_SENSOR_REL_AZ = 18       // 4-byte uint32, value × (360/(2^32-1)) °
private const val TAG_SENSOR_REL_EL = 19       // 4-byte int32,  value × (180/(2^31-1)) °
private const val TAG_SENSOR_REL_ROLL = 20     // 4-byte uint32, value × (360/(2^32-1)) °
private const val TAG_FRAME_CENTER_LAT = 23    // 4-byte int32, ±90°
private const val TAG_FRAME_CENTER_LON = 24    // 4-byte int32, ±180°
private const val TAG_OFFSET_LAT_PT1 = 26      // 2-byte int16, ±0.075°
private const val TAG_OFFSET_LON_PT1 = 27
private const val TAG_OFFSET_LAT_PT2 = 28
private const val TAG_OFFSET_LON_PT2 = 29
private const val TAG_OFFSET_LAT_PT3 = 30
private const val TAG_OFFSET_LON_PT3 = 31
private const val TAG_OFFSET_LAT_PT4 = 32
private const val TAG_OFFSET_LON_PT4 = 33
private const val TAG_FULL_LAT_PT1 = 82        // 4-byte int32, ±90°
private const val TAG_FULL_LON_PT1 = 83
private const val TAG_FULL_LAT_PT2 = 84
private const val TAG_FULL_LON_PT2 = 85
private const val TAG_FULL_LAT_PT3 = 86
private const val TAG_FULL_LON_PT3 = 87
private const val TAG_FULL_LAT_PT4 = 88
private const val TAG_FULL_LON_PT4 = 89
private const val TAG_PLATFORM_PITCH_FULL = 90  // 4-byte int32, ±90° (extends Tag 6 beyond ±20°)
private const val TAG_PLATFORM_ROLL_FULL = 91   // 4-byte int32, ±90° (extends Tag 7 beyond ±50°)

/** CLI entry point: `java earth.worldwind.tutorials.ExtractKlv <input.ts> <output.json>`. */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: ExtractKlv <input.ts> <output.json>")
        exitProcess(1)
    }
    extractKlvToJson(args[0], args[1])
}

/**
 * Read [inputTs], extract corner-position samples, and write a JSON timeline to
 * [outputJson]. Logs a one-line summary to stdout. Throws on I/O errors.
 */
fun extractKlvToJson(inputTs: String, outputJson: String) {
    val data = Files.readAllBytes(Paths.get(inputTs))
    val (json, parsed) = extractKlvToJsonString(data)
    Files.writeString(Paths.get(outputJson), json)
    println("Parsed $parsed corner samples → $outputJson")
}

/**
 * Programmatic in-memory variant: parse [tsBytes], return the JSON string and the count of
 * samples. Useful for SDK users who want to extract from streamed/preprocessed data without
 * touching the filesystem.
 */
fun extractKlvToJsonString(tsBytes: ByteArray): Pair<String, Int> {
    val klvPid = findKlvPid(tsBytes)
    println("KLV PID = 0x${klvPid.toString(16)}")
    val pes = reassemblePes(tsBytes, klvPid)
    println("PES packets: ${pes.size}")
    if (pes.isEmpty()) error("No KLV PES packets reassembled")

    // Tag inventory: lists every ST 0601 tag observed in the file. Helps diagnose missing
    // fields in the JSON (e.g. a drone emits Tag 90 instead of Tag 6 for platform pitch).
    val seenTags = sortedSetOf<Int>()
    for (p in pes) collectTags(p.payload, seenTags)
    println("Tags observed: $seenTags")

    // Anchor t=0 on the video stream's first PTS so player time directly indexes the
    // timeline with no per-clip skew constant. KLV that precedes the first video frame
    // (pre-roll telemetry — common when the platform starts emitting metadata before the
    // camera locks) ends up with negative tMs and is correctly handled by sampleAt.
    // Falls back to the file-earliest PTS when no video PID is identifiable in the PMT.
    val videoPid = findVideoPid(tsBytes)
    println("Video PID = ${if (videoPid >= 0) "0x${videoPid.toString(16)}" else "<not found>"}")
    val videoPts = if (videoPid >= 0) findEarliestPts(tsBytes, videoPid) else -1L
    val basePts90khz = when {
        videoPts >= 0 -> videoPts
        else -> findEarliestPts(tsBytes).takeIf { it >= 0 } ?: pes[0].pts
    }

    val json = StringBuilder().append("[\n")
    var first = true
    var parsed = 0
    for (p in pes) {
        val s = parseSt0601(p.payload) ?: continue
        val tMs = (p.pts - basePts90khz) * 1000L / 90000L
        if (!first) json.append(",\n")
        json.append(formatRecord(tMs, s))
        first = false
        parsed++
    }
    json.append("\n]\n")
    return json.toString() to parsed
}

private fun formatRecord(tMs: Long, s: Sample): String = String.format(
    Locale.US,
    "  {\"tMs\":%d," +
        "\"tl\":[%.7f,%.7f],\"tr\":[%.7f,%.7f],\"br\":[%.7f,%.7f],\"bl\":[%.7f,%.7f]," +
        "\"sLat\":%s,\"sLon\":%s,\"sAlt\":%s,\"sHae\":%s," +
        "\"pHdg\":%s,\"pPit\":%s,\"pRol\":%s," +
        "\"rAz\":%s,\"rEl\":%s,\"rRol\":%s," +
        "\"hFov\":%s,\"vFov\":%s}",
    tMs,
    s.tlLat, s.tlLon, s.trLat, s.trLon, s.brLat, s.brLon, s.blLat, s.blLon,
    fmt(s.sLat), fmt(s.sLon), fmtAlt(s.sAltMeters), fmtAlt(s.sHaeMeters),
    fmt(s.pHeading), fmt(s.pPitch), fmt(s.pRoll),
    fmt(s.rAz), fmt(s.rEl), fmt(s.rRoll),
    fmt(s.hFov), fmt(s.vFov),
)

// ----- TS demuxing -----

/**
 * Scan PMTs for a stream tagged with SMPTE 336M KLV. Falls back to 0x1F1 (the PID used by
 * the BlackHornet sample stream) if no PMT-discovered KLV PID is found.
 */
internal fun findKlvPid(data: ByteArray): Int {
    val patPid = 0x0000
    var pmtPid: Int? = null
    var klvPid: Int? = null
    var off = 0
    while (off + 188 <= data.size) {
        if ((data[off].toInt() and 0xFF) != 0x47) { off += 188; continue }
        val hdr1 = data[off + 1].toInt() and 0xFF
        val hdr2 = data[off + 2].toInt() and 0xFF
        val pid = ((hdr1 and 0x1F) shl 8) or hdr2
        val pusi = (hdr1 shr 6) and 0x01
        val afc = (data[off + 3].toInt() and 0x30) shr 4
        val payloadStart = 4 + (if (afc == 2 || afc == 3) 1 + (data[off + 4].toInt() and 0xFF) else 0)
        if (afc == 0 || afc == 2) { off += 188; continue } // no payload
        if (pid == patPid && pusi == 1 && pmtPid == null) {
            var p = payloadStart
            p += (data[off + p].toInt() and 0xFF) + 1 // pointer field
            val sectionLen = ((data[off + p + 1].toInt() and 0x0F) shl 8) or (data[off + p + 2].toInt() and 0xFF)
            val progStart = p + 8
            val progEnd = p + 3 + sectionLen - 4
            var q = progStart
            while (q + 4 <= progEnd) {
                val progNum = ((data[off + q].toInt() and 0xFF) shl 8) or (data[off + q + 1].toInt() and 0xFF)
                val pid2 = ((data[off + q + 2].toInt() and 0x1F) shl 8) or (data[off + q + 3].toInt() and 0xFF)
                if (progNum != 0) { pmtPid = pid2; break }
                q += 4
            }
        } else if (pmtPid != null && pid == pmtPid && pusi == 1 && klvPid == null) {
            var p = payloadStart
            p += (data[off + p].toInt() and 0xFF) + 1 // pointer field
            val sectionLen = ((data[off + p + 1].toInt() and 0x0F) shl 8) or (data[off + p + 2].toInt() and 0xFF)
            val progInfoLen = ((data[off + p + 10].toInt() and 0x0F) shl 8) or (data[off + p + 11].toInt() and 0xFF)
            val esStart = p + 12 + progInfoLen
            val esEnd = p + 3 + sectionLen - 4
            var q = esStart
            while (q + 5 <= esEnd) {
                val streamType = data[off + q].toInt() and 0xFF
                val esPid = ((data[off + q + 1].toInt() and 0x1F) shl 8) or (data[off + q + 2].toInt() and 0xFF)
                val esInfoLen = ((data[off + q + 3].toInt() and 0x0F) shl 8) or (data[off + q + 4].toInt() and 0xFF)
                // 0x15: SMPTE 336M synchronous KLV. 0x06 (PES private) with a registration
                // descriptor 'KLVA' is the asynchronous variant — accept either.
                if (streamType == 0x15) { klvPid = esPid; break }
                if (streamType == 0x06) {
                    var d = 0
                    while (d + 2 <= esInfoLen) {
                        val dTag = data[off + q + 5 + d].toInt() and 0xFF
                        val dLen = data[off + q + 6 + d].toInt() and 0xFF
                        if (dTag == 0x05 && dLen >= 4) { // registration descriptor
                            val fmt = ((data[off + q + 7 + d].toInt() and 0xFF) shl 24) or
                                ((data[off + q + 8 + d].toInt() and 0xFF) shl 16) or
                                ((data[off + q + 9 + d].toInt() and 0xFF) shl 8) or
                                (data[off + q + 10 + d].toInt() and 0xFF)
                            if (fmt == 0x4B4C5641) { klvPid = esPid } // 'KLVA'
                        }
                        d += 2 + dLen
                    }
                    if (klvPid != null) break
                }
                q += 5 + esInfoLen
            }
        }
        if (klvPid != null) return klvPid
        off += 188
    }
    return klvPid ?: 0x1F1
}

/**
 * Scan PMTs for the first video elementary stream and return its PID. Recognises the
 * common ISO/IEC 13818-1 video stream-type IDs:
 * - 0x01 / 0x02: MPEG-1 / MPEG-2 video
 * - 0x10: MPEG-4 visual
 * - 0x1B: H.264 / AVC
 * - 0x24: H.265 / HEVC
 * - 0x42: AVS
 * Returns -1 if no video PID can be identified.
 */
internal fun findVideoPid(data: ByteArray): Int {
    val patPid = 0x0000
    var pmtPid: Int? = null
    var off = 0
    while (off + 188 <= data.size) {
        if ((data[off].toInt() and 0xFF) != 0x47) { off += 188; continue }
        val hdr1 = data[off + 1].toInt() and 0xFF
        val hdr2 = data[off + 2].toInt() and 0xFF
        val pid = ((hdr1 and 0x1F) shl 8) or hdr2
        val pusi = (hdr1 shr 6) and 0x01
        val afc = (data[off + 3].toInt() and 0x30) shr 4
        if (afc == 0 || afc == 2) { off += 188; continue }
        val payloadStart = 4 + (if (afc == 3) 1 + (data[off + 4].toInt() and 0xFF) else 0)
        if (pid == patPid && pusi == 1 && pmtPid == null) {
            var p = payloadStart
            p += (data[off + p].toInt() and 0xFF) + 1
            val sectionLen = ((data[off + p + 1].toInt() and 0x0F) shl 8) or (data[off + p + 2].toInt() and 0xFF)
            val progStart = p + 8
            val progEnd = p + 3 + sectionLen - 4
            var q = progStart
            while (q + 4 <= progEnd) {
                val progNum = ((data[off + q].toInt() and 0xFF) shl 8) or (data[off + q + 1].toInt() and 0xFF)
                val pid2 = ((data[off + q + 2].toInt() and 0x1F) shl 8) or (data[off + q + 3].toInt() and 0xFF)
                if (progNum != 0) { pmtPid = pid2; break }
                q += 4
            }
        } else if (pmtPid != null && pid == pmtPid && pusi == 1) {
            var p = payloadStart
            p += (data[off + p].toInt() and 0xFF) + 1
            val sectionLen = ((data[off + p + 1].toInt() and 0x0F) shl 8) or (data[off + p + 2].toInt() and 0xFF)
            val progInfoLen = ((data[off + p + 10].toInt() and 0x0F) shl 8) or (data[off + p + 11].toInt() and 0xFF)
            val esStart = p + 12 + progInfoLen
            val esEnd = p + 3 + sectionLen - 4
            var q = esStart
            while (q + 5 <= esEnd) {
                val streamType = data[off + q].toInt() and 0xFF
                val esPid = ((data[off + q + 1].toInt() and 0x1F) shl 8) or (data[off + q + 2].toInt() and 0xFF)
                val esInfoLen = ((data[off + q + 3].toInt() and 0x0F) shl 8) or (data[off + q + 4].toInt() and 0xFF)
                if (streamType == 0x01 || streamType == 0x02 || streamType == 0x10 ||
                    streamType == 0x1B || streamType == 0x24 || streamType == 0x42) return esPid
                q += 5 + esInfoLen
            }
        }
        off += 188
    }
    return -1
}

internal class PesPacket(val pts: Long, val payload: ByteArray)

/**
 * Walk an ST 0601 KLV payload, invoking [onTag] for each (tag, value) pair found inside
 * the UL-delimited Local Set. Tolerates a leading SMPTE 336M Asynchronous Metadata AU
 * header (or similar wrappers) by scanning the first 64 bytes for the ST 0601 UL. Bails
 * on length-overruns rather than throwing.
 */
private inline fun walkTags(msg: ByteArray, onTag: (tag: Int, value: ByteArray) -> Unit) {
    if (msg.size < 17) return
    var ulStart = -1
    val searchEnd = minOf(64, msg.size - 16)
    for (i in 0..searchEnd) {
        var ok = true
        for (j in 0 until 16) if (msg[i + j] != ST0601_UL[j]) { ok = false; break }
        if (ok) { ulStart = i; break }
    }
    if (ulStart < 0) return
    var p = ulStart + 16
    // Outer Local Set BER length.
    var b = msg[p++].toInt() and 0xFF
    var outerLen = b and 0x7F
    if (b and 0x80 != 0) {
        val n = outerLen
        if (p + n > msg.size) return
        outerLen = 0
        repeat(n) { outerLen = (outerLen shl 8) or (msg[p++].toInt() and 0xFF) }
    }
    val end = minOf(p + outerLen, msg.size)
    while (p < end) {
        val tag = msg[p++].toInt() and 0xFF
        // Per-tag BER length.
        b = msg[p++].toInt() and 0xFF
        var tlen = b and 0x7F
        if (b and 0x80 != 0) {
            val n = tlen
            if (p + n > end) break
            tlen = 0
            repeat(n) { tlen = (tlen shl 8) or (msg[p++].toInt() and 0xFF) }
        }
        if (p + tlen > end) break
        val v = ByteArray(tlen)
        System.arraycopy(msg, p, v, 0, tlen)
        onTag(tag, v)
        p += tlen
    }
}

/** Add every ST 0601 tag ID observed in [msg] to [out]. */
internal fun collectTags(msg: ByteArray, out: MutableSet<Int>) =
    walkTags(msg) { tag, _ -> out.add(tag) }

/**
 * Walk all TS packets with the given PID, reassemble each PES (one PES per PUSI=1 boundary),
 * extract PTS, and return the PES payload bytes.
 */
internal fun reassemblePes(data: ByteArray, targetPid: Int): List<PesPacket> {
    val out = ArrayList<PesPacket>()
    val buf = ByteArrayOutputStream()
    var currentPts = -1L
    var off = 0
    while (off + 188 <= data.size) {
        if ((data[off].toInt() and 0xFF) != 0x47) { off += 188; continue }
        val hdr1 = data[off + 1].toInt() and 0xFF
        val hdr2 = data[off + 2].toInt() and 0xFF
        val pid = ((hdr1 and 0x1F) shl 8) or hdr2
        if (pid != targetPid) { off += 188; continue }
        val pusi = (hdr1 shr 6) and 0x01
        val afc = (data[off + 3].toInt() and 0x30) shr 4
        val payloadStart = 4 + (if (afc == 2 || afc == 3) 1 + (data[off + 4].toInt() and 0xFF) else 0)
        if (afc == 0 || afc == 2) { off += 188; continue }
        val payloadEnd = off + 188

        if (pusi == 1) {
            if (buf.size() > 0 && currentPts >= 0) out.add(PesPacket(currentPts, buf.toByteArray()))
            buf.reset()
            currentPts = -1L
            val p = off + payloadStart
            if ((data[p].toInt() and 0xFF) != 0 || (data[p + 1].toInt() and 0xFF) != 0 ||
                (data[p + 2].toInt() and 0xFF) != 1) { off += 188; continue }
            val ptsDtsFlags = (data[p + 7].toInt() and 0xC0) shr 6
            val pesHdrDataLen = data[p + 8].toInt() and 0xFF
            if (ptsDtsFlags == 2 || ptsDtsFlags == 3) {
                val q = p + 9
                val pts0 = ((data[q].toLong() and 0x0E) shr 1)
                val pts1 = (data[q + 1].toLong() and 0xFF)
                val pts2 = ((data[q + 2].toLong() and 0xFE) shr 1)
                val pts3 = (data[q + 3].toLong() and 0xFF)
                val pts4 = ((data[q + 4].toLong() and 0xFE) shr 1)
                currentPts = (pts0 shl 30) or (pts1 shl 22) or (pts2 shl 15) or (pts3 shl 7) or pts4
            }
            val payloadOffset = p + 9 + pesHdrDataLen
            val payloadLen = payloadEnd - payloadOffset
            if (payloadLen > 0) buf.write(data, payloadOffset, payloadLen)
        } else {
            val len = payloadEnd - (off + payloadStart)
            if (len > 0) buf.write(data, off + payloadStart, len)
        }
        off += 188
    }
    if (buf.size() > 0 && currentPts >= 0) out.add(PesPacket(currentPts, buf.toByteArray()))
    return out
}

/**
 * Scan every TS packet in the file, parse the PTS of every PUSI=1 PES header that carries
 * one, and return the minimum 90 kHz PTS observed - which is normally the video stream's
 * first frame. Returns -1 if no PTS-bearing PES headers are seen.
 *
 * Pass [targetPid] to restrict the scan to a single elementary stream (e.g. video only),
 * or -1 to scan across every PID.
 */
internal fun findEarliestPts(data: ByteArray, targetPid: Int = -1): Long {
    var earliest = -1L
    var off = 0
    while (off + 188 <= data.size) {
        if ((data[off].toInt() and 0xFF) != 0x47) { off += 188; continue }
        val hdr1 = data[off + 1].toInt() and 0xFF
        val hdr2 = data[off + 2].toInt() and 0xFF
        val pid = ((hdr1 and 0x1F) shl 8) or hdr2
        if (targetPid >= 0 && pid != targetPid) { off += 188; continue }
        val pusi = (hdr1 shr 6) and 0x01
        val afc = (data[off + 3].toInt() and 0x30) shr 4
        if (afc == 0 || afc == 2 || pusi == 0) { off += 188; continue }
        val payloadStart = 4 + (if (afc == 3) 1 + (data[off + 4].toInt() and 0xFF) else 0)
        val p = off + payloadStart
        if (p + 14 > off + 188) { off += 188; continue }
        if ((data[p].toInt() and 0xFF) != 0 || (data[p + 1].toInt() and 0xFF) != 0 ||
            (data[p + 2].toInt() and 0xFF) != 1) { off += 188; continue }
        val ptsDtsFlags = (data[p + 7].toInt() and 0xC0) shr 6
        if (ptsDtsFlags != 2 && ptsDtsFlags != 3) { off += 188; continue }
        val q = p + 9
        val pts0 = ((data[q].toLong() and 0x0E) shr 1)
        val pts1 = (data[q + 1].toLong() and 0xFF)
        val pts2 = ((data[q + 2].toLong() and 0xFE) shr 1)
        val pts3 = (data[q + 3].toLong() and 0xFF)
        val pts4 = ((data[q + 4].toLong() and 0xFE) shr 1)
        val pts = (pts0 shl 30) or (pts1 shl 22) or (pts2 shl 15) or (pts3 shl 7) or pts4
        if (earliest < 0 || pts < earliest) earliest = pts
        off += 188
    }
    return earliest
}

// ----- ST 0601 parsing -----

/** One ST 0601 packet's worth of corner positions plus camera pose / FOV. */
internal class Sample {
    var tlLat = 0.0; var tlLon = 0.0
    var trLat = 0.0; var trLon = 0.0
    var brLat = 0.0; var brLon = 0.0
    var blLat = 0.0; var blLon = 0.0
    // sAltMeters = MSL (Tag 15); sHaeMeters = HAE / above WGS84 ellipsoid (Tag 75).
    // BlackHornet and similar UAS often emit only the HAE field.
    var sLat = Double.NaN; var sLon = Double.NaN
    var sAltMeters = Double.NaN; var sHaeMeters = Double.NaN
    var pHeading = Double.NaN; var pPitch = Double.NaN; var pRoll = Double.NaN
    var rAz = Double.NaN; var rEl = Double.NaN; var rRoll = Double.NaN
    var hFov = Double.NaN; var vFov = Double.NaN
}

/**
 * Parse a single ST 0601 KLV message into a [Sample] of corner positions + sensor pose +
 * FOV. Returns `null` if the UL signature isn't found or no corner positions are present.
 */
internal fun parseSt0601(msg: ByteArray): Sample? {
    val tags = HashMap<Int, ByteArray>()
    walkTags(msg) { tag, v -> tags[tag] = v }
    if (tags.isEmpty()) return null

    val s = Sample()

    // Prefer full-precision corners (tags 82-89) when present AND all eight values are
    // valid (not the int32-min "error" sentinel). When even one corner is NaN we fall
    // through to the frame-center + offset path - both are valid in ST 0601 and live
    // packets often carry the offset variant when the platform can't resolve full corner
    // positions for that frame (e.g. terrain database lookup gap).
    var hasCorners = false
    if (tags.containsKey(TAG_FULL_LAT_PT1) && tags.containsKey(TAG_FULL_LON_PT1)) {
        s.tlLat = decodeLat(tags[TAG_FULL_LAT_PT1]); s.tlLon = decodeLon(tags[TAG_FULL_LON_PT1])
        s.trLat = decodeLat(tags[TAG_FULL_LAT_PT2]); s.trLon = decodeLon(tags[TAG_FULL_LON_PT2])
        s.brLat = decodeLat(tags[TAG_FULL_LAT_PT3]); s.brLon = decodeLon(tags[TAG_FULL_LON_PT3])
        s.blLat = decodeLat(tags[TAG_FULL_LAT_PT4]); s.blLon = decodeLon(tags[TAG_FULL_LON_PT4])
        hasCorners = !s.tlLat.isNaN() && !s.tlLon.isNaN() &&
            !s.trLat.isNaN() && !s.trLon.isNaN() &&
            !s.brLat.isNaN() && !s.brLon.isNaN() &&
            !s.blLat.isNaN() && !s.blLon.isNaN()
    }
    if (!hasCorners) {
        val fcLat = tags[TAG_FRAME_CENTER_LAT] ?: return null
        val fcLon = tags[TAG_FRAME_CENTER_LON] ?: return null
        val cLat = decodeLat(fcLat); val cLon = decodeLon(fcLon)
        s.tlLat = cLat + decodeOffsetCorner(tags[TAG_OFFSET_LAT_PT1])
        s.tlLon = cLon + decodeOffsetCorner(tags[TAG_OFFSET_LON_PT1])
        s.trLat = cLat + decodeOffsetCorner(tags[TAG_OFFSET_LAT_PT2])
        s.trLon = cLon + decodeOffsetCorner(tags[TAG_OFFSET_LON_PT2])
        s.brLat = cLat + decodeOffsetCorner(tags[TAG_OFFSET_LAT_PT3])
        s.brLon = cLon + decodeOffsetCorner(tags[TAG_OFFSET_LON_PT3])
        s.blLat = cLat + decodeOffsetCorner(tags[TAG_OFFSET_LAT_PT4])
        s.blLon = cLon + decodeOffsetCorner(tags[TAG_OFFSET_LON_PT4])
    }

    s.sLat = decodeLat(tags[TAG_SENSOR_LAT])
    s.sLon = decodeLon(tags[TAG_SENSOR_LON])
    s.sAltMeters = decodeSensorAlt(tags[TAG_SENSOR_ALT])
    s.sHaeMeters = decodeSensorAlt(tags[TAG_SENSOR_HAE])
    s.pHeading = decodeUnsignedAngle16(tags[TAG_PLATFORM_HEADING], 360.0)
    // Prefer Full-range int32 (Tag 90/91, ±90°) over narrow int16 (Tag 6/7, ±20°/±50°).
    // Drones with wider attitude envelopes (quadcopters, gimbal'd platforms) emit only
    // Full; older fixed-wing streams emit only narrow. Fall back when the preferred
    // tag is absent.
    s.pPitch = decodeSignedAngle32(tags[TAG_PLATFORM_PITCH_FULL], 90.0)
    if (s.pPitch.isNaN()) s.pPitch = decodeSignedAngle16(tags[TAG_PLATFORM_PITCH], 20.0)
    s.pRoll = decodeSignedAngle32(tags[TAG_PLATFORM_ROLL_FULL], 90.0)
    if (s.pRoll.isNaN()) s.pRoll = decodeSignedAngle16(tags[TAG_PLATFORM_ROLL], 50.0)
    s.hFov = decodeUnsignedAngle16(tags[TAG_SENSOR_HFOV], 180.0)
    s.vFov = decodeUnsignedAngle16(tags[TAG_SENSOR_VFOV], 180.0)
    s.rAz = decodeUnsignedAngle32(tags[TAG_SENSOR_REL_AZ], 360.0)
    s.rEl = decodeSignedAngle32(tags[TAG_SENSOR_REL_EL], 180.0)
    s.rRoll = decodeUnsignedAngle32(tags[TAG_SENSOR_REL_ROLL], 360.0)
    return s
}

/** 4-byte signed int → degrees in ±90 range. -2^31 reserved as error → NaN. */
internal fun decodeLat(v: ByteArray?): Double {
    if (v == null || v.size != 4) return Double.NaN
    val x = ((v[0].toInt() and 0xFF) shl 24) or ((v[1].toInt() and 0xFF) shl 16) or
        ((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)
    if (x == Int.MIN_VALUE) return Double.NaN
    return x.toDouble() * 90.0 / Int.MAX_VALUE
}

/** 4-byte signed int → degrees in ±180 range. */
internal fun decodeLon(v: ByteArray?): Double {
    if (v == null || v.size != 4) return Double.NaN
    val x = ((v[0].toInt() and 0xFF) shl 24) or ((v[1].toInt() and 0xFF) shl 16) or
        ((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)
    if (x == Int.MIN_VALUE) return Double.NaN
    return x.toDouble() * 180.0 / Int.MAX_VALUE
}

private fun fmt(d: Double): String = if (d.isNaN()) "null" else String.format(Locale.US, "%.6f", d)
private fun fmtAlt(d: Double): String = if (d.isNaN()) "null" else String.format(Locale.US, "%.2f", d)

/** 2-byte uint16 → degrees in `[0, range]`. */
internal fun decodeUnsignedAngle16(v: ByteArray?, range: Double): Double {
    if (v == null || v.size != 2) return Double.NaN
    val x = ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
    return x * range / 65535.0
}

/** 4-byte uint32 → degrees in `[0, range]`. */
internal fun decodeUnsignedAngle32(v: ByteArray?, range: Double): Double {
    if (v == null || v.size != 4) return Double.NaN
    val x = ((v[0].toLong() and 0xFF) shl 24) or ((v[1].toLong() and 0xFF) shl 16) or
        ((v[2].toLong() and 0xFF) shl 8) or (v[3].toLong() and 0xFF)
    return x * range / 4294967295.0 // 2^32 - 1
}

/** 2-byte int16 → degrees in `±range`. -2^15 reserved as error → NaN. */
internal fun decodeSignedAngle16(v: ByteArray?, range: Double): Double {
    if (v == null || v.size != 2) return Double.NaN
    val x = (((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)).toShort().toInt()
    if (x == Short.MIN_VALUE.toInt()) return Double.NaN
    return x * range / 32767.0
}

/** 4-byte int32 → degrees in `±range`. -2^31 reserved as error → NaN. */
internal fun decodeSignedAngle32(v: ByteArray?, range: Double): Double {
    if (v == null || v.size != 4) return Double.NaN
    val x = ((v[0].toInt() and 0xFF) shl 24) or ((v[1].toInt() and 0xFF) shl 16) or
        ((v[2].toInt() and 0xFF) shl 8) or (v[3].toInt() and 0xFF)
    if (x == Int.MIN_VALUE) return Double.NaN
    return x.toDouble() * range / Int.MAX_VALUE
}

/** 2-byte uint16 → meters MSL: `value × (19900/65535) - 900`. */
internal fun decodeSensorAlt(v: ByteArray?): Double {
    if (v == null || v.size != 2) return Double.NaN
    val x = ((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)
    return x * 19900.0 / 65535.0 - 900.0
}

/** 2-byte signed int → ±0.075° degree offset. -2^15 reserved as error → 0. */
internal fun decodeOffsetCorner(v: ByteArray?): Double {
    if (v == null || v.size != 2) return 0.0
    val x = (((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)).toShort().toInt()
    if (x == Short.MIN_VALUE.toInt()) return 0.0
    return x.toDouble() * 0.075 / Short.MAX_VALUE
}
