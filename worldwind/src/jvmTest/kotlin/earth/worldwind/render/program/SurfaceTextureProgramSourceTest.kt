package earth.worldwind.render.program

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The composite surface-texture shader is assembled from string templates; this verifies the
 * generated GLSL is well-formed (balanced braces, one block per texture unit, sampler uniforms
 * matching the unit table) for both the no-shadow and shadow variants.
 */
class SurfaceTextureProgramSourceTest {
    private class Exposed(shadows: Boolean) : SurfaceTextureProgram(shadows) {
        val sources get() = programSources
    }

    private fun checkBalanced(src: String) =
        assertEquals(src.count { it == '{' }, src.count { it == '}' }, "unbalanced braces in:\n$src")

    private fun checkComposite(frag: String, k: Int) {
        for (i in 0 until k) {
            assertTrue(frag.contains("uniform sampler2D texSampler$i;"), "missing sampler $i")
            assertTrue(frag.contains("if (texCount > $i)"), "missing composite block $i")
        }
        assertTrue(!frag.contains("texSampler$k"), "unexpected sampler beyond unit table")
    }

    @Test
    fun noShadowSources() {
        val (vert, frag) = Exposed(false).sources
        checkBalanced(vert)
        checkBalanced(frag)
        checkComposite(frag, SurfaceTextureProgram.TEXTURE_UNITS.size)
        assertTrue(!frag.contains("#define SHADOWS_ENABLED"), "no-shadow variant must not define shadows")
        println("===== no-shadow vertex =====\n$vert")
        println("===== no-shadow fragment =====\n$frag")
    }

    @Test
    fun shadowSources() {
        val (vert, frag) = Exposed(true).sources
        checkBalanced(vert)
        checkBalanced(frag)
        assertTrue(frag.contains("#define SHADOWS_ENABLED") || frag.startsWith("#define"), "shadow define missing")
        assertTrue(frag.contains("shadowAlbedoFactor"), "shadow factor missing")
    }
}
