package earth.worldwind.geom

import earth.worldwind.globe.Globe
import earth.worldwind.globe.projection.Wgs84Projection
import kotlin.test.*

class FrustumTest {
    private lateinit var globe: Globe

    @BeforeTest
    fun setUp() {
        // Create the globe object used by the test
        globe = Globe(Ellipsoid.WGS84, Wgs84Projection())
    }

    @Test
    fun testConstructor_Default() {
        // Constructs a new unit frustum with each of its planes 1 meter from the center.
        val frustum = Frustum()
        assertNotNull(frustum)
        assertEquals(1.0, frustum.left.normal.magnitude, 0.0, "left")
        assertEquals(1.0, frustum.right.normal.magnitude, 0.0, "right")
        assertEquals(1.0, frustum.bottom.normal.magnitude, 0.0, "bottom")
        assertEquals(1.0, frustum.top.normal.magnitude, 0.0, "top")
        assertEquals(1.0, frustum.near.normal.magnitude, 0.0, "near")
        assertEquals(1.0, frustum.far.normal.magnitude, 0.0, "far")
        assertEquals(Plane(1.0, 0.0, 0.0, 1.0), frustum.left, "left")
        assertEquals(Plane(-1.0, 0.0, 0.0, 1.0), frustum.right, "right")
        assertEquals(Plane(0.0, 1.0, 0.0, 1.0), frustum.bottom, "bottom")
        assertEquals(Plane(0.0, -1.0, 0.0, 1.0), frustum.top, "top")
        assertEquals(Plane(0.0, 0.0, -1.0, 1.0), frustum.near, "near")
        assertEquals(Plane(0.0, 0.0, 1.0, 1.0), frustum.far, "far")
    }

    @Test
    fun testConstructor() {
        val left = Plane(0.0, 1.0, 0.0, 2.0)
        val right = Plane(0.0, -1.0, 0.0, 2.0)
        val bottom = Plane(0.0, 0.0, 1.0, 2.0)
        val top = Plane(0.0, 0.0, -1.0, 2.0)
        val near = Plane(1.0, 0.0, 0.0, 0.0)
        val far = Plane(-1.0, 0.0, 0.0, 1.5)
        val viewport = Viewport(1, 2, 3, 4)
        val frustum = Frustum(left, right, bottom, top, near, far, viewport)
        assertNotNull(frustum)
        assertEquals(left, frustum.left, "left")
        assertEquals(right, frustum.right, "right")
        assertEquals(bottom, frustum.bottom, "bottom")
        assertEquals(top, frustum.top, "top")
        assertEquals(near, frustum.near, "near")
        assertEquals(far, frustum.far, "far")
        assertEquals(viewport, frustum.viewport, "viewport")
    }

    @Test
    fun testSetToUnitFrustum() {
        val left = Plane(0.0, 1.0, 0.0, 2.0)
        val right = Plane(0.0, -1.0, 0.0, 2.0)
        val bottom = Plane(0.0, 0.0, 1.0, 2.0)
        val top = Plane(0.0, 0.0, -1.0, 2.0)
        val near = Plane(1.0, 0.0, 0.0, 0.0)
        val far = Plane(-1.0, 0.0, 0.0, 1.5)
        val viewport = Viewport(1, 2, 3, 4)
        val frustum = Frustum(left, right, bottom, top, near, far, viewport)
        frustum.setToUnitFrustum()
        assertEquals(Plane(1.0, 0.0, 0.0, 1.0), frustum.left, "left")
        assertEquals(Plane(-1.0, 0.0, 0.0, 1.0), frustum.right, "right")
        assertEquals(Plane(0.0, 1.0, 0.0, 1.0), frustum.bottom, "bottom")
        assertEquals(Plane(0.0, -1.0, 0.0, 1.0), frustum.top, "top")
        assertEquals(Plane(0.0, 0.0, -1.0, 1.0), frustum.near, "near")
        assertEquals(Plane(0.0, 0.0, 1.0, 1.0), frustum.far, "far")
        assertEquals(Viewport(0, 0, 1, 1), frustum.viewport, "viewport")
    }

