@file:JsQualifier("window")
@file:Suppress("ClassName", "FunctionName")

package earth.worldwind.shape.milstd2525

import earth.worldwind.shape.milstd2525.graphics2d.Rectangle2D
import earth.worldwind.shape.milstd2525.renderer.utilities.IPointConversion
import earth.worldwind.shape.milstd2525.renderer.utilities.MilStdSymbol

external object java {
    object util {
        class ArrayList<T> {
            fun add(obj1: T)
            fun get(index: Number): T?
            fun clear()
            fun size(): Int
            fun getArray(): Array<T>
            fun toArray(): Array<T>
        }
    }
}

external object armyc2 {
    object c2sd {
        object JavaRendererServer {
            object RenderMultipoints {
                object clsRenderer {
                    fun renderWithPolylines(mss: MilStdSymbol, converter: IPointConversion, clipArea: Rectangle2D?)
                }
            }
        }
    }
}

external class WeakRef<T>(element: T) {
    fun deref(): T?
}