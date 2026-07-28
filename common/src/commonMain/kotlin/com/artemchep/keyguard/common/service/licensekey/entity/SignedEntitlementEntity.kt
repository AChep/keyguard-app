package com.artemchep.keyguard.common.service.licensekey.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SignedEntitlementEntity(
    @SerialName("protected")
    val protectedHeader: String,
    val payload: String,
    val signature: String,
)

@Serializable
data class EntitlementProofHeaderEntity(
    val alg: String,
    val kid: String,
    val typ: String,
)

@Serializable
data class EntitlementProofPayloadEntity(
    val v: Int,
    val challenge: String,
    val iat: Long,
    val exp: Long,
    val request: EntitlementProofRequestEntity,
    val entitlement: LicenseEntitlementEntity,
)

@Serializable
data class EntitlementProofRequestEntity(
    val kind: String,
    val hash: String,
)
