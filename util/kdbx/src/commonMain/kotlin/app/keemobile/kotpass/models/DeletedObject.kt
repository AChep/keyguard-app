package app.keemobile.kotpass.models

import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class DeletedObject(
    val id: Uuid,
    val deletionTime: Instant
)
