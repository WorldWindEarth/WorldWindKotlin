package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Camera
import earth.worldwind.geom.Location
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe
import earth.worldwind.globe.elevation.coverage.AbstractElevationCoverage
import earth.worldwind.globe.geoid.EGM96Geoid
import earth.worldwind.globe.geoid.Geoid
import earth.worldwind.globe.projection.GeographicProjection
import earth.worldwind.globe.projection.MercatorProjection
import earth.worldwind.globe.projection.Wgs84Projection
import earth.worldwind.globe.terrain.BasicTerrain
import earth.worldwind.globe.terrain.Terrain
import earth.worldwind.globe.terrain.TerrainTile
import earth.worldwind.render.RenderContext
import earth.worldwind.util.LevelSet
import kotlinx.coroutines.runBlocking
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [PointMemo] keeps its terms per globe: windows and 2D offset passes stay correct and hold no globe or tile. */
class PointMemoSlotsTest {
    /** Hills, on 2D and 3D terrain alike; counts point lookups to tell a cache hit from a recompute. */
    private class HillCoverage : AbstractElevationCoverage() {
        var pointLookups = 0
        override fun clear() {}
        override fun doGetElevation(latitude: Angle, longitude: Angle, retrieve: Boolean): Float {
            pointLookups++
            return height(latitude.inDegrees, longitude.inDegrees)
        }
        override fun doGetElevationGrid(gridSector: Sector, gridWidth: Int, gridHeight: Int, result: FloatArray) {
            for (r in 0 until gridHeight) for (c in 0 until gridWidth) {
                val lat = gridSector.minLatitude.inDegrees + gridSector.deltaLatitude.inDegrees * r / (gridHeight - 1)
                val lon = gridSector.minLongitude.inDegrees + gridSector.deltaLongitude.inDegrees * c / (gridWidth - 1)
                result[r * gridWidth + c] = height(lat, lon)
            }
        }
        override fun doGetElevationLimits(sector: Sector, result: FloatArray) { result[0] = -200f; result[1] = 600f }
        fun height(lat: Double, lon: Double) = (400.0 * sin(lat * 40.0) * sin(lon * 55.0) + 200.0).toFloat()
    }

    /** One window: its globe and its own terrain tiles. */
    private inner class View(projection: GeographicProjection) {
        val globe = Globe(geoid = geoid, projection = projection).also { it.elevationModel.addCoverage(coverage) }
        val tiles = tiles()
    }

    private val coverage = HillCoverage()
    private val geoid = EGM96Geoid()
    private val rc = RenderContext().also { it.camera = Camera() }
    private val terrainSector = Sector.fromDegrees(48.0, 37.0, 1.0, 1.0)
    private val fullSphere = Sector().setFullSphere()
    private val levelSet = LevelSet(fullSphere, fullSphere, Location.fromDegrees(0.1, 0.1), 1, 32, 32)
    private val modes = listOf(AltitudeMode.CLAMP_TO_GROUND to 0.0, AltitudeMode.RELATIVE_TO_GROUND to 150.0)

    @BeforeTest
    fun setUp() {
        // Share one loaded geoid: each globe's own loads in the background and reads 0 until then
        runBlocking { geoid.awaitDataLoaded() }
    }

    private fun tiles() = buildList {
        for (r in 0 until 10) for (c in 0 until 10) {
            add(TerrainTile(Sector.fromDegrees(48.0 + r * 0.1, 37.0 + c * 0.1, 0.1, 0.1), levelSet.firstLevel, r, c))
        }
    }

    /** One render pass of a window at a globe offset, with the tiles it tessellated. */
    private fun pass(view: View, offset: Globe.Offset = Globe.Offset.Center, tiles: List<TerrainTile> = view.tiles) {
        rc.globe = view.globe
        view.globe.offset = offset
        rc.globeState = view.globe.state
        rc.elevationModelTimestamp = view.globe.elevationModel.timestamp
        tiles.forEach { it.prepare(rc) }
        rc.terrain = BasicTerrain(tiles, terrainSector, null)
    }

    private fun memoPoint(memo: PointMemo, lat: Double, lon: Double, altitude: Double, mode: AltitudeMode) =
        memo.geographicToCartesian(rc, fromDegrees(lat), fromDegrees(lon), altitude, mode, Vec3())

    private fun expected(lat: Double, lon: Double, altitude: Double, mode: AltitudeMode) =
        rc.geographicToCartesian(fromDegrees(lat), fromDegrees(lon), altitude, mode, Vec3())

    private fun assertMatches(memo: PointMemo, lat: Double, lon: Double, message: String) {
        for ((mode, altitude) in modes) {
            val actual = memoPoint(memo, lat, lon, altitude, mode)
            assertEquals(0.0, actual.distanceTo(expected(lat, lon, altitude, mode)), 0.01, "$message $mode")
        }
    }

    @Test
    fun test3DAnd2DWindowsMatchTheRenderContext() {
        val views = listOf(View(Wgs84Projection()), View(MercatorProjection()))
        val memos = List(100) { PointMemo() }
        repeat(3) { round ->
            for (view in views) {
                pass(view)
                for ((i, memo) in memos.withIndex()) {
                    val lat = 48.0 + (i * 0.6180339887 % 1.0)
                    val lon = 37.0 + (i * 0.4142135623 % 1.0)
                    assertMatches(memo, lat, lon, "round $round ${view.globe.projection.displayName} at $lat,$lon")
                }
            }
        }
    }

