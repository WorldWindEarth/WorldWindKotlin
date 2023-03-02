package earth.worldwind.shape

import android.graphics.Typeface
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode
import earth.worldwind.render.Color
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlin.test.*

class TextAttributesTest {
    @BeforeTest
    fun setUp() {
        // Mock the static Typeface methods used by TextAttributes
        mockkStatic(Typeface::class)
        every { Typeface.defaultFromStyle(Typeface.NORMAL) } returns mockk(relaxed = true)
    }

    @Test
    fun testConstructor_Default() {
        val attributes = TextAttributes()
        assertNotNull(attributes)
        // Assert default values are as expected.
        assertEquals(Color(1f, 1f, 1f, 1f), attributes.textColor, "textColor should be white")
        assertEquals(Offset.bottomCenter(), attributes.textOffset, "textOffset should be bottom center")
        assertEquals(24.0f, attributes.font.size, 0.0f, "textSize should be 24.0")
        assertNull(attributes.font.typeface, "typeface should be null")
        assertTrue(attributes.isOutlineEnabled, "isOutlineEnabled should be true")
        assertTrue(attributes.isDepthTestEnabled, "isDepthTestEnabled should be true")
        assertEquals(3.0f, attributes.outlineWidth, 0.0f, "outlineWidth should be 3.0")
    }

    @Test
    fun testConstructor_Copy() {
        val attributes = TextAttributes()
        attributes.font.typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
        val copy = TextAttributes(attributes)
        assertNotNull(copy)
        assertEquals(attributes, copy)
        // Ensure we made a deep copy of the colors
        assertNotSame(copy.textColor, attributes.textColor)
        assertNotSame(copy.textOffset, attributes.textOffset)
        // Ensure we copied the typeface by reference
        assertSame(copy.font.typeface, attributes.font.typeface)
    }

    @Test
    fun testSet() {
        val attributes = TextAttributes()
        // create another attribute bundle with differing values
        val other = TextAttributes()
        other.textColor = Color(0f, 0f, 0f, 0f)
        other.textOffset = Offset(OffsetMode.PIXELS, 0.0, OffsetMode.PIXELS, 0.0)
        other.font.size = 0.0f
        other.font.typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
        other.isOutlineEnabled = false
        other.isDepthTestEnabled = false
        other.outlineWidth = 0.0f
        attributes.copy(other)
        assertEquals(attributes, other)
    }

    @Test
    fun testEquals() {
        val attributes = TextAttributes()
        val same = TextAttributes()
        assertEquals(same.textColor, attributes.textColor)
        assertEquals(same.textOffset, attributes.textOffset)
        assertEquals(same.font.size, attributes.font.size, 0.0f)
        assertEquals(same.font.typeface, attributes.font.typeface)
        assertEquals(same.isOutlineEnabled, attributes.isOutlineEnabled)
        assertEquals(same.isDepthTestEnabled, attributes.isDepthTestEnabled)
        assertEquals(same.outlineWidth, attributes.outlineWidth, 0.0f)
        assertEquals(attributes, attributes)
        assertEquals(attributes, same)
        assertEquals(same, attributes)
    }

    @Test
    fun testInequality() {
        val typical = TextAttributes()
        val different = TextAttributes()
        different.textColor = Color(0f, 0f, 0f, 0f)
        different.textOffset = Offset(OffsetMode.PIXELS, 0.0, OffsetMode.PIXELS, 0.0)
        different.font.size = 0.0f
        different.font.typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
        different.isOutlineEnabled = false
        different.isDepthTestEnabled = false
        different.outlineWidth = 0.0f
        assertNotEquals(different.textColor, typical.textColor)
        assertNotEquals(different.textOffset, typical.textOffset)
        assertNotEquals(different.font.size, typical.font.size)
        assertNotEquals(different.font.typeface, typical.font.typeface)
        assertNotEquals(different.isOutlineEnabled, typical.isOutlineEnabled)
        assertNotEquals(different.isDepthTestEnabled, typical.isDepthTestEnabled)
        assertNotEquals(different.outlineWidth, typical.outlineWidth)
        assertNotEquals(different, typical)
        assertNotEquals(typical, different)
        assertNotNull(typical)
    }

    @Test
    fun testHashCode() {
        // Three differing sets of attributes
        val a = TextAttributes()
        val b = TextAttributes()
        val c = TextAttributes()
        b.textColor = Color(0f, 0f, 0f, 0f)
        c.font.typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
        val aHash = a.hashCode()
        val bHash = b.hashCode()
        val cHash = c.hashCode()
        assertNotEquals(bHash, aHash, "a hash vs b hash")
        assertNotEquals(cHash, bHash, "b hash vs c hash")
        assertNotEquals(aHash, cHash, "c hash vs a hash")
    }

    @Test
    fun testGetTextColor() {
        val attributes = TextAttributes()
        val black = Color(0f, 0f, 0f, 1f)
        attributes.textColor = black
        assertEquals(black, attributes.textColor)
    }

    @Test
    fun testSetTextColor() {
        val attributes = TextAttributes()
        val black = Color(0f, 0f, 0f, 1f)
        attributes.textColor = black

        // Verify that the object is an equivalent deep copy.
        assertEquals(black, attributes.textColor)
        assertNotSame(black, attributes.textColor)
    }

    @Test
    fun testGetTextOffset() {
        val attributes = TextAttributes()
        val lowerLeft = Offset(OffsetMode.PIXELS, 0.0, OffsetMode.PIXELS, 0.0)
        attributes.textOffset = lowerLeft
        assertEquals(lowerLeft, attributes.textOffset)
    }

    @Test
    fun testSetTextOffset() {
        val attributes = TextAttributes()
        val lowerLeft = Offset(OffsetMode.PIXELS, 0.0, OffsetMode.PIXELS, 0.0)
        attributes.textOffset = lowerLeft

        // Verify that the object is an equivalent deep copy.
        assertEquals(lowerLeft, attributes.textOffset)
        assertNotSame(lowerLeft, attributes.textOffset)
    }

    @Test
    fun testGetTextSize() {
        val attributes = TextAttributes()
        val size = 2.5f
        attributes.font.size = size
        assertEquals(size, attributes.font.size, 1.0e-15f)
    }

    @Test
    fun testSetTextSize() {
        val attributes = TextAttributes()
        val size = 2.5f
        attributes.font.size = size
        assertEquals(size, attributes.font.size, 0.0f)
    }

    @Test
    fun testGetTypeface() {
        val attributes = TextAttributes()
        val typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
        attributes.font.typeface = typeface
        assertEquals(typeface, attributes.font.typeface)
    }

    @Test
    fun testSetTypeface() {
        val attributes = TextAttributes()
        val typeface = Typeface.defaultFromStyle(Typeface.NORMAL)
        attributes.font.typeface = typeface
        assertEquals(typeface, attributes.font.typeface)
    }

    @Test
    fun testGetOutlineWidth() {
        val attributes = TextAttributes()
        val width = 0.0f
        attributes.outlineWidth = width
        assertEquals(width, attributes.outlineWidth, 1.0e-15f)
    }

    @Test
    fun testSetOutlineWidth() {
        val attributes = TextAttributes()
        val width = 0.0f
        attributes.outlineWidth = width
        assertEquals(width, attributes.outlineWidth, 0.0f)
    }
}