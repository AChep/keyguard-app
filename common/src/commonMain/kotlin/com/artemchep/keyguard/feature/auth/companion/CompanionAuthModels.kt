package com.artemchep.keyguard.feature.auth.companion

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerHeader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class CompanionAuthProvider {
    BITWARDEN,
    KEEPASS,
}

@Serializable
enum class CompanionAuthStatus {
    STARTED,
    CANCELLED,
    SUCCESS,
    ERROR,
}

@Serializable
enum class CompanionAuthError {
    PHONE_UNAVAILABLE,
    MALFORMED_REQUEST_ID,
    MULTIPLE_REACHABLE_PHONES,
    TRUSTED_PHONE_UNAVAILABLE,
    PAYLOAD_TOO_LARGE,
    REQUEST_FAILED,
    LAUNCH_FAILED,
    TIMEOUT,
    IMPORT_FAILED,
    UNSUPPORTED_PROTOCOL,
    SECURITY_VALIDATION_FAILED,
    INTEGRITY_CHECK_FAILED,
    CANCELLED,
    INVALID_REQUEST,
    UNKNOWN,
}

@Serializable
data class CompanionAuthRequest(
    val requestId: String,
    val provider: CompanionAuthProvider,
    val protocolVersion: Int,
    val watchPublicKey: String,
)

@Serializable
data class CompanionAuthResponse(
    val requestId: String,
    val provider: CompanionAuthProvider,
    val protocolVersion: Int,
    val status: CompanionAuthStatus,
    val error: CompanionAuthError? = null,
    val message: String? = null,
    val phonePublicKey: String? = null,
    val encryptedPayload: CompanionAuthEncryptedPayload? = null,
)

@Serializable
sealed interface CompanionAuthPayload

@Serializable
data class CompanionAuthEncryptedPayload(
    val cipherText: String,
)

@Serializable
data class CompanionAuthEncryptedBlob(
    val cipherText: String,
)

@Serializable
sealed interface CompanionAuthSecurePayload

@Serializable
@SerialName("cancelled")
data class CompanionAuthCancelledPayload(
    val message: String? = null,
) : CompanionAuthSecurePayload

@Serializable
@SerialName("error")
data class CompanionAuthErroredPayload(
    val error: CompanionAuthError,
    val message: String? = null,
) : CompanionAuthSecurePayload

@Serializable
@SerialName("bitwarden")
data class CompanionBitwardenPayload(
    val env: CompanionBitwardenEnvironment,
    val email: String,
    val masterKeyBase64: String,
    val passwordKeyBase64: String,
    val encryptionKeyBase64: String,
    val macKeyBase64: String,
    val refreshToken: String,
    val accessToken: String,
    val accessTokenExpirationDate: String,
) : CompanionAuthPayload, CompanionAuthSecurePayload

@Serializable
data class CompanionBitwardenEnvironment(
    val baseUrl: String = "",
    val webVaultUrl: String = "",
    val apiUrl: String = "",
    val identityUrl: String = "",
    val iconsUrl: String = "",
    val region: Region = Region.US,
    val headers: List<Header> = emptyList(),
) {
    @Serializable
    enum class Region {
        US,
        EU,
    }

    @Serializable
    data class Header(
        val key: String,
        val value: String,
    )

    fun toServerEnv() = ServerEnv(
        baseUrl = baseUrl,
        webVaultUrl = webVaultUrl,
        apiUrl = apiUrl,
        identityUrl = identityUrl,
        iconsUrl = iconsUrl,
        region = when (region) {
            Region.US -> ServerEnv.Region.US
            Region.EU -> ServerEnv.Region.EU
        },
        headers = headers.map { header ->
            ServerHeader(
                key = header.key,
                value = header.value,
            )
        },
    )

    companion object {
        fun of(env: ServerEnv) = CompanionBitwardenEnvironment(
            baseUrl = env.baseUrl,
            webVaultUrl = env.webVaultUrl,
            apiUrl = env.apiUrl,
            identityUrl = env.identityUrl,
            iconsUrl = env.iconsUrl,
            region = when (env.region) {
                ServerEnv.Region.US -> Region.US
                ServerEnv.Region.EU -> Region.EU
            },
            headers = env.headers.map { header ->
                Header(
                    key = header.key,
                    value = header.value,
                )
            },
        )
    }
}

@Serializable
@SerialName("keepass")
data class CompanionKeePassPayload(
    val databaseFileName: String,
    val keyFileName: String? = null,
    val password: String,
) : CompanionAuthPayload, CompanionAuthSecurePayload

object CompanionAuthProtocol {
    const val VERSION = 2
    const val PHONE_CAPABILITY = "com.artemchep.keyguard.phone_companion_auth"
    const val LAUNCH_TIMEOUT_MS = 60_000L
    const val SESSION_TIMEOUT_MS = 5L * 60_000L
    const val MAX_RESPONSE_MESSAGE_BYTES = 128 * 1024
    const val MAX_RESPONSE_CIPHERTEXT_BYTES = 96 * 1024
    const val MAX_KEEPASS_KEY_FILE_BYTES = 8L * 1024L * 1024L
    const val MAX_KEEPASS_DATABASE_BYTES = 128L * 1024L * 1024L

    const val REQUEST_PATH = "/companion-auth/request"
    const val RESPONSE_PATH = "/companion-auth/response"

    private const val CHANNEL_ROOT = "/companion-auth/channel"

    fun keepassDatabaseChannelPath(requestId: String): String =
        "$CHANNEL_ROOT/$requestId/database"

    fun keepassKeyFileChannelPath(requestId: String): String =
        "$CHANNEL_ROOT/$requestId/key"
}

fun canonicalCompanionAuthRequestIdOrNull(
    value: String?,
): String? {
    val canonical = value
        ?.let { raw ->
            kotlin.runCatching {
                Uuid.parse(raw).toString()
            }.getOrNull()
        }
        ?: return null
    return canonical.takeIf { it == value }
}
