package earth.worldwind.formats.nitf

import earth.worldwind.formats.BinaryDataView

/**
 * Decode the pixel data section of an uncompressed (IC=NC or IC=NM) NITF image
 * segment into a row-major ARGB int[] suitable for upload as a `Bitmap` /
 * `BufferedImage` / `<canvas>` ImageData.
 *
 * Spec coverage (MIL-STD-2500C §5.4.2.5–§5.4.2.10):
 *  - IMODE B (band-interleaved-by-block) — supported for all band counts.
 *  - IMODE P (band-interleaved-by-pixel) — supported.
 *  - IMODE R (band-interleaved-by-row) — supported.
 *  - IMODE S (band-sequential, blocks stored band-by-band) — supported.
 *
 *  - NBPP ∈ {8, 16} integer pixels (PVTYPE=INT or SI). 1-bit (B) and IEEE
 *    floating-point (R) are out of scope here; callers wanting bilevel can
 *    pre-quantise to 8-bit themselves.
 *  - NBANDS ∈ {1, 3, 4} with IREP ∈ {MONO, RGB, RGB/LUT, MULTI}. MONO with a
 *    LUT becomes a paletted lookup; bare MONO maps grey→ARGB. RGB(A) sources
 *    are written straight through.
 *
 * Block masks for IC=NM follow §5.4.1.2 (BMR with 0xFFFFFFFF = missing block,
 * filled with the transparent pixel code if present, otherwise 0). Pad-pixel
 * masks (TMR) are parsed but only the transparent code is used — applying
 * per-pixel TMR data would require a second pass and is left for Phase 2.
 *
 * 16-bit signed values are output as `(value + 32768)` mapped to 0..255 in the
 * grey ramp; the raw 16-bit grid is also exposed via [decodeRaw] for callers
 * that want elevation/SAR/temperature data unmangled.
 */
object NitfImageReader {

    /** Result of a raw (non-ARGB) decode. */
    class RawImage internal constructor(
        val width: Int,
        val height: Int,
        val numBands: Int,
        val bitsPerSample: Int,
        /** Interleaved per-pixel band samples, row-major. */
        val samples: IntArray,
    )

    /**
     * Decode the pixel grid into a row-major ARGB int[] of length `numRows *
     * numCols`. Throws [IllegalStateException] for spec violations or features
     * outside Phase 1's scope.
     */
    fun decodeArgb(segment: NitfImageSegment, fileBytes: ByteArray): IntArray {
        require(segment.compression == NitfCompression.NC ||
            segment.compression == NitfCompression.NM) {
            "decodeArgb only handles uncompressed (IC=NC|NM); IC=${segment.compression.code}"
        }
        require(segment.bitsPerPixel == 8 || segment.bitsPerPixel == 16) {
            "Unsupported NBPP=${segment.bitsPerPixel}; supported {8,16}"
        }
        val raw = decodeRaw(segment, fileBytes)
        return rawToArgb(segment, raw)
    }

