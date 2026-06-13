package earth.worldwind.tutorials

import earth.worldwind.layer.ogc3d.content.spz.SpzInflater
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.js.js

/** Browser-side [SpzInflater] backed by pako's synchronous `ungzip`. The default web actual
 *  of `installDefaultSpzInflater` is a no-op (CompressionStream is async-only); the tutorial
 *  app wires this in `Main.kt` so SPZ tiles inflate on the render thread without async. */
internal object PakoSpzInflater : SpzInflater {
    override fun inflate(bytes: ByteArray): ByteArray {
        val input = Uint8Array(bytes.size)
        for (i in bytes.indices) input[i] = bytes[i]
        val output = pakoUngzip(input)
        val result = ByteArray(output.length)
        for (i in 0 until output.length) result[i] = output[i]
        return result
    }
}

@Suppress("UNUSED_PARAMETER")
private fun pakoUngzip(input: Uint8Array): Uint8Array = js("require('pako').ungzip(input)")
