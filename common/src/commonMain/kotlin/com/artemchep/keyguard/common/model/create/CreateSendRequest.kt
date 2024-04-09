package com.artemchep.keyguard.common.model.create

import arrow.optics.optics
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.platform.LeUri
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@optics
data class CreateSendRequest(
    val ownership: Ownership? = null,
    val title: String? = null,
    val note: String? = null,
    val password: String? = null,
    val maxAccessCount: String? = null,
    val deletionDateAsDuration: Duration? = null,
    val deletionDate: LocalDateTime? = null,
    val expirationDateAsDuration: Duration? = null,
    val expirationDate: LocalDateTime? = null,
    val disabled: Boolean = false,
    val hideEmail: Boolean = false,
    // types
    val type: DSend.Type? = null,
    val text: Text = Text(),
    val file: File = File(),
    // other
    val now: Instant,
) {
    companion object;

    @optics
    data class Ownership(
        val accountId: String?,
    ) {
        companion object;
    }

    @optics
    data class File(
        val text: String? = null,
    ) {
        companion object;
    }

    @optics
    data class Text(
        val text: String? = null,
        val hidden: Boolean? = null,
    ) {
        companion object;
    }
}
