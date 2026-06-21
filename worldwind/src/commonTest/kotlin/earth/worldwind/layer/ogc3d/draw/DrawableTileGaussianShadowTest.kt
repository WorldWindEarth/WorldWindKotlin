package earth.worldwind.layer.ogc3d.draw

import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.util.BasicPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies the [DrawableTileGaussian] shadow caster contract introduced in Phase 12b. Same
 * pattern as [DrawableTilePointsShadowTest]: the actual `draw` / `drawShadowDepth` paths
 * need a live GL surface, but the per-cascade cull contract — surface non-null bounds when
 * casting is enabled, hide them otherwise — is exercisable directly.
 */
class DrawableTileGaussianShadowTest {

    private val pool = BasicPool<DrawableTileGaussian>()

    @Test fun castsExposesBoundsWhenEnabled() {
        val drawable = DrawableTileGaussian.obtain(pool)
        drawable.shadowMode = ShadowMode.ENABLED
        drawable.setWorldBounds(Vec3(10.0, 20.0, 30.0), 5.0)
        val center = drawable.shadowCasterCenter
        assertNotNull(center)
        assertEquals(10.0, center.x)
        assertEquals(20.0, center.y)
        assertEquals(30.0, center.z)
        assertEquals(5.0, drawable.shadowCasterRadius)
    }

    @Test fun castsHidesBoundsWhenReceiveOnly() {
        val drawable = DrawableTileGaussian.obtain(pool)
        drawable.shadowMode = ShadowMode.RECEIVE_ONLY
        drawable.setWorldBounds(Vec3(10.0, 20.0, 30.0), 5.0)
        assertNull(drawable.shadowCasterCenter)
        assertEquals(0.0, drawable.shadowCasterRadius)
    }

    @Test fun recycleDoesNotResetPrimitiveState() {
        // The engine deliberately does not reset primitives on recycle (perf): the enqueue path
        // sets them fresh each use, so a re-obtained instance still carries the prior bounds.
        val drawable = DrawableTileGaussian.obtain(pool)
        drawable.shadowMode = ShadowMode.ENABLED
        drawable.setWorldBounds(Vec3(10.0, 20.0, 30.0), 5.0)
        drawable.recycle()
        val again = DrawableTileGaussian.obtain(pool)
        val center = assertNotNull(again.shadowCasterCenter)
        assertEquals(10.0, center.x)
        assertEquals(5.0, again.shadowCasterRadius)
    }
}
