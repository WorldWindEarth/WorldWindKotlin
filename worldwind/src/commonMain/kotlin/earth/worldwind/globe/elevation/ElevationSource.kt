package earth.worldwind.globe.elevation

expect class ElevationSource {
    companion object {
        fun fromUrlString(urlString: String): ElevationSource
        fun fromUnrecognized(source: Any): ElevationSource
    }
}