package earth.worldwind.formats.collada

import earth.worldwind.geom.AltitudeMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end KMZ load (JVM/Android path): extraction, KML `<Model>` placement, streamed `.dae`, texture wiring. */
class ColladaKmzTest {

    private fun zip(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(target.outputStream()).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun loadsKmz_appliesKmlPlacement_andResolvesTexture() {
        val kmz = File.createTempFile("wwtest", ".kmz").apply { deleteOnExit() }
        zip(kmz, linkedMapOf(
            "doc.kml" to KML.toByteArray(),
            "model.dae" to DAE.toByteArray(),
            "textures/tex.png" to ByteArray(0)
        ))

        val scene = loadColladaKmz(kmz)
        try {
            // Placement from <Model><Location>/<Scale>/<altitudeMode>
            assertEquals(40.5, scene.position.latitude.inDegrees, 1e-9)
            assertEquals(-105.25, scene.position.longitude.inDegrees, 1e-9)
            assertEquals(1500.0, scene.position.altitude, 1e-9)
            assertEquals(2.0, scene.scale, 1e-9)
            assertEquals(AltitudeMode.ABSOLUTE, scene.altitudeMode)

            // Texture factory resolves a zip entry (relative to the .dae folder) without extraction,
            // including a backslash-separated init_from path (Windows exporters).
            assertNotNull(scene.imageSourceFactory?.invoke("textures/tex.png"), "texture image source")
            assertNotNull(scene.imageSourceFactory?.invoke("textures\\tex.png"), "backslash texture path")
            assertTrue(scene.imageSourceFactory?.invoke("missing.png") == null, "absent texture -> null")
        } finally {
            scene.close() // closes the backing ZipFile
        }

        // close() is idempotent and releases the texture factory
        scene.close()
        assertTrue(scene.imageSourceFactory == null, "factory cleared after close")
    }

    @Test
    fun altitudeOffset_isAddedToAnchorAltitude() {
        val kmz = File.createTempFile("wwtest", ".kmz").apply { deleteOnExit() }
        zip(kmz, linkedMapOf("doc.kml" to KML.toByteArray(), "model.dae" to DAE.toByteArray()))

        val scene = loadColladaKmz(kmz, altitudeOffset = 250.0)
        try {
            assertEquals(1750.0, scene.position.altitude, 1e-9) // 1500 (KML) + 250
        } finally {
            scene.close()
        }
    }

    companion object {
        private const val KML = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Region><LatLonAltBox><altitudeMode>clampToGround</altitudeMode></LatLonAltBox></Region>
<Placemark><name>m</name><Model>
  <altitudeMode>absolute</altitudeMode>
  <Location><longitude>-105.25</longitude><latitude>40.5</latitude><altitude>1500</altitude></Location>
  <Orientation><heading>10</heading><tilt>0</tilt><roll>0</roll></Orientation>
  <Scale><x>2</x><y>2</y><z>2</z></Scale>
  <Link><href>model.dae</href></Link>
</Model></Placemark></kml>"""

        private const val DAE = """<?xml version="1.0"?>
<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
  <library_geometries>
    <geometry id="g1">
      <mesh>
        <source id="pos">
          <float_array id="pos-arr" count="9">0 0 0 1 0 0 0 1 0</float_array>
          <technique_common><accessor source="#pos-arr" count="3" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common>
        </source>
        <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
        <triangles count="1"><input semantic="VERTEX" source="#verts" offset="0"/><p>0 1 2</p></triangles>
      </mesh>
    </geometry>
  </library_geometries>
</COLLADA>"""
    }
}
