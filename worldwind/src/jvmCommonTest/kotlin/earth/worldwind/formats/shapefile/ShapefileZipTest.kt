package earth.worldwind.formats.shapefile

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies the JVM/Android-only ZIP extraction path. Bundles the test shapefile bytes
 * into an in-memory ZIP, then exercises [extractShapefileBundle].
 */
class ShapefileZipTest {

    @Test
    fun extractsAllFourSidecars() {
        val zip = buildZip(
            "world.shp" to byteArrayOf(1, 2, 3),
            "world.dbf" to byteArrayOf(4, 5),
            "world.prj" to "GEOGCS[\"WGS 84\"]".toByteArray(),
            "world.cpg" to "UTF-8".toByteArray(),
        )
        val bundle = extractShapefileBundle(zip)
        assertNotNull(bundle.shp)
        assertEquals(3, bundle.shp.size)
        assertNotNull(bundle.dbf); assertEquals(2, bundle.dbf.size)
        assertNotNull(bundle.prj)
        assertNotNull(bundle.cpg)
    }

    @Test
    fun missingSidecarsAreNull() {
        val zip = buildZip("only.shp" to byteArrayOf(1))
        val bundle = extractShapefileBundle(zip)
        assertNotNull(bundle.shp)
        assertNull(bundle.dbf)
        assertNull(bundle.prj)
        assertNull(bundle.cpg)
    }

    @Test
    fun takesFirstMatchWhenMultipleShapefilesPresent() {
        val zip = buildZip(
            "states.shp" to byteArrayOf(0x10),
            "counties.shp" to byteArrayOf(0x20),
        )
        val bundle = extractShapefileBundle(zip)
        assertEquals(0x10.toByte(), bundle.shp!![0])
    }

    @Test
    fun ignoresDirectoryEntriesAndCaseInExtensions() {
        val zip = buildZip(
            "subdir/" to ByteArray(0),
            "subdir/data.SHP" to byteArrayOf(0x42),
            "subdir/data.DBF" to byteArrayOf(0x43),
        )
        val bundle = extractShapefileBundle(zip)
        assertNotNull(bundle.shp)
        assertEquals(0x42.toByte(), bundle.shp[0])
        assertNotNull(bundle.dbf)
    }

    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                if (!entry.isDirectory) zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
