package earth.worldwind.shape

import earth.worldwind.render.Color
import earth.worldwind.render.image.ImageSource
import kotlin.test.*

class ShapeAttributesTest {
    @Test
    fun testConstructor_Default() {
        val shapeAttributes = ShapeAttributes()
        assertNotNull(shapeAttributes)
        // Assert default values are as expected.
        assertTrue(shapeAttributes.isDrawInterior, "drawInterior should be true")
        assertTrue(shapeAttributes.isDrawOutline, "drawOutline should be true")
        assertFalse(shapeAttributes.isDrawVerticals, "drawVerticals should be false")
        assertTrue(shapeAttributes.isDepthTest, "depthTest should be true")
        assertFalse(shapeAttributes.isLightingEnabled, "isLightingEnabled should be false")
        assertEquals(Color(1f, 1f, 1f, 1f), shapeAttributes.interiorColor, "interiorColor should be white")
        assertEquals(Color(1f, 0f, 0f, 1f), shapeAttributes.outlineColor, "outlineColor should be red")
        assertEquals(1.0f, shapeAttributes.outlineWidth, 0.0f, "outlineWidth should be 1.0")
        assertNull(shapeAttributes.interiorImageSource, "interiorImageSource should be null")
        assertNull(shapeAttributes.outlineImageSource, "outlineImageSource should be null")
    }

    @Test
    fun testConstructor_Copy() {
        val attributes = ShapeAttributes()
        attributes.interiorImageSource = ImageSource.fromUnrecognized(Any())
        attributes.outlineImageSource = ImageSource.fromUnrecognized(Any())
        val copy = ShapeAttributes(attributes)
        assertNotNull(copy)
        assertEquals(attributes, copy)
        // Ensure we made a deep copy of the colors
        assertNotSame(copy.interiorColor, attributes.interiorColor)
        assertNotSame(copy.outlineColor, attributes.outlineColor)
        // Ensure we copied the image sources by reference
        assertSame(copy.interiorImageSource, attributes.interiorImageSource)
        assertSame(copy.outlineImageSource, attributes.outlineImageSource)
    }

    @Test
    fun testSet() {
        val attributes = ShapeAttributes()
        // create another attribute bundle with differing values
        val other = ShapeAttributes()
        other.isDrawInterior = false
        other.isDrawOutline = false
        other.isDrawVerticals = true
        other.isDepthTest = false
        other.isLightingEnabled = true
        other.interiorColor = Color(0f, 0f, 0f, 0f)
        other.outlineColor = Color(0f, 1f, 1f, 0f)
        other.outlineWidth = 0.0f
        other.interiorImageSource = ImageSource.fromUnrecognized(Any())
        other.outlineImageSource = ImageSource.fromUnrecognized(Any())
        attributes.copy(other)
        assertEquals(attributes, other)
    }
    
    @Test
    fun testEquals() {
        val attributes = ShapeAttributes()
        val same = ShapeAttributes()
        assertEquals(same.isDrawInterior, attributes.isDrawInterior)
        assertEquals(same.isDrawOutline, attributes.isDrawOutline)
        assertEquals(same.isDrawVerticals, attributes.isDrawVerticals)
        assertEquals(same.isDepthTest, attributes.isDepthTest)
        assertEquals(same.isLightingEnabled, attributes.isLightingEnabled)
        assertEquals(same.interiorColor, attributes.interiorColor)
        assertEquals(same.outlineColor, attributes.outlineColor)
        assertEquals(same.outlineWidth, attributes.outlineWidth, 0.0f)
        assertEquals(same.interiorImageSource, attributes.interiorImageSource)
        assertEquals(same.outlineImageSource, attributes.outlineImageSource)
        assertEquals(attributes, attributes)
        assertEquals(attributes, same)
        assertEquals(same, attributes)
    }

