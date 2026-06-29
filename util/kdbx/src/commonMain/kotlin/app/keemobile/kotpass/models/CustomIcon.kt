package app.keemobile.kotpass.models

import kotlin.time.Instant

/**
 * Extra icon added by user to the database. Can be used
 * for customizing groups and entries.
 *
 * @property data contains the icon image data as binary blob.
 * @property name optionally given to icon.
 * @property lastModified timestamp.
 */
data class CustomIcon(
    val data: ByteArray,
    val name: String?,
    val lastModified: Instant?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomIcon) return false

        if (!data.contentEquals(other.data)) return false
        if (name != other.name) return false
        if (lastModified != other.lastModified) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + lastModified.hashCode()
        return result
    }
}
