/*
 * Adapted from ebruneton/precomputed_atmospheric_scattering, BSD-3-Clause,
 * Copyright (c) 2017 Eric Bruneton.
 *
 * One-shot drawable: invokes [BrunetonAtmosphereModel.precompute] on the GL thread. Queued
 * by [earth.worldwind.layer.atmosphere.AtmosphereLayer] every frame; the model itself
 * gates "actually run the kernels" behind [BrunetonAtmosphereModel.isPrecomputed], so the
 * common-case cost after the first frame is one pool acquire + one no-op call.
 */
package earth.worldwind.layer.atmosphere.bruneton

import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.Drawable
import earth.worldwind.util.Pool

internal class DrawableBrunetonPrecompute : Drawable {
    var model: BrunetonAtmosphereModel? = null
    private var pool: Pool<DrawableBrunetonPrecompute>? = null

    override fun draw(dc: DrawContext) {
        model?.precompute(dc)
    }

    override fun recycle() {
        model = null
        pool?.release(this)
        pool = null
    }

    companion object {
        val KEY = DrawableBrunetonPrecompute::class

        fun obtain(pool: Pool<DrawableBrunetonPrecompute>): DrawableBrunetonPrecompute {
            val instance = pool.acquire() ?: DrawableBrunetonPrecompute()
            instance.pool = pool
            return instance
        }
    }
}
