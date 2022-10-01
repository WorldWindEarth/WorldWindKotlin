@file:Suppress("ClassName", "FunctionName")

package earth.worldwind.shape.milstd2525

external object java {
    object util {
        open class ArrayList<T> {
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
        object graphics2d {
            open class Point2D(x: Number, y: Number) {
                fun getX(): Number
                fun getY(): Number
                fun setLocation(x1: Number, y1: Number)
            }

            open class Rectangle2D(x: Number, y: Number, width: Number, height: Number)

            interface Stroke

            open class BasicStroke : Stroke {
                fun getDashArray(): Array<Float>?
            }
        }

        object renderer {
            object so {
                open class Point {
                    fun getX(): Number
                    fun getY(): Number
                }
            }

            object MilStdIconRenderer {
                fun Render(symbolID: String, modifiers: Map<String, String>?): ImageInfo?
            }
        }

        object JavaRendererServer {
            object RenderMultipoints {
                object clsRenderer {
                    fun renderWithPolylines(
                        mss: MilStdSymbol,
                        converter: IPointConversion,
                        clipArea: graphics2d.Rectangle2D?
                    )
                }
            }
        }
    }
}