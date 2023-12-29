package com.artemchep.keyguard.room

import androidx.room.Entity
import androidx.room.ForeignKey
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile

@Entity(
    tableName = "RoomBitwardenProfile",
    primaryKeys = [
        "profileId",
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
data class RoomBitwardenProfile(
    val profileId: String,
    val accountId: String,
    val content: BitwardenProfile,
)
