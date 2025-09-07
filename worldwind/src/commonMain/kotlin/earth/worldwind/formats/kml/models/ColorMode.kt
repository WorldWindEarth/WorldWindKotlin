package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable

/**
 * A value of random applies a random linear scale to the base <color> as follows.
 * To achieve a truly random selection of colors, specify a base <color> of white (ffffffff).
 * If you specify a single color component (for example, a value of ff0000ff for red), random color values for
 * that one component (red) will be selected. In this case, the values would range from 00 (black) to ff (full red).
 * If you specify values for two or for all three color components, a random linear scale is applied to each color
 * component, with results ranging from black to the maximum values specified for each component.
 * The opacity of a color comes from the alpha component of <color> and is never randomized.
 */
@Serializable
enum class ColorMode { normal, random }