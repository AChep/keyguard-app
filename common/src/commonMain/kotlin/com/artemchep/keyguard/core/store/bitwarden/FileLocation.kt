package com.artemchep.keyguard.core.store.bitwarden

import com.artemchep.keyguard.common.model.Password
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.time.Instant

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator(DB_TYPE_CLASS_DISCRIMINATOR)
sealed interface FileLocation {
    val displayName: String

    @Serializable
    @SerialName("local")
    data class Local(
        val uri: String,
        val accessToken: String? = null,
        val managedByApp: Boolean = false,
        override val displayName: String,
    ) : FileLocation

    @Serializable
    @SerialName("web_dav")
    data class WebDav(
        val url: String,
        val username: String? = null,
        val password: Password? = null,
        override val displayName: String,
    ) : FileLocation

    @Serializable
    @SerialName("google_drive")
    data class GoogleDrive(
        val account: OAuthAccount,
        val fileId: String,
        val driveId: String? = null,
        val resourceKey: String? = null,
        val parentId: String? = null,
        val pathHint: String? = null,
        override val displayName: String,
    ) : FileLocation

    @Serializable
    @SerialName("one_drive")
    data class OneDrive(
        val account: OAuthAccount,
        val driveId: String,
        val itemId: String,
        val driveType: String? = null,
        val pathHint: String? = null,
        val webUrl: String? = null,
        override val displayName: String,
    ) : FileLocation

    @Serializable
    @SerialName("dropbox")
    data class Dropbox(
        val account: OAuthAccount,
        val rootNamespaceId: String?,
        val homeNamespaceId: String?,
        val selectedNamespaceId: String?,
        val fileId: String,
        val pathLower: String?,
        val displayPath: String?,
        override val displayName: String,
    ) : FileLocation

    @Serializable
    @SerialName("sftp")
    data class Sftp(
        val host: String,
        val port: Int = 22,
        val username: String,
        val path: String,
        val auth: SftpAuth,
        val hostKeyPins: List<SftpHostKeyPin>,
        override val displayName: String,
    ) : FileLocation

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator(DB_TYPE_CLASS_DISCRIMINATOR)
    sealed interface SftpAuth {
        @Serializable
        @SerialName("password")
        data class Password(
            val password: com.artemchep.keyguard.common.model.Password,
        ) : SftpAuth

        @Serializable
        @SerialName("private_key")
        data class PrivateKey(
            val privateKeyBase64: String,
            val passphrase: com.artemchep.keyguard.common.model.Password? = null,
        ) : SftpAuth
    }

    @Serializable
    data class SftpHostKeyPin(
        val algorithm: String,
        val sha256Base64: String,
        val publicKeyBase64: String? = null,
    )
}

@Serializable
data class OAuthAccount(
    val providerAccountId: String,
    val email: String? = null,
    val displayName: String? = null,
    val tenantId: String? = null,
    val refreshToken: String,
    val accessToken: String? = null,
    val expiresAt: Instant? = null,
    val scopes: Set<String> = emptySet(),
)
