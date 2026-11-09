package com.music.sonariq.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test

/**
 * [QuantisedEffectCache]: nearby radii share one effect, distant radii
 * get their own, effects are created at the quantised radius, and the
 * cache stays capped. Exercised with strings so the keying is tested
 * without a graphics backend — the panel's instance creates BlurEffects
 * through the same code.
 */
class LyricBlurEffectsTest {

    @Test
    fun `radii in the same half-pixel bucket share one effect`() {
        val cache = QuantisedEffectCache { radius: Float -> "effect@$radius" }
        val first = cache.forRadius(10.1f)
        val second = cache.forRadius(10.24f)
        assertSame(first, second)
        assertEquals(1, cache.size)
    }

    @Test
    fun `effects are created at the quantised radius`() {
        val cache = QuantisedEffectCache { radius: Float -> "effect@$radius" }
        assertEquals("effect@10.0", cache.forRadius(10.1f))
        assertEquals("effect@10.5", cache.forRadius(10.4f))
    }

    @Test
    fun `distant radii get distinct effects`() {
        val cache = QuantisedEffectCache { radius: Float -> "effect@$radius" }
        val near = cache.forRadius(2.5f)
        val far = cache.forRadius(11.9f)
        assertNotSame(near, far)
        assertEquals(2, cache.size)
    }

    @Test
    fun `tiny radii never bucket below the first step`() {
        assertEquals(1, QuantisedEffectCache.keyFor(0.01f))
        assertEquals(1, QuantisedEffectCache.keyFor(0.24f))
        assertEquals(0.5f, QuantisedEffectCache.radiusForKey(1), 0f)
    }

    @Test
    fun `the cache evicts least recently used radii past its cap`() {
        val cache = QuantisedEffectCache(maxEntries = 4) { radius: Float -> "effect@$radius" }
        cache.forRadius(1f)
        cache.forRadius(2f)
        cache.forRadius(3f)
        cache.forRadius(4f)
        cache.forRadius(5f)
        assertEquals(4, cache.size)
        // The eldest (1px bucket) was evicted: asking again recreates it.
        val recreated = cache.forRadius(1f)
        assertEquals("effect@1.0", recreated)
        assertEquals(4, cache.size)
    }
}
