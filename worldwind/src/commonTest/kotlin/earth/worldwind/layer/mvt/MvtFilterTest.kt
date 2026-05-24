package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MvtFilterTest {

    private val motorway = mapOf<String, Any?>("kind" to "motorway", "lanes" to 4L, "admin_level" to 2L)
    private val residential = mapOf<String, Any?>("kind" to "residential", "lanes" to 2L)
    private val unknown = mapOf<String, Any?>()

    @Test fun eqMatchesByValue() {
        assertTrue(("kind" eq "motorway").matches(motorway))
        assertFalse(("kind" eq "motorway").matches(residential))
        assertFalse(("kind" eq "motorway").matches(unknown))
    }

    @Test fun notEqInvertsEq() {
        assertFalse(("kind" notEq "motorway").matches(motorway))
        assertTrue(("kind" notEq "motorway").matches(residential))
        // Absent key returns NOT (null == "motorway") = true. Matches Mapbox semantics.
        assertTrue(("kind" notEq "motorway").matches(unknown))
    }

    @Test fun inMatchesAnyValue() {
        val anyRoad = "kind" isIn setOf("motorway", "trunk", "primary")
        assertTrue(anyRoad.matches(motorway))
        assertFalse(anyRoad.matches(residential))
    }

    @Test fun hasChecksKeyPresence() {
        val hasLanes = MvtFilter.Has("lanes")
        assertTrue(hasLanes.matches(motorway))
        assertTrue(hasLanes.matches(residential))
        assertFalse(hasLanes.matches(unknown))
    }

    @Test fun numericLteAndGteHandleTypes() {
        // admin_level=2L should compare ≤ 4.0 as expected
        assertTrue(MvtFilter.NumericLte("admin_level", 4.0).matches(motorway))
        assertTrue(MvtFilter.NumericLte("admin_level", 2.0).matches(motorway))
        assertFalse(MvtFilter.NumericLte("admin_level", 1.0).matches(motorway))
        assertTrue(MvtFilter.NumericGte("lanes", 2.0).matches(residential))
        assertFalse(MvtFilter.NumericGte("lanes", 5.0).matches(motorway))
        // Missing key = false (no implicit zero).
        assertFalse(MvtFilter.NumericLte("missing", 1.0).matches(unknown))
        assertFalse(MvtFilter.NumericGte("missing", 1.0).matches(unknown))
    }

    @Test fun allShortCircuits() {
        val allF = MvtFilter.all("kind" eq "motorway", MvtFilter.NumericGte("lanes", 3.0))
        assertTrue(allF.matches(motorway))
        assertFalse(allF.matches(residential))
        // Empty All = true.
        assertTrue(MvtFilter.all().matches(unknown))
    }

    @Test fun anyShortCircuits() {
        val anyF = MvtFilter.any("kind" eq "motorway", "kind" eq "trunk")
        assertTrue(anyF.matches(motorway))
        assertFalse(anyF.matches(residential))
        // Empty Any = false.
        assertFalse(MvtFilter.any().matches(unknown))
    }

    @Test fun notNegates() {
        assertFalse(MvtFilter.not("kind" eq "motorway").matches(motorway))
        assertTrue(MvtFilter.not("kind" eq "motorway").matches(residential))
    }

    @Test fun alwaysReturnsTrueForAnyInput() {
        assertTrue(MvtFilter.Always.matches(motorway))
        assertTrue(MvtFilter.Always.matches(unknown))
    }

    @Test fun nestedCombinations() {
        // (kind=motorway OR kind=trunk) AND admin_level <= 4
        val complex = MvtFilter.all(
            MvtFilter.any("kind" eq "motorway", "kind" eq "trunk"),
            MvtFilter.NumericLte("admin_level", 4.0),
        )
        assertTrue(complex.matches(motorway))
        assertFalse(complex.matches(residential))
    }

    @Test fun numericComparisonsAcceptIntAndFloat() {
        val intMap = mapOf<String, Any?>("v" to 5)
        val longMap = mapOf<String, Any?>("v" to 5L)
        val floatMap = mapOf<String, Any?>("v" to 5.0f)
        val doubleMap = mapOf<String, Any?>("v" to 5.0)
        for (props in listOf(intMap, longMap, floatMap, doubleMap)) {
            assertTrue(MvtFilter.NumericGte("v", 5.0).matches(props))
            assertTrue(MvtFilter.NumericLte("v", 5.0).matches(props))
        }
        // Non-number value returns false even for both comparisons.
        assertEquals(false, MvtFilter.NumericGte("v", 5.0).matches(mapOf("v" to "five")))
    }
}
