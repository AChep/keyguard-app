package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@optics
@Serializable
sealed interface BitwardenOptionalStringNullable {
    companion object;

    @optics
    @Serializable
    @SerialName("some")
    data class Some(
        val value: String?,
    ) : BitwardenOptionalStringNullable {
        companion object;
    }

    @Serializable
    @SerialName("none")
    data object None : BitwardenOptionalStringNullable
}
