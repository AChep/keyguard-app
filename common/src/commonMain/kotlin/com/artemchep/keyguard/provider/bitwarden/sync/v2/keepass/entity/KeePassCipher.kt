package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import kotlin.time.Instant

data class KeePassCipher(
    val group: Group,
    val cipher: Entry,
    val revisionDate: Instant,
    val deletedDate: Instant? = null,
) {
    val id: String = cipher.uuid.toString()
}