package earth.worldwind.ogc

import earth.worldwind.ogc.ows.OwsExceptionReport

open class OwsException(val exceptionReport: OwsExceptionReport) : Exception(exceptionReport.toPrettyString())