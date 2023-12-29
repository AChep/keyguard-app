package com.artemchep.keyguard.room

import androidx.room.Entity
import androidx.room.ForeignKey
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization

@Entity(
    tableName = "RoomBitwardenOrganization",
    primaryKeys = [
        "organizationId",
        "accountId",
    ],
    foreignKeys = [
        ForeignKey(
            entity = RoomBitwardenToken::class,
            parentColumns = arrayOf("accountId"),
            childColumns = arrayOf("accountId"),
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoomBitwardenOrganization(
    val organizationId: String,
    val accountId: String,
    val content: BitwardenOrganization,
)
