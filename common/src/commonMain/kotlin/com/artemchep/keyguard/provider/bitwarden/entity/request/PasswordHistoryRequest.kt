package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PasswordHistoryRequest(
    @SerialName("password")
    val password: String,
    @SerialName("lastUsedDate")
    val lastUsedDate: Instant?,
)

fun PasswordHistoryRequest.Companion.of(
    model: BitwardenCipher.Login.PasswordHistory,
) = kotlin.run {
    PasswordHistoryRequest(
        password = model.password,
        lastUsedDate = model.lastUsedDate,
    )
}
