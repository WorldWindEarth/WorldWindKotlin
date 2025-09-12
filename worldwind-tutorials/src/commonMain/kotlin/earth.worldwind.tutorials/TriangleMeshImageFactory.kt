package earth.worldwind.tutorials

import earth.worldwind.render.image.ImageSource

expect class TriangleMeshImageFactory(
    size: Int = 64, innerRadius: Float = 5f, outerRadius: Float = 20f
) : ImageSource.ImageFactory