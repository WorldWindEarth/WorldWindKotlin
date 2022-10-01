package earth.worldwind.util

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Location
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.*

/**
 * Provides utilities for determining the Sun geographic and celestial location.
 */
object SunPosition {
    class CelestialLocation(val declination: Angle, val rightAscension: Angle)

    /**
     * Computes the geographic location of the sun for a given date
     * @param instant Input instant
     * @return the geographic location
     */
    fun getAsGeographicLocation(instant: Instant) = celestialToGeographic(getAsCelestialLocation(instant), instant)

    /**
     * Computes the celestial location of the sun for a given julianDate
     * @param instant Input instant
     * @return the celestial location
     */
    fun getAsCelestialLocation(instant: Instant): CelestialLocation {
        val julianDate = computeJulianDate(instant)

        //number of days (positive or negative) since Greenwich noon, Terrestrial Time, on 1 January 2000 (J2000.0)
        val numDays = julianDate - 2451545
        val meanLongitude = Angle.normalizeAngle360(280.460 + 0.9856474 * numDays)
        val meanAnomaly = Angle.toRadians(Angle.normalizeAngle360(357.528 + 0.9856003 * numDays))
        val eclipticLongitude = meanLongitude + 1.915 * sin(meanAnomaly) + 0.02 * sin(2 * meanAnomaly)
        val eclipticLongitudeRad = Angle.toRadians(eclipticLongitude)
        val obliquityOfTheEcliptic = Angle.toRadians(23.439 - 0.0000004 * numDays)
        val declination = Angle.fromRadians(asin(sin(obliquityOfTheEcliptic) * sin(eclipticLongitudeRad)))
        var rightAscension = Angle.fromRadians(atan(cos(obliquityOfTheEcliptic) * tan(eclipticLongitudeRad)))
        if (eclipticLongitude >= 90 && eclipticLongitude < 270) rightAscension += Angle.POS180
        return CelestialLocation(declination, rightAscension.normalize360())
    }

    /**
     * Converts from celestial coordinates (declination and right ascension) to geographic coordinates
     * (latitude, longitude) for a given julian date
     * @param celestialLocation Celestial location
     * @param instant Input instant
     * @return the geographic location
     */
    fun celestialToGeographic(celestialLocation: CelestialLocation, instant: Instant): Location {
        val julianDate = computeJulianDate(instant)

        //number of days (positive or negative) since Greenwich noon, Terrestrial Time, on 1 January 2000 (J2000.0)
        val numDays = julianDate - 2451545

        //Greenwich Mean Sidereal Time
        val GMST = Angle.normalizeAngle360(280.46061837 + 360.98564736629 * numDays)

        //Greenwich Hour Angle
        val GHA = Angle.normalizeAngle360(GMST - celestialLocation.rightAscension.degrees)

        val longitude = Angle.fromDegrees(-GHA).normalizeLongitude()

        return Location(celestialLocation.declination, longitude)
    }

    /**
     * Computes the julian date from a javascript date object
     * @param instant Input instant
     * @return the julian date
     */
    fun computeJulianDate(instant: Instant): Double {
        val date = instant.toLocalDateTime(TimeZone.UTC)
        var year = date.year
        var month = date.monthNumber + 1
        val day = date.dayOfMonth
        val hour = date.hour
        val minute = date.minute
        val second = date.second
        val dayFraction = (hour + minute / 60.0 + second / 3600.0) / 24.0
        if (month <= 2) {
            year -= 1
            month += 12
        }
        val a = floor(year / 100.0)
        val b = 2 - a + floor(a / 4.0)
        val JD0h = floor(365.25 * (year + 4716)) + floor(30.6001 * (month + 1)) + day + b - 1524.5
        return JD0h + dayFraction
    }

}