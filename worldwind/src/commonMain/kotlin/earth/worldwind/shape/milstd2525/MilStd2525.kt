package earth.worldwind.shape.milstd2525

expect object MilStd2525 {
    var modifiersThreshold: Double
    var labelScaleThreshold: Double
    var graphicsLineWidth: Float
        private set
    fun getSimplifiedSymbolID(symbolID: String): String
    fun isTacticalGraphic(symbolID: String): Boolean
    fun setAffiliation(symbolID: String, affiliation: String?): String
    fun setStatus(symbolID: String, status: String?): String
    fun setEchelon(symbolID: String, echelon: String?): String
    fun setMobility(symbolID: String, mobility: String?): String
    fun setHQTFD(symbolID: String, hq: Boolean, taskForce: Boolean, feintDummy: Boolean): String
    fun getLineColor(symbolID: String): Int
    fun getFillColor(symbolID: String): Int
    fun getUnfilledAttributes(symbolID: String): Map<String, String>
}