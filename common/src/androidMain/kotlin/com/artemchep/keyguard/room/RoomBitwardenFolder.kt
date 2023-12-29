package com.artemchep.keyguard.room

import androidx.room.Entity
import androidx.room.ForeignKey
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder

@Entity(
    tableName = "RoomBitwardenFolder",
    primaryKeys = [
        "folderId",
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
data class RoomBitwardenFolder(
    val folderId: String,
    val accountId: String,
    val content: BitwardenFolder,
)
