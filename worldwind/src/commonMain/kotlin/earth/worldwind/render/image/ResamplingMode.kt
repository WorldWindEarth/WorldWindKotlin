package earth.worldwind.render.image

/**
 * Resampling mode indicates the image sampling algorithm used by WorldWind to display images that appear larger or
 * smaller on screen than their native resolution. Accepted values are [BILINEAR] and [NEAREST_NEIGHBOR].
 */
enum class ResamplingMode {
    /**
     * Indicating bilinear image sampling.
     */
    BILINEAR,
    /**
     * Indicating nearest neighbor image sampling.
     */
    NEAREST_NEIGHBOR;
}