    /**
     * Decode the pixel grid into a [RawImage] preserving the source bit depth.
     * Out-of-significant-area pixels are filled with 0 (or with the segment's
     * transparent pixel code if one is declared in the NM mask).
     */
    fun decodeRaw(segment: NitfImageSegment, fileBytes: ByteArray): RawImage {
        require(segment.compression == NitfCompression.NC ||
            segment.compression == NitfCompression.NM) {
            "decodeRaw only handles uncompressed (IC=NC|NM)"
        }
        require(segment.bitsPerPixel == 8 || segment.bitsPerPixel == 16) {
            "Unsupported NBPP=${segment.bitsPerPixel}"
        }
        val w = segment.numCols.toInt()
        val h = segment.numRows.toInt()
        val nb = segment.numBands
        val bps = segment.bitsPerPixel
        val bytesPerSample = bps / 8

        val view = BinaryDataView(fileBytes)
        val reader = NitfReader(view, segment.dataOffset.toInt())

        // Phase 1: NM mask is parsed (so we can advance the cursor past it
        // correctly) but masked blocks are simply zero-filled. Transparent
        // pixel code from TPXCD is applied at the per-block level.
        var blockOffsets: IntArray? = null
        var transparentCode: Long = 0
        var hasTransparentCode = false
        var dataStartAfterMask = reader.position
        if (segment.compression == NitfCompression.NM) {
            val maskStart = reader.position
            val imdatoff = reader.readUInt().toInt()
            val bmrlnth = reader.readUShort()
            val tmrlnth = reader.readUShort()
            val tpxcdlnth = reader.readUShort()
            if (tpxcdlnth > 0) {
                transparentCode = reader.readBitsAsLong(tpxcdlnth)
                hasTransparentCode = true
            }
            val totalBlocks = segment.blocksPerRow * segment.blocksPerCol * nb
            if (bmrlnth > 0) {
                blockOffsets = IntArray(totalBlocks)
                for (i in 0 until totalBlocks) {
                    blockOffsets[i] = reader.readUInt().toInt()
                }
            }
            if (tmrlnth > 0) {
                // Pad-pixel mask: skip (§5.4.1.2 — we currently treat the
                // transparent code applied per-pixel as good enough).
                reader.skip(totalBlocks * 4)
            }
            dataStartAfterMask = maskStart + imdatoff
        }

        // Sample buffer interleaved per-pixel for the significant region.
        val samples = IntArray(w * h * nb)

        // Helper to write a single sample respecting the spec's block grid.
        fun writeSample(row: Int, col: Int, band: Int, value: Int) {
            if (row < 0 || row >= h || col < 0 || col >= w) return
            samples[(row * w + col) * nb + band] = value
        }

        val nbpr = segment.blocksPerRow
        val nbpc = segment.blocksPerCol
        val nppbh = segment.pixelsPerBlockH
        val nppbv = segment.pixelsPerBlockV
        val pixelsPerBlock = nppbh * nppbv

        // Read one sample at the current reader cursor. Caller picks bps.
        fun readSample(): Int = when (bytesPerSample) {
            1 -> reader.readUByte()
            2 -> reader.readUShort()
            else -> error("unreachable")
        }

        // Test for masked / missing block (only meaningful when blockOffsets != null).
        fun blockMissing(blockIdx: Int): Boolean =
            blockOffsets != null && blockOffsets[blockIdx] == -1 /* 0xFFFFFFFF as Int */

        // Apply transparent-code suppression if the block is present but
        // declares a transparent value per pixel.
        fun applyTransparent(value: Int): Int =
            if (hasTransparentCode && value.toLong() == transparentCode) 0 else value

        when (segment.imageMode) {
            NitfImageMode.BAND_BLOCK -> {
                // For IMODE B, each block stores all bands consecutively:
                //   block_data = [band0(NPPBV × NPPBH), band1(...), ...]
                // The BMR (when present) has NBPR × NBPC × NBANDS entries
                // ordered the same way: (block, band) with band innermost.
                for (by in 0 until nbpc) {
                    for (bx in 0 until nbpr) {
                        for (band in 0 until nb) {
                            val gIdx = (by * nbpr + bx) * nb + band
                            if (blockMissing(gIdx)) {
                                // Seek past the block of zeros if NC; for NM
                                // with masked blocks the bytes are absent.
                                if (segment.compression == NitfCompression.NC) {
                                    reader.skip(pixelsPerBlock * bytesPerSample)
                                }
                                continue
                            }
                            if (segment.compression == NitfCompression.NM && blockOffsets != null) {
                                reader.seek(dataStartAfterMask + blockOffsets[gIdx])
                            }
                            for (py in 0 until nppbv) {
                                for (px in 0 until nppbh) {
                                    val v = applyTransparent(readSample())
                                    writeSample(by * nppbv + py, bx * nppbh + px, band, v)
                                }
                            }
                        }
                    }
                }
            }
            NitfImageMode.BAND_PIXEL -> {
                // Each block is a NPPBV × NPPBH grid of pixels; each pixel is
                // NBANDS consecutive samples.
                for (by in 0 until nbpc) {
                    for (bx in 0 until nbpr) {
                        val gIdx = by * nbpr + bx
                        if (blockMissing(gIdx)) {
                            if (segment.compression == NitfCompression.NC) {
                                reader.skip(pixelsPerBlock * nb * bytesPerSample)
                            }
                            continue
                        }
                        if (segment.compression == NitfCompression.NM && blockOffsets != null) {
                            reader.seek(dataStartAfterMask + blockOffsets[gIdx])
                        }
                        for (py in 0 until nppbv) {
                            for (px in 0 until nppbh) {
                                for (band in 0 until nb) {
                                    val v = applyTransparent(readSample())
                                    writeSample(by * nppbv + py, bx * nppbh + px, band, v)
                                }
                            }
                        }
                    }
                }
            }
            NitfImageMode.BAND_ROW -> {
                // Each block stores NBANDS-wise interleaved rows:
                //   row_block = [row(band0), row(band1), ..., row(bandN-1)] × NPPBV
                for (by in 0 until nbpc) {
                    for (bx in 0 until nbpr) {
                        val gIdx = by * nbpr + bx
                        if (blockMissing(gIdx)) {
                            if (segment.compression == NitfCompression.NC) {
                                reader.skip(pixelsPerBlock * nb * bytesPerSample)
                            }
                            continue
                        }
                        if (segment.compression == NitfCompression.NM && blockOffsets != null) {
                            reader.seek(dataStartAfterMask + blockOffsets[gIdx])
                        }
                        for (py in 0 until nppbv) {
                            for (band in 0 until nb) {
                                for (px in 0 until nppbh) {
                                    val v = applyTransparent(readSample())
                                    writeSample(by * nppbv + py, bx * nppbh + px, band, v)
                                }
                            }
                        }
                    }
                }
            }
            NitfImageMode.BAND_SEQUENTIAL -> {
                // Bands stored consecutively at the segment level:
                //   [all blocks of band0][all blocks of band1]...
                // Block grid is still row-major within each band.
                for (band in 0 until nb) {
                    for (by in 0 until nbpc) {
                        for (bx in 0 until nbpr) {
                            val gIdx = band * (nbpc * nbpr) + by * nbpr + bx
                            if (blockMissing(gIdx)) {
                                if (segment.compression == NitfCompression.NC) {
                                    reader.skip(pixelsPerBlock * bytesPerSample)
                                }
                                continue
                            }
                            if (segment.compression == NitfCompression.NM && blockOffsets != null) {
                                reader.seek(dataStartAfterMask + blockOffsets[gIdx])
                            }
                            for (py in 0 until nppbv) {
                                for (px in 0 until nppbh) {
                                    val v = applyTransparent(readSample())
                                    writeSample(by * nppbv + py, bx * nppbh + px, band, v)
                                }
                            }
                        }
                    }
                }
            }
        }

        return RawImage(
            width = w,
            height = h,
            numBands = nb,
            bitsPerSample = bps,
            samples = samples,
        )
    }

