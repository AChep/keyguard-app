package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity

import app.keemobile.kotpass.models.Group
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class KeePassFolder(
    val group: Group,
    val name: String,
    val revisionDate: Instant,
    val parentGroupUuid: Uuid? = null,
) {
    val id: String = group.uuid.toString()
}