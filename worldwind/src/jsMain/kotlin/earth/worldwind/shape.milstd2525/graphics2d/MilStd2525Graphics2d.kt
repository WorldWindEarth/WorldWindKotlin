@file:JsQualifier("window.armyc2.c2sd.graphics2d")

package earth.worldwind.shape.milstd2525.graphics2d

external class Point2D(x: Number, y: Number) {
    fun getX(): Number
    fun getY(): Number
    fun setLocation(x1: Number, y1: Number)
}

external class Rectangle2D(x: Number, y: Number, width: Number, height: Number)

external interface Stroke

external class BasicStroke : Stroke {
    fun getDashArray(): Array<Float>?
}