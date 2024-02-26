package com.artemchep.keyguard.common.service.export.entity

import com.artemchep.keyguard.provider.bitwarden.entity.UriMatchTypeEntity
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ItemLoginExportEntity(
    val uris: List<ItemLoginUriExportEntity>? = null,
    val username: String? = null,
    val password: String? = null,
    val passwordRevisionDate: Instant? = null,
    val totp: String? = null,
    val fido2Credentials: List<ItemLoginFido2CredentialsExportEntity>? = null,
)

@Serializable
data class ItemLoginUriExportEntity(
    val uri: String? = null,
    val match: UriMatchTypeEntity? = null,
)

@Serializable
data class ItemLoginFido2CredentialsExportEntity(
    val credentialId: String? = null,
    val keyType: String,
    val keyAlgorithm: String,
    val keyCurve: String,
    val keyValue: String,
    val rpId: String,
    val rpName: String,
    val counter: String,
    val userHandle: String,
    val userName: String? = null,
    val userDisplayName: String? = null,
    val discoverable: String,
    val creationDate: Instant,
)