    private fun rawToArgb(segment: NitfImageSegment, raw: RawImage): IntArray {
        val w = raw.width
        val h = raw.height
        val nb = raw.numBands
        val signed = segment.pixelValueType == NitfPixelValueType.SIGNED
        val shift = when (raw.bitsPerSample) {
            8 -> 0
            16 -> 8
            else -> 0
        }
        val out = IntArray(w * h)

        val lut: Array<IntArray>? = segment.bands.firstOrNull()?.lookupTables
        val isRgbLut = segment.imageRepresentation == NitfImageRepresentation.RGB_LUT && lut != null

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pi = (y * w + x) * nb
                val argb: Int = when (segment.imageRepresentation) {
                    NitfImageRepresentation.MONO -> {
                        val v0 = raw.samples[pi]
                        val sample = if (signed && raw.bitsPerSample == 16) {
                            (v0.toShort().toInt() + 32768) ushr shift
                        } else {
                            v0 ushr shift
                        }
                        if (lut != null && lut.size == 1) {
                            // MONO with a 1-table LUT is a grey ramp — apply.
                            val g = lut[0][sample.coerceIn(0, lut[0].size - 1)] and 0xFF
                            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                        } else {
                            val g = sample and 0xFF
                            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                        }
                    }
                    NitfImageRepresentation.RGB -> {
                        val r = (raw.samples[pi] ushr shift) and 0xFF
                        val g = (raw.samples[pi + 1] ushr shift) and 0xFF
                        val b = (raw.samples[pi + 2] ushr shift) and 0xFF
                        val a = if (nb >= 4) (raw.samples[pi + 3] ushr shift) and 0xFF else 0xFF
                        (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    NitfImageRepresentation.RGB_LUT -> {
                        val idx = raw.samples[pi] and 0xFF
                        if (isRgbLut && lut.size >= 3) {
                            val r = lut[0][idx.coerceIn(0, lut[0].size - 1)] and 0xFF
                            val g = lut[1][idx.coerceIn(0, lut[1].size - 1)] and 0xFF
                            val b = lut[2][idx.coerceIn(0, lut[2].size - 1)] and 0xFF
                            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        } else {
                            // Mis-tagged — fall back to grey ramp from the raw index.
                            (0xFF shl 24) or (idx shl 16) or (idx shl 8) or idx
                        }
                    }
                    NitfImageRepresentation.MULTI -> {
                        // Best-effort: take first three bands as RGB.
                        val r = if (nb >= 1) (raw.samples[pi] ushr shift) and 0xFF else 0
                        val g = if (nb >= 2) (raw.samples[pi + 1] ushr shift) and 0xFF else 0
                        val b = if (nb >= 3) (raw.samples[pi + 2] ushr shift) and 0xFF else 0
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    else -> {
                        // NODISPLY / NVECTOR / POLAR / VPH / YCbCr601 / MITM
                        // are out of scope for ARGB conversion; emit opaque
                        // grey from band 0 as a least-surprising default.
                        val v = (raw.samples[pi] ushr shift) and 0xFF
                        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                }
                out[y * w + x] = argb
            }
        }
        return out
    }
}
