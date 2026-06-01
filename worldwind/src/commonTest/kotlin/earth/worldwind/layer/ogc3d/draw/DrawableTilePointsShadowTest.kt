package earth.worldwind.layer.ogc3d.draw

import earth.worldwind.geom.Vec3
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.util.BasicPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies the [DrawableTilePoints] shadow caster contract introduced in Phase 11. The actual
 * `drawShadowDepth` path needs a real GL surface and can't be unit-tested here; what we *can*
 * test is the per-frame cull contract:
 *
 *  - When the layer's `shadowMode` casts shadows AND the tile has uploaded geometry with a
 *    non-empty world bounding sphere, the drawable surfaces a non-null caster centre + a
 *    positive caster radius so [earth.worldwind.draw.DrawableShadow] iterates this tile in
 *    its per-cascade cull.
 *  - When the layer opts out of casting (e.g. ground-truth comparison toggle, or large
 *    photogrammetry receiver-only setups), the drawable returns null centre + zero radius
 *    and the shadow pass skips it entirely without paying the per-cascade sphere test cost.
 */
class DrawableTilePointsShadowTest {

    private val pool = BasicPool<DrawableTilePoints>()

    @Test fun castsExposesBoundsWhenEnabled() {
        val drawable = DrawableTilePoints.obtain(pool)
        drawable.shadowMode = ShadowMode.ENABLED
        drawable.setWorldBounds(Vec3(100.0, 200.0, 300.0), 50.0)
        val center = drawable.shadowCasterCenter
        assertNotNull(center)
        assertEquals(100.0, center.x)
        assertEquals(200.0, center.y)
        assertEquals(300.0, center.z)
        assertEquals(50.0, drawable.shadowCasterRadius)
    }

    @Test fun castsHidesBoundsWhenCastingDisabled() {
        val drawable = DrawableTilePoints.obtain(pool)
        drawable.shadowMode = ShadowMode.RECEIVE_ONLY
        drawable.setWorldBounds(Vec3(100.0, 200.0, 300.0), 50.0)
        assertNull(drawable.shadowCasterCenter)
        assertEquals(0.0, drawable.shadowCasterRadius)
    }

    @Test fun castsHidesBoundsWhenRadiusZero() {
        val drawable = DrawableTilePoints.obtain(pool)
        drawable.shadowMode = ShadowMode.ENABLED
        // setWorldBounds called with radius 0 (or never called) → tile contributes nothing.
        assertNull(drawable.shadowCasterCenter)
        assertEquals(0.0, drawable.shadowCasterRadius)
    }

    @Test fun recycleClearsBounds() {
        val drawable = DrawableTilePoints.obtain(pool)
        drawable.shadowMode = ShadowMode.ENABLED
        drawable.setWorldBounds(Vec3(100.0, 200.0, 300.0), 50.0)
        drawable.recycle()
        // Recycled instance returns to pool — re-obtaining gives back the same object with cleared state.
        val again = DrawableTilePoints.obtain(pool)
        assertNull(again.shadowCasterCenter)
        assertEquals(0.0, again.shadowCasterRadius)
    }
}
