package earth.worldwind.shape

import earth.worldwind.geom.Angle.Companion.fromDegrees
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.render.Color
import earth.worldwind.util.Logger
import io.mockk.every
import io.mockk.mockkStatic
import kotlin.math.PI
import kotlin.test.*

class EllipseTest {
    @BeforeTest
    fun setup() {
        mockkStatic(Logger::class)
        every { Logger.logMessage(any(), any(), any(), any()) } returns ""
    }

    @Test
    fun testConstructor() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        assertEquals(position, ellipse.center, "constructor position")
        assertEquals(majorRadius, ellipse.majorRadius, 1e-9, "constructor major radius")
        assertEquals(minorRadius, ellipse.minorRadius, 1e-9, "constructor minor radius")
    }

    @Test
    fun testConstructorWithAttriubutes() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val attrs = ShapeAttributes()
        attrs.interiorColor = Color(0f, 1f, 0f, 1f)
        attrs.isDrawOutline = false
        val ellipse = Ellipse(position, majorRadius, minorRadius, attrs)
        assertEquals(position, ellipse.center, "constructor position")
        assertEquals(majorRadius, ellipse.majorRadius, 1e-9, "constructor major radius")
        assertEquals(minorRadius, ellipse.minorRadius, 1e-9, "constructor minor radius")
        assertEquals(attrs, ellipse.attributes, "constructor attributes")
    }

    @Test
    fun testCenterGetter() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        val actualPosition = ellipse.center
        assertEquals(position, actualPosition, "center getter")
    }

    @Test
    fun testCenterSetterNonNull() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val newPosition = fromDegrees(24.0, 68.0, 10.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        ellipse.center = newPosition
        assertEquals(newPosition, ellipse.center, "non-null center setter")
    }

    @Test
    fun testMajorRadiusGetter() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        val actualMajorRadius = ellipse.majorRadius
        assertEquals(majorRadius, actualMajorRadius, 1e-9, "major radius getter")
    }

    @Test
    fun testMajorRadiusSetterValid() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        ellipse.majorRadius = 15000.0
        assertEquals(15000.0, ellipse.majorRadius, 1e-9, "major radius setter valid")
    }

    @Test
    fun testMajorRadiusSetterError() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        try {
            ellipse.majorRadius = -1.0
            fail("invalid setter value for major radius")
        } catch (ex: Exception) {
            assertTrue(ex is IllegalArgumentException, "major radius invalid setter exception type")
        }
    }

    @Test
    fun testMinorRadiusGetter() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        val actualMinorRadius = ellipse.minorRadius
        assertEquals(minorRadius, actualMinorRadius, 1e-9, "minor radius getter")
    }

    @Test
    fun testMinorRadiusSetterValid() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        ellipse.minorRadius = 15000.0
        assertEquals(15000.0, ellipse.minorRadius, 1e-9, "minor radius setter valid")
    }

    @Test
    fun testMinorRadiusSetterError() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        try {
            ellipse.minorRadius = -1.0
            fail("invalid setter value for minor radius")
        } catch (ex: Exception) {
            assertTrue(ex is IllegalArgumentException, "minor radius invalid setter exception type")
        }
    }

    @Test
    fun testHeadingGetter() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        val heading = fromDegrees(64.3)
        ellipse.heading = heading
        val actualHeading = ellipse.heading
        assertEquals(heading.degrees, actualHeading.degrees, 1e-9, "heading getter")
    }

    @Test
    fun testHeadingSetter() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        val heading = fromDegrees(64.2)
        ellipse.heading = heading
        assertEquals(heading.degrees, ellipse.heading.degrees, 1e-9, "heading setter")
    }

    @Test
    fun testMaxIntervalGetter() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        val actualMaxNumberIntervals = ellipse.maximumIntervals
        assertEquals(ellipse.maximumIntervals, actualMaxNumberIntervals, "default number of intervals")
    }

    @Test
    fun testMaxIntervalSetterValid() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        val maxNumberIntervals = 146
        ellipse.maximumIntervals = maxNumberIntervals
        assertEquals(maxNumberIntervals, ellipse.maximumIntervals, "max interval setter even")
    }

    @Test
    fun testMaxIntervalSetterInvalid() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 1000.0
        val minorRadius = 500.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        try {
            ellipse.maximumIntervals = -8
            fail("invalid max interval setting")
        } catch (e: Exception) {
            assertTrue(e is IllegalArgumentException, "maximum interval exception type")
        }
    }

    @Test
    fun testCircumference_Circle() {
        val position = fromDegrees(12.0, 34.0, 56.0)
        val majorRadius = 50.0
        val minorRadius = 50.0
        val ellipse = Ellipse(position, majorRadius, minorRadius)
        val expectedCircumference = 2 * PI * majorRadius
        val actualCircumference = ellipse.computeCircumference()
        assertEquals(expectedCircumference, actualCircumference, 1e-9, "circle circumference")
    }
}