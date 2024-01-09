package earth.worldwind.formats.geotiff

import earth.worldwind.geom.Sector

class GeoTiffMetadata {
    var bitsPerSample: List<Int> = mutableListOf()
    var colorMap: Array<Number>? = null
    var compression: Int = 0
    var extraSamples: Array<Number>? = null
    var imageDescription: String? = null
    var imageLength: Int = 0
    var imageWidth: Int = 0
    var maxSampleValue: Number? = null
    var minSampleValue: Number? = null
    var orientation: Int = 0
    var photometricInterpretation: Int = -1
    var planarConfiguration: Int = -1
    var resolutionUnit: Number? = null
    var rowsPerStrip: Int = 0
    var samplesPerPixel: Int = 0
    var sampleFormat: Int = TiffConstants.SampleFormat.UNSIGNED
    var software: Array<Number>? = null
    var stripByteCounts: List<Int> = mutableListOf()
    var stripOffsets: List<Int> = mutableListOf()
    var tileByteCounts: List<Int> = mutableListOf()
    var tileOffsets: List<Int> = mutableListOf()
    var tileLength: Int = 0
    var tileWidth: Int = 0
    var xResolution: Number? = null
    var yResolution: Number? = null
    var geoAsciiParams: String? = null
    var geoDoubleParams: List<Double> = mutableListOf()
    var geoKeyDirectory: List<Int> =  mutableListOf()
    var modelPixelScale: List<Double> = mutableListOf()
    var modelTiePoint: List<Double> = mutableListOf()
    var modelTransformation: List<Double> = mutableListOf()
    var noData: Number? = null
    var metaData: String? = null
    var bbox: Sector? = null
    var gtModelTypeGeoKey: Number? = null
    var gtRasterTypeGeoKey: Number? = null
    var gtCitationGeoKey: String? = null
    var geographicTypeGeoKey: Number? = null
    var geogCitationGeoKey: String? = null
    var geogAngularUnitsGeoKey: Number? = null
    var geogAngularUnitSizeGeoKey: Number? = null
    var geogSemiMajorAxisGeoKey: Number? = null
    var geogInvFlatteningGeoKey: Number? = null
    var projectedCSType: Number? = null
    var projLinearUnits: Number? = null
}