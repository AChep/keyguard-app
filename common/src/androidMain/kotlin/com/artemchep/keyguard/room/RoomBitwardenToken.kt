package com.artemchep.keyguard.room

import androidx.room.Entity
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken

@Entity(
    tableName = "RoomBitwardenToken",
    primaryKeys = [
        "accountId",
    ],
)
data class RoomBitwardenToken(
    val accountId: String,
    val content: BitwardenToken,
)
