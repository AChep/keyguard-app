package com.artemchep.keyguard.room

import androidx.room.Entity
import androidx.room.ForeignKey
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection

@Entity(
    tableName = "RoomBitwardenCollection",
    primaryKeys = [
        "collectionId",
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
data class RoomBitwardenCollection(
    val collectionId: String,
    val accountId: String,
    val content: BitwardenCollection,
)