    @Test
    fun testAlternatingWindowsKeepTheirElevation() {
        // Off the terrain a clamped point uses the elevation model: one lookup per globe
        val views = listOf(View(Wgs84Projection()), View(MercatorProjection()))
        val memo = PointMemo()
        coverage.pointLookups = 0
        repeat(3) {
            for (view in views) {
                pass(view, tiles = emptyList())
                memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
            }
        }
        assertEquals(views.size, coverage.pointLookups)
    }

    @Test
    fun testOffsetPassesMatchTheRenderContext() {
        val view = View(MercatorProjection())
        val memo = PointMemo()
        repeat(2) {
            for (offset in Globe.Offset.entries) {
                pass(view, offset)
                assertMatches(memo, 48.55, 37.55, "$offset")
            }
        }
    }

    @Test
    fun testProjectionSwitchResetsThatGlobe() {
        val view = View(Wgs84Projection())
        val memo = PointMemo()
        pass(view)
        assertMatches(memo, 48.55, 37.55, "3D")
        view.globe.projection = MercatorProjection()
        pass(view)
        assertMatches(memo, 48.55, 37.55, "switched to 2D")
    }

    @Test
    fun testManyAlternatingWindowsKeepTheirElevation() {
        // Nine windows, 2D ones at all offsets: one elevation lookup per globe
        val views = List(9) { View(if (it % 3 == 0) MercatorProjection() else Wgs84Projection()) }
        val memo = PointMemo()
        coverage.pointLookups = 0
        repeat(3) {
            for (view in views) for (offset in Globe.Offset.entries) {
                pass(view, offset, tiles = emptyList())
                memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
            }
        }
        assertEquals(views.size, coverage.pointLookups)
    }

    @Test
    fun testMoreWindowsThanSlotsStayCorrect() {
        // Off the terrain, so no tiles are needed; beyond MAX_SLOTS the least recently used is reused
        val views = List(PointMemo.MAX_SLOTS + 2) { View(if (it % 2 == 0) Wgs84Projection() else MercatorProjection()) }
        val memo = PointMemo()
        repeat(2) {
            for ((i, view) in views.withIndex()) {
                pass(view, tiles = emptyList())
                assertMatches(memo, 48.55, 37.55, "window $i")
            }
        }
    }

    @Test
    fun testOffTheTerrainMatchesTheRenderContext() {
        for (view in listOf(View(Wgs84Projection()), View(MercatorProjection()))) {
            val memo = PointMemo()
            pass(view, tiles = view.tiles.filter { !it.sector.contains(fromDegrees(48.55), fromDegrees(37.55)) })
            assertMatches(memo, 48.55, 37.55, view.globe.projection.displayName)
        }
    }

    @Test
    fun testGeoidLoadedLaterIsPickedUp() {
        // Neither the geoid offset nor the elevation may keep the 0 read while loading
        val lateGeoid = object : Geoid {
            override val displayName = "late"
            override var isLoaded = false
            override fun getOffset(latitude: Angle, longitude: Angle) = if (isLoaded) 25f else 0f
        }
        val view = View(Wgs84Projection()).also { it.globe.geoid = lateGeoid }
        val memo = PointMemo()
        pass(view, tiles = emptyList())
        memoPoint(memo, 48.55, 37.55, 100.0, AltitudeMode.ABOVE_SEA_LEVEL)
        memoPoint(memo, 48.55, 37.55, 0.0, AltitudeMode.CLAMP_TO_GROUND)
        lateGeoid.isLoaded = true
        pass(view, tiles = emptyList())
        for (mode in listOf(AltitudeMode.ABOVE_SEA_LEVEL, AltitudeMode.CLAMP_TO_GROUND)) {
            val altitude = if (mode == AltitudeMode.ABOVE_SEA_LEVEL) 100.0 else 0.0
            val actual = memoPoint(memo, 48.55, 37.55, altitude, mode)
            assertEquals(0.0, actual.distanceTo(expected(48.55, 37.55, altitude, mode)), 0.01, "$mode")
        }
    }

    @Test
    fun testHoldsNoGlobeOrTileReference() {
        // No globe, tile or terrain, nor a collection, map or lambda that could hide one
        val forbidden = listOf(
            Globe::class.java, TerrainTile::class.java, Terrain::class.java, RenderContext::class.java,
            Collection::class.java, Map::class.java, Function::class.java
        )
        val slotClass = PointMemo::class.java.declaredClasses.single { it.simpleName == "Slot" }
        for (type in listOf(PointMemo::class.java, slotClass)) for (field in type.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            val fieldType = if (field.type.isArray) field.type.componentType else field.type
            val held = forbidden.filter { it.isAssignableFrom(fieldType) || fieldType == Any::class.java }
            assertTrue(held.isEmpty(), "${type.simpleName}.${field.name}: ${field.type.name}")
        }
    }
}
