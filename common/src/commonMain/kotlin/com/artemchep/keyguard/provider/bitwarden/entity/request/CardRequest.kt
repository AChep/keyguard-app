package com.artemchep.keyguard.provider.bitwarden.entity.request

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CardRequest(
    @SerialName("cardholderName")
    val cardholderName: String?,
    @SerialName("brand")
    val brand: String?,
    @SerialName("number")
    val number: String?,
    @SerialName("expMonth")
    val expMonth: String?,
    @SerialName("expYear")
    val expYear: String?,
    @SerialName("code")
    val code: String?,
) {
    companion object
}

fun CardRequest.Companion.of(
    model: BitwardenCipher.Card,
) = kotlin.run {
    CardRequest(
        cardholderName = model.cardholderName,
        brand = model.brand,
        number = model.number,
        expMonth = model.expMonth,
        expYear = model.expYear,
        code = model.code,
    )
}
