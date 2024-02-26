package com.artemchep.keyguard.common.service.export.entity

import kotlinx.serialization.Serializable

@Serializable
data class ItemIdentityExportEntity(
    val title: String? = null,
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val address1: String? = null,
    val address2: String? = null,
    val address3: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val company: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val ssn: String? = null,
    val username: String? = null,
    val passportNumber: String? = null,
    val licenseNumber: String? = null,
)