    @Test
    fun testContainsPoint() {
        // Simple test using a unit frustum
        val frustum = Frustum()
        assertTrue(frustum.containsPoint(Vec3(0.0, 0.0, 0.0)), "origin")
        assertTrue(frustum.containsPoint(Vec3(0.0, 0.0, -0.999999)), "inside near")
        assertTrue(frustum.containsPoint(Vec3(0.0, 0.0, 0.999999)), "inside far")
        assertTrue(frustum.containsPoint(Vec3(0.9999999, 0.0, 0.0)), "inside left")
        assertTrue(frustum.containsPoint(Vec3(-0.9999999, 0.0, 0.0)), "inside right")
        assertTrue(frustum.containsPoint(Vec3(0.0, -0.9999999, 0.0)), "inside bottom")
        assertTrue(frustum.containsPoint(Vec3(0.0, 0.9999999, 0.0)), "inside top")
        assertFalse(frustum.containsPoint(Vec3(1.0000001, 0.0, 0.0)), "outside left")
        assertFalse(frustum.containsPoint(Vec3(-1.0000001, 0.0, 0.0)), "outside right")
        assertFalse(frustum.containsPoint(Vec3(0.0, -1.0000001, 0.0)), "outside bottom")
        assertFalse(frustum.containsPoint(Vec3(0.0, 1.0000001, 0.0)), "outside top")
        assertFalse(frustum.containsPoint(Vec3(0.0, 0.0, -1.0000001)), "outside near")
        assertFalse(frustum.containsPoint(Vec3(0.0, 0.0, 1.0000001)), "outside far")
        assertFalse(frustum.containsPoint(Vec3(1.0, 0.0, 0.0)), "on left side")
        assertFalse(frustum.containsPoint(Vec3(0.0, -1.0, 0.0)), "on bottom side")
        assertFalse(frustum.containsPoint(Vec3(0.0, 1.0, 0.0)), "on top side")
        assertFalse(frustum.containsPoint(Vec3(-1.0, 0.0, 0.0)), "on right side")
        assertFalse(frustum.containsPoint(Vec3(0.0, 0.0, -1.0)), "on near")
        assertFalse(frustum.containsPoint(Vec3(0.0, 0.0, 1.0)), "on far")
    }

    @Test
    fun testIntersectsSegment() {
        // Perform simple tests with a unit frustum using segments with an endpoint at the origin
        val frustum = Frustum()
        val origin = Vec3(0.0, 0.0, 0.0)
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, 0.0, 0.0)), "origin")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, 0.0, -0.999999)), "inside near")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, 0.0, 0.999999)), "inside far")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.9999999, 0.0, 0.0)), "inside left")
        assertTrue(frustum.intersectsSegment(origin, Vec3(-0.9999999, 0.0, 0.0)), "inside right")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, -0.9999999, 0.0)), "inside bottom")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, 0.9999999, 0.0)), "inside top")
        assertTrue(frustum.intersectsSegment(origin, Vec3(1.0000001, 0.0, 0.0)), "intersect left")
        assertTrue(frustum.intersectsSegment(origin, Vec3(-1.0000001, 0.0, 0.0)), "intersect right")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, -1.0000001, 0.0)), "intersect bottom")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, 1.0000001, 0.0)), "intersect top")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, 0.0, -1.0000001)), "intersect near")
        assertTrue(frustum.intersectsSegment(origin, Vec3(0.0, 0.0, 1.0000001)), "intersect far")
        assertTrue(frustum.intersectsSegment(Vec3(1.0, 0.0, 0.0), Vec3(1.0000001, 0.0, 0.0)), "touch left")
        assertTrue(frustum.intersectsSegment(Vec3(-1.0, 0.0, 0.0), Vec3(-1.0000001, 0.0, 0.0)), "touch right")
        assertTrue(frustum.intersectsSegment(Vec3(0.0, -1.0, 0.0), Vec3(0.0, -1.0000001, 0.0)), "touch bottom")
        assertTrue(frustum.intersectsSegment(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 1.0000001, 0.0)), "touch top")
        assertTrue(frustum.intersectsSegment(Vec3(0.0, 0.0, -1.0), Vec3(0.0, 0.0, -1.0000001)), "touch near")
        assertTrue(frustum.intersectsSegment(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, 1.0000001)), "touch far")
        assertFalse(frustum.intersectsSegment(Vec3(2.0, 0.0, 0.0), Vec3(1.0000001, 0.0, 0.0)), "outside left")
        assertFalse(frustum.intersectsSegment(Vec3(-2.0, 0.0, 0.0), Vec3(-1.0000001, 0.0, 0.0)), "outside right")
        assertFalse(frustum.intersectsSegment(Vec3(0.0, -2.0, 0.0), Vec3(0.0, -1.0000001, 0.0)), "outside bottom")
        assertFalse(frustum.intersectsSegment(Vec3(0.0, 2.0, 0.0), Vec3(0.0, 1.0000001, 0.0)), "outside top")
        assertFalse(frustum.intersectsSegment(Vec3(0.0, 0.0, -2.0), Vec3(0.0, 0.0, -1.0000001)), "outside near")
        assertFalse(frustum.intersectsSegment(Vec3(0.0, 0.0, 2.0), Vec3(0.0, 0.0, 1.0000001)), "outside far")
    }

