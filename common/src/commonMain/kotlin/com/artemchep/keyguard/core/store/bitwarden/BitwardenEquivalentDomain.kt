package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@optics(
    [
        arrow.optics.OpticsTarget.LENS,
        arrow.optics.OpticsTarget.OPTIONAL,
    ],
)
data class BitwardenEquivalentDomain(
    /**
     * Id of the bitwarden account, generated on
     * login.
     */
    val accountId: String,
    val entryId: String,
    // fields
    val excluded: Boolean,
    val domains: List<String>,
    val type: Type,
) {
    companion object;

    @JsonClassDiscriminator("_type")
    @Serializable
    sealed interface Type {
        @SerialName("global")
        @Serializable
        data class Global(
            val type: Int,
        ) : Type

        @SerialName("custom")
        @Serializable
        data object Custom : Type
    }
}
