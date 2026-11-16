package com.artemchep.keyguard.common.service.relays.api.migadu

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.relays.api.EmailRelaySchema
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.feature.auth.common.util.REGEX_DOMAIN
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.DrawableResource

class MigaduEmailRelay(
    private val base64Service: Base64Service,
    private val httpClient: HttpClient,
    private val cryptoGenerator: CryptoGenerator,
) : EmailRelay {
    companion object {
        private const val ENDPOINT_BASE_URL = "https://api.migadu.com/v1/domains"
        private const val KEY_EMAIL = "email"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_DESTINATION_EMAIL = "destinationEmail"
        private const val ALIAS_LENGTH = 8
        private val ALIAS_CHARS = ('a'..'z') + ('0'..'9')
    }

    override val type = "Migadu"

    override val name = "Migadu"

    override val iconRes: DrawableResource
        get() = Res.drawable.ic_logo_email_relay_migadu

    override val docUrl = "https://www.migadu.com/api/"

    override val schema = persistentMapOf(
        KEY_EMAIL to EmailRelaySchema(
            title = TextHolder.Res(Res.string.email),
            hint = TextHolder.Value("admin@example.net"),
            canBeEmpty = false,
        ),
        KEY_API_KEY to EmailRelaySchema(
            title = TextHolder.Res(Res.string.api_key),
            hint = TextHolder.Value("XXXX"),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Token,
            canBeEmpty = false,
        ),
        KEY_DOMAIN to EmailRelaySchema(
            title = TextHolder.Res(Res.string.domain),
            hint = TextHolder.Value("example.com"),
            canBeEmpty = false,
        ),
        KEY_DESTINATION_EMAIL to EmailRelaySchema(
            title = TextHolder.Res(Res.string.destination_email),
            hint = TextHolder.Value("user@example.com"),
            canBeEmpty = false,
        ),
    )

    override fun generate(
        context: GeneratorContext,
        config: ImmutableMap<String, String>,
    ): IO<String> = ioEffect {
        val email = config.requireEmail(KEY_EMAIL)
        val apiKey = config.requireValue(KEY_API_KEY)
        val domain = config.requireValue(KEY_DOMAIN)
            .lowercase()
            .removePrefix("@")
        if (!REGEX_DOMAIN.matches(domain)) {
            throw MigaduException(TextHolder.Res(Res.string.error_invalid_domain))
        }
        val destinationEmail = config.requireEmail(KEY_DESTINATION_EMAIL)

        val localPart = buildString(capacity = ALIAS_LENGTH) {
            repeat(ALIAS_LENGTH) {
                append(ALIAS_CHARS[cryptoGenerator.random(ALIAS_CHARS.indices)])
            }
        }
        val address = "$localPart@$domain"
        val response = httpClient.post("$ENDPOINT_BASE_URL/$domain/aliases") {
            val token = base64Service.encodeToString("$email:$apiKey")
            header("Authorization", "Basic $token")
            contentType(ContentType.Application.Json)
            setBody(
                MigaduRequest(
                    localPart = localPart,
                    destinations = listOf(destinationEmail),
                    isInternal = false,
                ),
            )
        }
        val result = runCatchingNonFatal {
            response.body<JsonObject>()
        }.getOrNull()
        if (!response.status.isSuccess()) {
            val message = result?.get("message").stringOrNull()
                ?: result?.get("error").stringOrNull()
                ?: response.status.description
            throw MigaduException(
                title = TextHolder.Res(Res.string.error_failed_unknown),
                text = TextHolder.Value(message),
            )
        }

        if (result?.get("address").stringOrNull() != address) {
            throw MigaduException(TextHolder.Res(Res.string.error_failed_unknown))
        }
        address
    }

    private fun ImmutableMap<String, String>.requireValue(key: String): String =
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MigaduException(
                title = schema.getValue(key).title,
                text = TextHolder.Res(Res.string.error_must_not_be_blank),
            )

    private fun ImmutableMap<String, String>.requireEmail(key: String): String = requireValue(key)
        .also { value ->
            if (!REGEX_EMAIL.matches(value)) {
                throw MigaduException(TextHolder.Res(Res.string.error_invalid_email))
            }
        }

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    @Serializable
    private data class MigaduRequest(
        @SerialName("local_part") val localPart: String,
        val destinations: List<String>,
        @SerialName("is_internal") val isInternal: Boolean,
    )

    private class MigaduException(
        override val title: TextHolder,
        override val text: TextHolder? = null,
    ) : IllegalArgumentException("Migadu email alias request failed"), Readable
}
