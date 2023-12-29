package com.artemchep.keyguard.room

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "RoomFillCipherHistory",
    foreignKeys = [
        ForeignKey(
            entity = RoomBitwardenToken::class,
            parentColumns = arrayOf("accountId"),
            childColumns = arrayOf("accountId"),
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RoomBitwardenCipher::class,
            parentColumns = arrayOf("itemId"),
            childColumns = arrayOf("itemId"),
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoomFillCipherHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val itemId: String,
    val accountId: String,
    val timestamp: Long,
)
