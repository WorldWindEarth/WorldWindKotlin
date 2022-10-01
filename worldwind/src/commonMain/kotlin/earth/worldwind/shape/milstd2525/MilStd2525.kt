package earth.worldwind.shape.milstd2525

expect object MilStd2525 {
    var outlineWidth: Float
        private set
    fun getSimplifiedSymbolID(sidc: String): String
    fun setAffiliation(sidc: String, affiliation: String?): String
    fun setStatus(sidc: String, status: String?): String
    fun setEchelon(sidc: String, echelon: String?): String
    fun setSymbolModifier(
        sidc: String, hq: Boolean, taskForce: Boolean, feintDummy: Boolean, installation: Boolean, mobility: String?
    ): String
    fun setCountryCode(sidc: String, countryCode: String?): String
    fun getLineColor(sidc: String): Int
    fun getFillColor(sidc: String): Int
}