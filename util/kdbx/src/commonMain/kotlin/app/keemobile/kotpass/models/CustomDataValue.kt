package app.keemobile.kotpass.models

import kotlin.time.Instant
import kotlin.time.Clock

/**
 * Arbitrary string data holder for database/group/entry metadata.
 */
data class CustomDataValue(
    val value: String,
    val lastModified: Instant? = null
)
