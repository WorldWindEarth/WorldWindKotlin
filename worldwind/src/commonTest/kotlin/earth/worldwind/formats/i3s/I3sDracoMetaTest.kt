package earth.worldwind.formats.i3s

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [I3sDracoMeta] against a real Draco header+metadata prefix captured from an ArcGIS-authored
 *  SLPK node (`i3s-scale_x/y` on the position attribute + `i3s-attribute-type` on feature-index). */
class I3sDracoMetaTest {
    /** First 120 bytes of a Draco geometry buffer; the metadata section ends at offset 116. */
    private val fixture = (
        "445241434f0202010100800200020b6933732d7363616c655f78082cb6166296c2ed3e0b6933732d7363616c655f79" +
        "0891a9b6f4cbd9e23e000202126933732d6174747269627574652d747970650d666561747572652d696e646578" +
        "0f6933732d666561747572652d696473040100000000000002cc7bcb"
    ).hexToBytes()

    @Test fun sniffsDracoMagic() {
        assertTrue(I3sDracoMeta.isDraco(fixture))
        // Uncompressed I3S geometry starts with a binary vertexCount, never the DRACO magic.
        assertFalse(I3sDracoMeta.isDraco(byteArrayOf(0x36, 0x2e, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0, 0, 0)))
        assertFalse(I3sDracoMeta.isDraco(ByteArray(4)))
    }

    @Test fun readsPositionScalesFromAttributeMetadata() {
        val scales = assertNotNull(I3sDracoMeta.positionScales(fixture))
        assertEquals(1.4190724928332577e-5, scales[0], 1e-20)
        assertEquals(8.988746819611745e-6, scales[1], 1e-20)
    }

    @Test fun noMetadataFlagYieldsNull() {
        val noMeta = fixture.copyOf().also { it[9] = 0; it[10] = 0 }
        assertNull(I3sDracoMeta.positionScales(noMeta))
    }

    @Test fun metadataWithoutScalesYieldsNull() {
        // Projected-CRS packages carry only i3s-attribute-type entries: drop the first (position)
        // attribute's metadata by rewriting the fixture's attribute count and splicing it out.
        val spliced = fixture.copyOfRange(0, 11) + byteArrayOf(0x01) + fixture.copyOfRange(57, fixture.size)
        assertNull(I3sDracoMeta.positionScales(spliced))
    }

    @Test fun truncatedMetadataYieldsNull() {
        for (size in intArrayOf(11, 12, 20, 40)) {
            assertNull(I3sDracoMeta.positionScales(fixture.copyOf(size)), "prefix of $size bytes")
        }
    }

    private fun String.hexToBytes() = ByteArray(length / 2) {
        ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte()
    }
}
