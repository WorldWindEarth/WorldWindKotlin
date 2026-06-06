package earth.worldwind.render.image

import java.util.zip.ZipFile

/**
 * Marker source for an image stored inside an open [ZipFile] (e.g. a `.kmz` texture), decoded
 * on demand by the platform [ImageDecoder] so the archive is never extracted to disk. Wrapped via
 * [ImageSource.fromUnrecognized]. Equality keys on the archive identity + entry name so the render
 * resource cache shares and evicts these like any other texture; the [zip] must stay open for the
 * lifetime of the consuming scene.
 */
class ZipEntryImageRef(val zip: ZipFile, val entry: String) {
    override fun equals(other: Any?) = other is ZipEntryImageRef && other.zip === zip && other.entry == entry
    override fun hashCode() = 31 * System.identityHashCode(zip) + entry.hashCode()
    override fun toString() = "ZipEntry: ${zip.name}!/$entry"
}
