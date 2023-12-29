package com.artemchep.keyguard.room

import androidx.room.Entity
import androidx.room.ForeignKey
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta

@Entity(
    tableName = "RoomBitwardenMeta",
    primaryKeys = [
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
data class RoomBitwardenMeta(
    val accountId: String,
    val content: BitwardenMeta,
)
