package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerHeader
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.uuid.Uuid

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator(DB_TYPE_CLASS_DISCRIMINATOR)
sealed interface ServiceToken {
    val id: String
}

/**
 * Represents the authentication and configuration details
 * for a Bitwarden account.
 */
@Serializable
@SerialName("bitwarden")
data class BitwardenToken(
    override val id: String = Uuid.random().toString(),
    val key: Key,
    val token: Token? = null,
    val user: User,
    /** Information about the Bitwarden server */
    val env: Environment,
) : ServiceToken {
    fun formatUser() = "<" +
            "id=$id, " +
            "email=${user.email}" +
            ">"

    @Serializable
    data class Token(
        val refreshToken: String,
        val accessToken: String,
        /**
         * Expiration date of the refresh token, ask user to re-login
         * after the old one is expired!
         */
        val expirationDate: Instant,
    )

    @optics
    @Serializable
    data class Key(
        val masterKeyBase64: String,
        val passwordKeyBase64: String,
        val encryptionKeyBase64: String,
        val macKeyBase64: String,
    ) {
        companion object
    }

    @optics
    @Serializable
    data class Environment(
        val baseUrl: String = "",
        val webVaultUrl: String = "",
        val apiUrl: String = "",
        val identityUrl: String = "",
        val iconsUrl: String = "",
        val region: Region = Region.US,
        val headers: List<Header> = emptyList(),
    ) {
        companion object {
            fun of(model: ServerEnv) = Environment(
                baseUrl = model.baseUrl,
                webVaultUrl = model.webVaultUrl,
                apiUrl = model.apiUrl,
                identityUrl = model.identityUrl,
                iconsUrl = model.iconsUrl,
                region = when (model.region) {
                    ServerEnv.Region.US -> Region.US
                    ServerEnv.Region.EU -> Region.EU
                },
                headers = model
                    .headers
                    .map { header ->
                        Header(
                            key = header.key,
                            value = header.value,
                        )
                    },
            )
        }

        fun back() = ServerEnv(
            baseUrl = baseUrl,
            webVaultUrl = webVaultUrl,
            apiUrl = apiUrl,
            identityUrl = identityUrl,
            iconsUrl = iconsUrl,
            region = when (region) {
                Region.US -> ServerEnv.Region.US
                Region.EU -> ServerEnv.Region.EU
            },
            headers = headers
                .map { header ->
                    ServerHeader(
                        key = header.key,
                        value = header.value,
                    )
                },
        )

        @Serializable
        enum class Region {
            US,
            EU,
        }

        @optics
        @Serializable
        data class Header(
            val key: String,
            val value: String,
        ) {
            companion object
        }
    }

    @Serializable
    data class User(
        val email: String,
    )
}

/**
 * Represents the authentication details
 * for a Keepass database.
 */
@Serializable
@SerialName("keepass")
data class KeePassToken(
    override val id: String = Uuid.random().toString(),
    val key: Key,
    val files: Files,
) : ServiceToken {

    @optics
    @Serializable
    data class Key(
        val passwordBase64: String,
        val keyBase64: String? = null,
    ) {
        companion object
    }

    @Serializable
    data class Files(
        val databaseUri: String,
        val databaseFileName: String = kotlin.run {
            val regex = ".*/(.+)\\??.*".toRegex()
            val result = regex.matchEntire(databaseUri)
            result?.groupValues?.getOrNull(1)
                ?: databaseUri
        },
    )
}

fun BitwardenToken.Companion.generated(): BitwardenToken {
    val accountIds = listOf(
        "account_a",
        "account_b",
        "account_c",
    )
    val folderIds = listOf(
        "folder_a",
        "folder_b",
        "folder_c",
        null,
    )
    val name = listOf(
        "List",
    )
    return BitwardenToken(
        id = accountIds.random(),
        key = BitwardenToken.Key(
            masterKeyBase64 = "",
            passwordKeyBase64 = "",
            encryptionKeyBase64 = "",
            macKeyBase64 = "",
        ),
        env = BitwardenToken.Environment(),
        user = BitwardenToken.User(
            email = "email",
        ),
    )
}
