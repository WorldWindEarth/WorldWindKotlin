package earth.worldwind.geom

import kotlin.test.*

class RangeTest {
    @Test
    fun testConstructor_Default() {
        val range = Range()
        assertNotNull(range)
        assertEquals(0, range.lower, "lower")
        assertEquals(0, range.upper, "upper")
    }

    @Test
    fun testConstructor_Copy() {
        val original = Range(1, 2)
        val range = Range(original)
        assertNotNull(range)
        assertEquals(1, range.lower, "lower")
        assertEquals(2, range.upper, "upper")
    }

    @Test
    fun testConstructor_Parameters() {
        val range = Range(1, 2)
        assertNotNull(range)
        assertEquals(1, range.lower, "lower")
        assertEquals(2, range.upper, "upper")
    }

    @Test
    fun testEquals() {
        val range1 = Range(1, 2)
        val range2 = Range(1, 2)
        assertEquals(range1, range2, "equals")
    }

    @Test
    fun testEquals_Null() {
        val range = Range(1, 2)
        assertNotNull(range, "inequality with null")
    }

    @Test
    fun testEquals_Inequality() {
        val range1 = Range(1, 2)
        val range2 = Range(2, 1)
        assertNotEquals(range1, range2, "not equals")
    }

    @Test
    fun testHashCode() {
        val range1 = Range(1, 2)
        val range2 = Range(range1)
        val range3 = Range(2, 1)
        val hashCode1 = range1.hashCode()
        val hashCode2 = range2.hashCode()
        val hashCode3 = range3.hashCode()
        assertEquals(hashCode1, hashCode2)
        assertNotEquals(hashCode1, hashCode3)
    }

    @Test
    fun testToString() {
        val range = Range(1, 2)
        val string = range.toString()
        assertTrue(string.contains(range.lower.toString()), "lower")
        assertTrue(string.contains(range.upper.toString()), "upper")
    }

    @Test
    fun testSet_Parameters() {
        val range = Range()
        range.set(1, 2)
        assertEquals(1, range.lower, "lower")
        assertEquals(2, range.upper, "upper")
    }

    @Test
    fun testSet_Copy() {
        val original = Range(1, 2)
        val range = Range()
        range.copy(original)
        assertNotSame(original, range, "not the same reference")
        assertEquals(1, range.lower, "lower")
        assertEquals(2, range.upper, "upper")
    }

    @Test
    fun testSetEmpty() {
        val range = Range(1, 2)
        range.setEmpty()
        assertEquals(0, range.lower, "lower")
        assertEquals(0, range.upper, "upper")
    }

    @Test
    fun testIsEmpty() {
        val range1 = Range()
        val range2 = Range(1, 2)
        assertTrue(range1.isEmpty, "range is empty")
        assertFalse(range2.isEmpty, "range is not empty")
    }

    @Test
    fun testLength() {
        val range = Range(1, 2)
        assertEquals(1, range.length, "length")
    }

    @Test
    fun testLength_Empty() {
        val range = Range()
        assertEquals(0, range.length, "length")
    }
}