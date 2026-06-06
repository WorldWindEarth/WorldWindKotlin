package earth.worldwind.formats.collada

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the streaming parser ([ColladaStreamParser] + [ColladaTokenizer]) and the buffer-core
 * refactor of [ColladaMesh]: streamed output must match the DOM path byte-for-byte, plus a few
 * hand-computed absolute assertions pin the index/dedup/triangulation algorithm itself.
 */
class ColladaStreamingTest {

    private fun streamCatalog(dae: String) = ColladaStreamParser.buildCatalog(StringReader(dae)).first

    private fun assertMeshEquals(expected: ColladaParsedMesh, actual: ColladaParsedMesh) {
        assertContentEquals(expected.vertices, actual.vertices, "vertices")
        assertContentEquals(expected.normals, actual.normals, "normals")
        assertContentEquals(expected.uvs, actual.uvs, "uvs")
        assertContentEquals(expected.indices, actual.indices, "indices")
        assertContentEquals(expected.indicesShort, actual.indicesShort, "indicesShort")
        assertEquals(expected.indexedRendering, actual.indexedRendering, "indexedRendering")
        assertEquals(expected.is32BitIndices, actual.is32BitIndices, "is32BitIndices")
        assertEquals(expected.material, actual.material, "material")
        assertEquals(expected.clamp, actual.clamp, "clamp")
    }

    private fun assertStreamMatchesDom(dae: String) {
        val dom = parseCatalogDom(dae)
        val stream = streamCatalog(dae)
        assertEquals(dom.meshes.keys, stream.meshes.keys, "geometry ids")
        for ((id, domMesh) in dom.meshes) {
            val streamMesh = stream.meshes.getValue(id)
            assertEquals(domMesh.parsedMeshes.size, streamMesh.parsedMeshes.size, "primitive count for $id")
            for (i in domMesh.parsedMeshes.indices) assertMeshEquals(domMesh.parsedMeshes[i], streamMesh.parsedMeshes[i])
        }
    }

    @Test
    fun triangle_nonIndexed_matchesDomAndExpected() {
        assertStreamMatchesDom(TRIANGLE)
        val mesh = streamCatalog(TRIANGLE).meshes.getValue("g1").parsedMeshes.single()
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), mesh.vertices)
        assertContentEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), mesh.uvs)
        assertEquals(false, mesh.indexedRendering)
        assertNull(mesh.indices)
        assertNull(mesh.indicesShort)
        assertEquals("mat", mesh.material)
    }

    @Test
    fun triangles_indexedDedup_matchesDomAndExpected() {
        assertStreamMatchesDom(TWO_TRIANGLES)
        val mesh = streamCatalog(TWO_TRIANGLES).meshes.getValue("g1").parsedMeshes.single()
        assertTrue(mesh.indexedRendering)
        // (0,0) and (2,2) dedup across the two triangles -> 4 unique verts, indices 0,1,2,0,2,3
        assertContentEquals(shortArrayOf(0, 1, 2, 0, 2, 3), mesh.indicesShort)
        assertContentEquals(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f), mesh.vertices
        )
    }

    @Test
    fun polylist_quadFan_matchesDom() {
        // Exercises the vcount/fan-triangulation path; stream output must equal the DOM path.
        assertStreamMatchesDom(QUAD_POLYLIST)
        assertEquals(1, streamCatalog(QUAD_POLYLIST).meshes.getValue("g1").parsedMeshes.size)
    }

    @Test
    fun unitScale_isParsed() {
        val (_, root) = ColladaStreamParser.buildCatalog(StringReader(TRIANGLE))
        assertEquals(0.5, unitScale(root))
    }

    @Test
    fun upAxis_parsedFromAssetWithSpecDefault() {
        assertEquals(ColladaUpAxis.Y_UP, upAxis(XmlElement.parse(TRIANGLE)))
        assertEquals(ColladaUpAxis.Y_UP, ColladaUpAxis.fromString(null)) // COLLADA default
        assertEquals(ColladaUpAxis.Z_UP, ColladaUpAxis.fromString("Z_UP"))
        assertEquals(ColladaUpAxis.X_UP, ColladaUpAxis.fromString(" x_up "))
    }

    companion object {
        private const val TRIANGLE = """<?xml version="1.0"?>
<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
  <!-- a comment with stray > and ] chars to exercise comment skipping -->
  <asset><unit meter="0.5" name="meter"/><up_axis>Y_UP</up_axis></asset>
  <library_geometries>
    <geometry id="g1" name="g1">
      <mesh>
        <source id="pos">
          <float_array id="pos-arr" count="9">0 0 0 1 0 0 0 1 0</float_array>
          <technique_common><accessor source="#pos-arr" count="3" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common>
        </source>
        <source id="uv">
          <float_array id="uv-arr" count="6">0 0 1 0 0 1</float_array>
          <technique_common><accessor source="#uv-arr" count="3" stride="2"><param name="S" type="float"/><param name="T" type="float"/></accessor></technique_common>
        </source>
        <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
        <triangles material="mat" count="1">
          <input semantic="VERTEX" source="#verts" offset="0"/>
          <input semantic="TEXCOORD" source="#uv" offset="1" set="0"/>
          <p>0 0 1 1 2 2</p>
        </triangles>
      </mesh>
    </geometry>
  </library_geometries>
</COLLADA>"""

        private const val TWO_TRIANGLES = """<?xml version="1.0"?>
<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
  <library_geometries>
    <geometry id="g1">
      <mesh>
        <source id="pos">
          <float_array id="pos-arr" count="12">0 0 0 1 0 0 0 1 0 1 1 0</float_array>
          <technique_common><accessor source="#pos-arr" count="4" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common>
        </source>
        <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
        <triangles count="2">
          <input semantic="VERTEX" source="#verts" offset="0"/>
          <p>0 1 2 0 2 3</p>
        </triangles>
      </mesh>
    </geometry>
  </library_geometries>
</COLLADA>"""

        private const val QUAD_POLYLIST = """<?xml version="1.0"?>
<COLLADA xmlns="http://www.collada.org/2005/11/COLLADASchema" version="1.4.1">
  <library_geometries>
    <geometry id="g1">
      <mesh>
        <source id="pos">
          <float_array id="pos-arr" count="12">0 0 0 1 0 0 1 1 0 0 1 0</float_array>
          <technique_common><accessor source="#pos-arr" count="4" stride="3"><param name="X" type="float"/><param name="Y" type="float"/><param name="Z" type="float"/></accessor></technique_common>
        </source>
        <vertices id="verts"><input semantic="POSITION" source="#pos"/></vertices>
        <polylist count="1">
          <input semantic="VERTEX" source="#verts" offset="0"/>
          <vcount>4</vcount>
          <p>0 1 2 3</p>
        </polylist>
      </mesh>
    </geometry>
  </library_geometries>
</COLLADA>"""
    }
}
