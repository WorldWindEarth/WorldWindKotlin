package earth.worldwind.shape.milstd2525

expect object MilStd2525 {
    fun isTacticalGraphic(symbolID: String): Boolean
    fun getUnfilledAttributes(symbolID: String): Map<String, String>
}