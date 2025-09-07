package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * Represents an extent in time bounded by begin and end dateTimes.
 *
 * If [begin] or [end] is missing, then that end of the period is unbounded.
 *
 * The dateTime is defined according to XML Schema time (see XML Schema Part 2: Datatypes Second Edition).
 * The value can be expressed as yyyy-mm-ddThh:mm:ss.ssszzzzzz, where T is the separator between the date and the time,
 * and the time zone is either Z (for UTC) or zzzzzz, which represents ±hh:mm in relation to UTC. Additionally,
 * the value can be expressed as a date only.
 */
@Serializable
internal data class TimeSpan(
    override val id: String? = null,

    /**
     * Describes the beginning instant of a time period. If absent, the beginning of the period is unbounded.
     */
    @XmlElement
    val begin: String? = null,

    /**
     * Describes the ending instant of a time period. If absent, the end of the period is unbounded.
     */
    @XmlElement
    val end: String? = null,
) : TimePrimitive()