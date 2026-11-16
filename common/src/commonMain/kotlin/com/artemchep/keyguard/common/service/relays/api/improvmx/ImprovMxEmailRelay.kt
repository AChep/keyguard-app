package com.artemchep.keyguard.common.service.relays.api.improvmx

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.DrawableResource

class ImprovMxEmailRelay(
    private val base64Service: Base64Service,
    private val httpClient: HttpClient,
    private val cryptoGenerator: CryptoGenerator,
) : EmailRelay {
    companion object {
        private const val ENDPOINT_BASE_URL = "https://api.improvmx.com/v4/domains"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_DESTINATION_EMAIL = "destinationEmail"
        private const val ALIAS_LENGTH = 8
        private val ALIAS_CHARS = ('a'..'z') + ('0'..'9')
    }

    override val type = "ImprovMX"

    override val name = "ImprovMX"

    override val iconRes: DrawableResource
        get() = Res.drawable.ic_logo_email_relay_improvmx

    override val docUrl = "https://www.improvmx.com/api/v4/"

    override val schema = persistentMapOf(
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
        val apiKey = config.requireValue(KEY_API_KEY)
        val domain = config.requireValue(KEY_DOMAIN)
            .lowercase()
            .removePrefix("@")
        if (!REGEX_DOMAIN.matches(domain)) {
            throw ImprovMxException(TextHolder.Res(Res.string.error_invalid_domain))
        }
        val destinationEmail = config.requireValue(KEY_DESTINATION_EMAIL)
        if (!REGEX_EMAIL.matches(destinationEmail)) {
            throw ImprovMxException(TextHolder.Res(Res.string.error_invalid_email))
        }

        val localPart = buildString(capacity = ALIAS_LENGTH) {
            repeat(ALIAS_LENGTH) {
                append(ALIAS_CHARS[cryptoGenerator.random(ALIAS_CHARS.indices)])
            }
        }
        val response = httpClient.post("$ENDPOINT_BASE_URL/$domain/aliases") {
            val token = base64Service.encodeToString("api:$apiKey")
            header("Authorization", "Basic $token")
            contentType(ContentType.Application.Json)
            setBody(ImprovMxRequest(alias = localPart, forward = destinationEmail))
        }
        val result = runCatchingNonFatal {
            response.body<JsonObject>()
        }.getOrNull()
        val success = (result?.get("success") as? JsonPrimitive)?.booleanOrNull
        if (!response.status.isSuccess() || success == false) {
            val message = (result?.get("message") as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: response.status.description
            throw ImprovMxException(
                title = TextHolder.Res(Res.string.error_failed_unknown),
                text = TextHolder.Value(message),
            )
        }

        val alias = (result?.get("alias") as? JsonObject)?.get("alias") as? JsonPrimitive
        if (alias?.contentOrNull != localPart) {
            throw ImprovMxException(TextHolder.Res(Res.string.error_failed_unknown))
        }
        "$localPart@$domain"
    }

    private fun ImmutableMap<String, String>.requireValue(key: String): String =
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ImprovMxException(
                title = schema.getValue(key).title,
                text = TextHolder.Res(Res.string.error_must_not_be_blank),
            )

    @Serializable
    private data class ImprovMxRequest(
        val alias: String,
        val forward: String,
    )

    private class ImprovMxException(
        override val title: TextHolder,
        override val text: TextHolder? = null,
    ) : IllegalArgumentException("ImprovMX email alias request failed"), Readable
}
