@file:JsQualifier("window.armyc2.c2sd.renderer")
@file:Suppress("unused", "FunctionName")

package earth.worldwind.shape.milstd2525.renderer

import earth.worldwind.shape.milstd2525.renderer.utilities.ImageInfo

external object MilStdIconRenderer {
    fun Render(symbolID: String, modifiers: dynamic): ImageInfo?
}