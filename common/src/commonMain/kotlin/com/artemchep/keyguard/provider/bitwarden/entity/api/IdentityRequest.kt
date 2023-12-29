package com.artemchep.keyguard.provider.bitwarden.entity.api

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IdentityRequest(
    @SerialName("title")
    val title: String?,
    @SerialName("firstName")
    val firstName: String?,
    @SerialName("middleName")
    val middleName: String?,
    @SerialName("lastName")
    val lastName: String?,
    @SerialName("address1")
    val address1: String?,
    @SerialName("address2")
    val address2: String?,
    @SerialName("address3")
    val address3: String?,
    @SerialName("city")
    val city: String?,
    @SerialName("state")
    val state: String?,
    @SerialName("postalCode")
    val postalCode: String?,
    @SerialName("country")
    val country: String?,
    @SerialName("company")
    val company: String?,
    @SerialName("email")
    val email: String?,
    @SerialName("phone")
    val phone: String?,
    @SerialName("ssn")
    val ssn: String?,
    @SerialName("username")
    val username: String?,
    @SerialName("passportNumber")
    val passportNumber: String?,
    @SerialName("licenseNumber")
    val licenseNumber: String?,
) {
    companion object
}

fun IdentityRequest.Companion.of(
    model: BitwardenCipher.Identity,
) = kotlin.run {
    IdentityRequest(
        title = model.title,
        firstName = model.firstName,
        middleName = model.middleName,
        lastName = model.lastName,
        address1 = model.address1,
        address2 = model.address2,
        address3 = model.address3,
        city = model.city,
        state = model.state,
        postalCode = model.postalCode,
        country = model.country,
        company = model.company,
        email = model.email,
        phone = model.phone,
        ssn = model.ssn,
        username = model.username,
        passportNumber = model.passportNumber,
        licenseNumber = model.licenseNumber,
    )
}
