package earth.worldwind.shape

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.render.RenderContext
import kotlin.test.*

class AbstractShapeTest {
    /**
     * A simple concrete implementation of AbstractShape for testing.
     */
    private class AbstractShapeImpl(attributes: ShapeAttributes = ShapeAttributes()) : AbstractShape(attributes) {
        override fun reset() {}
        override fun makeDrawable(rc: RenderContext) {}
    }

    @Test
    fun testConstructor_Default() {
        val shape = AbstractShapeImpl()
        assertNotNull(shape)
        assertNotNull(shape.attributes)
        assertNull(shape.highlightAttributes)
    }

    @Test
    fun testConstructor_WithAttributes() {
        val attributes = ShapeAttributes()
        val shape = AbstractShapeImpl(attributes)
        assertNotNull(shape)
        assertSame(attributes, shape.attributes)
        assertNull(shape.highlightAttributes)
    }

    @Test
    fun testGetAttributes() {
        val attributes = ShapeAttributes()
        val shape = AbstractShapeImpl(attributes)
        val result = shape.attributes
        assertSame(attributes, result)
    }

    @Test
    fun testSetAttributes() {
        val attributes = ShapeAttributes()
        val shape = AbstractShapeImpl()
        shape.attributes = attributes
        assertSame(attributes, shape.attributes)
    }

    @Test
    fun testGetHighlightAttributes() {
        val attributes = ShapeAttributes()
        val shape = AbstractShapeImpl()
        shape.highlightAttributes = attributes
        val result = shape.highlightAttributes
        assertSame(attributes, result)
    }

    @Test
    fun testSetHighlightAttributes() {
        val attributes = ShapeAttributes()
        val shape = AbstractShapeImpl()
        shape.highlightAttributes = attributes
        assertSame(attributes, shape.highlightAttributes)
    }

    @Test
    fun testGetAltitudeMode() {
        val shape = AbstractShapeImpl()
        shape.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        val result = shape.altitudeMode
        assertEquals(AltitudeMode.CLAMP_TO_GROUND, result)
    }

    @Test
    fun testSetAltitudeMode() {
        val shape = AbstractShapeImpl()
        shape.altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        assertEquals(AltitudeMode.CLAMP_TO_GROUND, shape.altitudeMode)
    }

    @Test
    fun testGetPathType() {
        val shape = AbstractShapeImpl()
        shape.pathType = PathType.RHUMB_LINE
        val result = shape.pathType
        assertEquals(PathType.RHUMB_LINE, result)
    }

    @Test
    fun testSetPathType() {
        val shape = AbstractShapeImpl()
        shape.pathType = PathType.RHUMB_LINE
        assertEquals(PathType.RHUMB_LINE, shape.pathType)
    }

    @Test
    fun testGetMaximumIntermediatePoints() {
        val shape = AbstractShapeImpl()
        shape.maximumIntermediatePoints = 123
        assertEquals(123, shape.maximumIntermediatePoints)
    }

    @Test
    fun testSetMaximumIntermediatePoints() {
        val shape = AbstractShapeImpl()
        shape.maximumIntermediatePoints = 123
        assertEquals(123, shape.maximumIntermediatePoints)
    }
}