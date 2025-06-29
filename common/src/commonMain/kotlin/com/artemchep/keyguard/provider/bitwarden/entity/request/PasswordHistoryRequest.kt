package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.common.util.to6DigitsNanosOfSecond
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PasswordHistoryRequest(
    @SerialName("password")
    val password: String,
    @SerialName("lastUsedDate")
    val lastUsedDate: Instant,
)

fun PasswordHistoryRequest.Companion.of(
    model: BitwardenCipher.Login.PasswordHistory,
) = kotlin.run {
    val lastUsedDate = model.lastUsedDate
        ?.to6DigitsNanosOfSecond()
        // Bitwarden forces us to have a last used date for
        // the password history item. It still allows for existing
        // items to have it as null tho.
        ?: Instant.fromEpochMilliseconds(0)
    PasswordHistoryRequest(
        password = model.password,
        lastUsedDate = lastUsedDate,
    )
}