    @Test
    fun testInequality() {
        val typical = ShapeAttributes()
        val different = ShapeAttributes()
        different.isDrawInterior = false
        different.isDrawOutline = false
        different.isDrawVerticals = true
        different.isDepthTest = false
        different.isLightingEnabled = true
        different.interiorColor = Color(0f, 0f, 0f, 0f)
        different.outlineColor = Color(0f, 1f, 1f, 0f)
        different.outlineWidth = 0.0f
        different.interiorImageSource = ImageSource.fromUnrecognized(Any())
        different.outlineImageSource = ImageSource.fromUnrecognized(Any())
        assertNotEquals(different.isDrawInterior, typical.isDrawInterior)
        assertNotEquals(different.isDrawOutline, typical.isDrawOutline)
        assertNotEquals(different.isDrawVerticals, typical.isDrawVerticals)
        assertNotEquals(different.isDepthTest, typical.isDepthTest)
        assertNotEquals(different.isLightingEnabled, typical.isLightingEnabled)
        assertNotEquals(different.interiorColor, typical.interiorColor)
        assertNotEquals(different.outlineColor, typical.outlineColor)
        assertNotEquals(different.outlineWidth, typical.outlineWidth, 0.0f)
        assertNotEquals(different.interiorImageSource, typical.interiorImageSource)
        assertNotEquals(different.outlineImageSource, typical.outlineImageSource)
        assertNotEquals(different, typical)
        assertNotEquals(typical, different)
        assertNotNull(typical)
    }

    @Test
    fun testHashCode() {
        // Three differing sets of attributes
        val a = ShapeAttributes()
        val b = ShapeAttributes()
        val c = ShapeAttributes()
        b.isDrawInterior = false
        c.isDrawOutline = false
        val aHash = a.hashCode()
        val bHash = b.hashCode()
        val cHash = c.hashCode()
        assertNotEquals(bHash, aHash, "a hash vs b hash")
        assertNotEquals(cHash, bHash, "b hash vs c hash")
        assertNotEquals(aHash, cHash, "c hash vs a hash")
    }

    @Test
    fun testGetInteriorColor() {
        val attributes = ShapeAttributes()
        val black = Color(0f, 0f, 0f, 1f)
        attributes.interiorColor = black
        assertEquals(black, attributes.interiorColor)
    }

    @Test
    fun testSetInteriorColor() {
        val attributes = ShapeAttributes()
        val black = Color(0f, 0f, 0f, 1f)
        attributes.interiorColor = black

        // Verify that the object is an equivalent deep copy.
        assertEquals(black, attributes.interiorColor)
        assertNotSame(black, attributes.interiorColor)
    }

    @Test
    fun testGetOutlineColor() {
        val attributes = ShapeAttributes()
        val black = Color(0f, 0f, 0f, 1f)
        attributes.outlineColor = black
        assertEquals(black, attributes.outlineColor)
    }

    @Test
    fun testSetOutlineColor() {
        val attributes = ShapeAttributes()
        val black = Color(0f, 0f, 0f, 1f)
        attributes.outlineColor = black

        // Verify that the object is an equivalent deep copy.
        assertEquals(black, attributes.outlineColor)
        assertNotSame(black, attributes.outlineColor)
    }

    @Test
    fun testGetOutlineWidth() {
        val attributes = ShapeAttributes()
        val width = 2.5f
        attributes.outlineWidth = width
        assertEquals(width, attributes.outlineWidth, 1e-15f)
    }

    @Test
    fun testSetOutlineWidth() {
        val attributes = ShapeAttributes()
        val width = 2.5f
        attributes.outlineWidth = width
        assertEquals(width, attributes.outlineWidth, 1e-15f)
    }

    @Test
    fun testGetInteriorImageSource() {
        val attributes = ShapeAttributes()
        val imageSource = ImageSource.fromUnrecognized(Any())
        attributes.interiorImageSource = imageSource
        assertEquals(imageSource, attributes.interiorImageSource)
    }

    @Test
    fun testSetInteriorImageSource() {
        val attributes = ShapeAttributes()
        val imageSource = ImageSource.fromUnrecognized(Any())
        attributes.interiorImageSource = imageSource
        assertEquals(imageSource, attributes.interiorImageSource)
    }

    @Test
    fun testGetOutlineImageSource() {
        val attributes = ShapeAttributes()
        val imageSource = ImageSource.fromUnrecognized(Any())
        attributes.outlineImageSource = imageSource
        assertEquals(imageSource, attributes.outlineImageSource)
    }

    @Test
    fun testSetOutlineImageSource() {
        val attributes = ShapeAttributes()
        val imageSource = ImageSource.fromUnrecognized(Any())
        attributes.outlineImageSource = imageSource
        assertEquals(imageSource, attributes.outlineImageSource)
    }
}