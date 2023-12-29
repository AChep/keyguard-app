package com.artemchep.keyguard.provider.bitwarden.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class IdentityEntity(
    @JsonNames("title")
    @SerialName("Title")
    val title: String? = null,
    @JsonNames("firstName")
    @SerialName("FirstName")
    val firstName: String? = null,
    @JsonNames("middleName")
    @SerialName("MiddleName")
    val middleName: String? = null,
    @JsonNames("lastName")
    @SerialName("LastName")
    val lastName: String? = null,
    @JsonNames("address1")
    @SerialName("Address1")
    val address1: String? = null,
    @JsonNames("address2")
    @SerialName("Address2")
    val address2: String? = null,
    @JsonNames("address3")
    @SerialName("Address3")
    val address3: String? = null,
    @JsonNames("city")
    @SerialName("City")
    val city: String? = null,
    @JsonNames("state")
    @SerialName("State")
    val state: String? = null,
    @JsonNames("postalCode")
    @SerialName("PostalCode")
    val postalCode: String? = null,
    @JsonNames("country")
    @SerialName("Country")
    val country: String? = null,
    @JsonNames("company")
    @SerialName("Company")
    val company: String? = null,
    @JsonNames("email")
    @SerialName("Email")
    val email: String? = null,
    @JsonNames("phone")
    @SerialName("Phone")
    val phone: String? = null,
    @JsonNames("ssn")
    @SerialName("SSN")
    val ssn: String? = null,
    @JsonNames("username")
    @SerialName("Username")
    val username: String? = null,
    @JsonNames("passportNumber")
    @SerialName("PassportNumber")
    val passportNumber: String? = null,
    @JsonNames("licenseNumber")
    @SerialName("LicenseNumber")
    val licenseNumber: String? = null,
)
