package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class CardEntity(
    @JsonNames("cardholderName")
    @SerialName("CardholderName")
    val cardholderName: String? = null,
    @JsonNames("brand")
    @SerialName("Brand")
    val brand: String? = null,
    @JsonNames("number")
    @SerialName("Number")
    val number: String? = null,
    @JsonNames("expMonth")
    @SerialName("ExpMonth")
    val expMonth: String? = null,
    @JsonNames("expYear")
    @SerialName("ExpYear")
    val expYear: String? = null,
    @JsonNames("code")
    @SerialName("Code")
    val code: String? = null,
)