// TODO Uncomment following tests after extracting WorldWindow to common source and mock Camera

//    @Test
//    fun testSetToModelviewProjection() {
//        // The expected test values were obtained via SystemOut on Frustum object
//        // at a time in the development cycle when the setToModelviewProjection
//        // was known to be working correctly (via observed runtime behavior).
//        // This unit test simply tests for changes in the behavior since that time.
//
//        // Create a Frustum similar to the way the WorldWindow does it.
//
//        // Setup a Camera, looking near Oxnard Airport.
//        val lookAt = LookAt().setDegrees(34.15, -119.15, 0.0, AltitudeMode.ABSOLUTE, 2e4 /*range*/, 0.0, 45.0, 0.0)
//        wwd.cameraFromLookAt(lookAt)
//
//        // Compute a perspective projection matrix given the viewport, field of view, and clip distances.
//        val viewport = Viewport(0, 0, 100, 100) // screen coordinates
//        val nearDistance = wwd.camera.position.altitude * 0.75
//        val farDistance = globe.horizonDistance(wwd.camera.position.altitude) + globe.horizonDistance(160000.0)
//        val projection = Matrix4()
//        projection.setToPerspectiveProjection(
//            viewport.width, viewport.height, Angle.fromDegrees(45.0) /*fovy*/, nearDistance, farDistance
//        )
//
//        // Compute a Cartesian viewing matrix using this Camera's properties as a Camera.
//        val modelview = Matrix4()
//        wwd.cameraToViewingTransform(modelview)
//
//        // Compute the Frustum
//        val frustum = Frustum()
//        frustum.setToModelviewProjection(projection, modelview, viewport)
//
//        // Evaluate the results with known values captured on 08/04/2022
//        val bottom = Plane(0.17635740224291602, 0.9793994030381801, 0.0983609475482354, -2412232.4534454597)
//        val left = Plane(-0.1217786415196101, 0.07203573632653151, 0.9899398038070457, 1737116.8972520994)
//        val right = Plane(0.778260558915453, 0.07203573632653185, -0.6237959242640985, 1737116.8972520994)
//        val top = Plane(0.4801245151529268, -0.8353279303851167, 0.2677829319947119, 5886466.247949658)
//        val near = Plane(0.857734960380441, 0.18823845046369225, 0.4783900328269721, 4528686.830908617)
//        val far = Plane(-0.857734960380441, -0.18823845046369225, -0.4783900328269721, -2676528.6881595696)
//        // Android, JVM and JS floating point operation rules return different results with 1e-11 tolerance
//        assertEquals(left.normal.x, frustum.left.normal.x, 1e-11, "left.x")
//        assertEquals(left.normal.y, frustum.left.normal.y, 1e-11, "left.y")
//        assertEquals(left.normal.z, frustum.left.normal.z, 1e-11, "left.z")
//        assertEquals(left.distance, frustum.left.distance, 1e-4, "left.distance")
//        assertEquals(right.normal.x, frustum.right.normal.x, 1e-11, "right.x")
//        assertEquals(right.normal.y, frustum.right.normal.y, 1e-11, "right.y")
//        assertEquals(right.normal.z, frustum.right.normal.z, 1e-11, "right.z")
//        assertEquals(right.distance, frustum.right.distance, 1e-4, "right.distance")
//        assertEquals(bottom.normal.x, frustum.bottom.normal.x, 1e-11, "bottom.x")
//        assertEquals(bottom.normal.y, frustum.bottom.normal.y, 1e-11, "bottom.y")
//        assertEquals(bottom.normal.z, frustum.bottom.normal.z, 1e-11, "bottom.z")
//        assertEquals(bottom.distance, frustum.bottom.distance, 1e-4, "bottom.distance")
//        assertEquals(top.normal.x, frustum.top.normal.x, 1e-11, "top.x")
//        assertEquals(top.normal.y, frustum.top.normal.y, 1e-11, "top.y")
//        assertEquals(top.normal.z, frustum.top.normal.z, 1e-11, "top.z")
//        assertEquals(top.distance, frustum.top.distance, 1e-4, "top.distance")
//        assertEquals(near.normal.x, frustum.near.normal.x, 1e-11, "near.x")
//        assertEquals(near.normal.y, frustum.near.normal.y, 1e-11, "near.y")
//        assertEquals(near.normal.z, frustum.near.normal.z, 1e-11, "near.z")
//        assertEquals(near.distance, frustum.near.distance, 1e-4, "near.distance")
//        assertEquals(far.normal.x, frustum.far.normal.x, 1e-11, "far.x")
//        assertEquals(far.normal.y, frustum.far.normal.y, 1e-11, "far.y")
//        assertEquals(far.normal.z, frustum.far.normal.z, 1e-11, "far.z")
//        assertEquals(far.distance, frustum.far.distance, 1e-4, "far.distance")
//        assertEquals(viewport, frustum.viewport, "viewport")
//    }
//
//    @Test
//    fun testSetToModelviewProjection_SubViewport() {
//        // The expected test values were obtained via SystemOut on Frustum object
//        // at a time in the development cycle when the setToModelviewProjection
//        // was known to be working correctly (via observed runtime behavior).
//        // This unit test simply tests for changes in the behavior since that time.
//
//        // Create a Frustum similar to the way the WorldWindow does it when picking
//
//        // Setup a Camera, looking near Oxnard Airport.
//        val lookAt = LookAt().setDegrees(34.15, -119.15, 0.0, AltitudeMode.ABSOLUTE, 2e4 /*range*/, 0.0, 45.0, 0.0)
//        wwd.cameraFromLookAt(lookAt)
//
//        // Compute a perspective projection matrix given the viewport, field of view, and clip distances.
//        val viewport = Viewport(0, 0, 100, 100) // screen coordinates
//        val pickViewport = Viewport(49, 49, 3, 3) // 3x3 viewport centered on a pick point
//        val nearDistance = wwd.camera.position.altitude * 0.75
//        val farDistance = globe.horizonDistance(wwd.camera.position.altitude) + globe.horizonDistance(160000.0)
//        val projection = Matrix4()
//        projection.setToPerspectiveProjection(
//            viewport.width, viewport.height, Angle.fromDegrees(45.0) /*fovy*/, nearDistance, farDistance
//        )
//
//        // Compute a Cartesian viewing matrix using this Camera's properties as a Camera.
//        val modelview = Matrix4()
//        wwd.cameraToViewingTransform(globe, 1.0, modelview)
//
//        // Compute the Frustum
//        val frustum = Frustum()
//        frustum.setToModelviewProjection(projection, modelview, viewport, pickViewport)
//
//        // Evaluate the results with known values captured on 08/04/2022
//        val bottom = Plane(-0.1572864706635831, 0.9836490211411792, -0.08772439429368303, -4453465.721709793)
//        val left = Plane(-0.4799755263103659, 0.001559364875315691, 0.8772804925018411, 37603.54528185588)
//        val right = Plane(0.501240328720066, 0.0031184087676195548, -0.8653024953109508, 75199.350196271)
//        val top = Plane(0.17858448447919562, -0.9788701700756618, 0.09960307243928168, 4565806.392885643)
//        val near = Plane(0.8577349603811374, 0.18823845046385906, 0.47839003282565795, 4528686.830907854)
//        val far = Plane(-0.8577349603804454, -0.18823845046383064, -0.47839003282690995, -2676528.6881589056)
//        // Android, JVM and JS floating point operation rules return different results with 1e-11 tolerance
//        assertEquals(left.normal.x, frustum.left.normal.x, 1e-11, "left.x")
//        assertEquals(left.normal.y, frustum.left.normal.y, 1e-11, "left.y")
//        assertEquals(left.normal.z, frustum.left.normal.z, 1e-11, "left.z")
//        assertEquals(left.distance, frustum.left.distance, 1e-4, "left.distance")
//        assertEquals(right.normal.x, frustum.right.normal.x, 1e-11, "right.x")
//        assertEquals(right.normal.y, frustum.right.normal.y, 1e-11, "right.y")
//        assertEquals(right.normal.z, frustum.right.normal.z, 1e-11, "right.z")
//        assertEquals(right.distance, frustum.right.distance, 1e-4, "right.distance")
//        assertEquals(bottom.normal.x, frustum.bottom.normal.x, 1e-11, "bottom.x")
//        assertEquals(bottom.normal.y, frustum.bottom.normal.y, 1e-11, "bottom.y")
//        assertEquals(bottom.normal.z, frustum.bottom.normal.z, 1e-11, "bottom.z")
//        assertEquals(bottom.distance, frustum.bottom.distance, 1e-4, "bottom.distance")
//        assertEquals(top.normal.x, frustum.top.normal.x, 1e-11, "top.x")
//        assertEquals(top.normal.y, frustum.top.normal.y, 1e-11, "top.y")
//        assertEquals(top.normal.z, frustum.top.normal.z, 1e-11, "top.z")
//        assertEquals(top.distance, frustum.top.distance, 1e-4, "top.distance")
//        assertEquals(near.normal.x, frustum.near.normal.x, 1e-11, "near.x")
//        assertEquals(near.normal.y, frustum.near.normal.y, 1e-11, "near.y")
//        assertEquals(near.normal.z, frustum.near.normal.z, 1e-11, "near.z")
//        assertEquals(near.distance, frustum.near.distance, 1e-4, "near.distance")
//        assertEquals(far.normal.x, frustum.far.normal.x, 1e-11, "far.x")
//        assertEquals(far.normal.y, frustum.far.normal.y, 1e-11, "far.y")
//        assertEquals(far.normal.z, frustum.far.normal.z, 1e-11, "far.z")
//        assertEquals(far.distance, frustum.far.distance, 1e-4, "far.distance")
//        assertEquals(pickViewport, frustum.viewport, "viewport")
//    }

    @Test
    fun testIntersectsViewport() {
        val plane = Plane(0.0, 0.0, 0.0, 0.0)
        val viewport1 = Viewport(1, 2, 3, 4)
        val viewport2 = Viewport(2, 3, 4, 5)
        val frustum1 = Frustum(plane, plane, plane, plane, plane, plane, viewport1)
        assertTrue(frustum1.intersectsViewport(viewport2))
    }
}