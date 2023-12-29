package com.artemchep.keyguard.room

import androidx.room.Entity
import androidx.room.ForeignKey
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

@Entity(
    tableName = "RoomBitwardenCipher",
    primaryKeys = [
        "itemId",
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
data class RoomBitwardenCipher(
    val itemId: String,
    val accountId: String,
    val folderId: String?,
    val content: BitwardenCipher,
)
