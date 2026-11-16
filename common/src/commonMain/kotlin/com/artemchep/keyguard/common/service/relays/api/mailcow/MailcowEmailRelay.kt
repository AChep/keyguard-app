package com.artemchep.keyguard.common.service.relays.api.mailcow

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.relays.api.EmailRelaySchema
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.compose.resources.DrawableResource

class MailcowEmailRelay(
    private val httpClient: HttpClient,
    private val cryptoGenerator: CryptoGenerator,
) : EmailRelay {
    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_DESTINATION_EMAIL = "destinationEmail"
        private const val ALIAS_LENGTH = 8
        private val ALIAS_CHARS = ('a'..'z') + ('0'..'9')
    }

    override val type = "Mailcow"

    override val name = "mailcow"

    override val iconRes: DrawableResource
        get() = Res.drawable.ic_logo_email_relay_mailcow

    override val docUrl = "https://docs.mailcow.email/manual-guides/mailcow-UI/u_e-mailcow_ui-api/"

    override val schema = persistentMapOf(
        KEY_BASE_URL to EmailRelaySchema(
            title = TextHolder.Res(Res.string.emailrelay_base_env_server_url_label),
            hint = TextHolder.Value("https://mail.example.com"),
            description = TextHolder.Res(Res.string.emailrelay_base_env_note),
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
            hint = TextHolder.Value("user@example.net"),
            canBeEmpty = false,
        ),
    )

    override fun generate(
        context: GeneratorContext,
        config: ImmutableMap<String, String>,
    ): IO<String> = ioEffect {
        val apiUrl = config.requireValue(KEY_BASE_URL).toApiUrl()
        val apiKey = config.requireValue(KEY_API_KEY)
        val domain = config.requireValue(KEY_DOMAIN).lowercase().removePrefix("@")
        if (!REGEX_DOMAIN.matches(domain)) {
            throw MailcowException(TextHolder.Res(Res.string.error_invalid_domain))
        }
        val destinationEmail = config.requireValue(KEY_DESTINATION_EMAIL)
        if (!REGEX_EMAIL.matches(destinationEmail)) {
            throw MailcowException(TextHolder.Res(Res.string.error_invalid_email))
        }

        val localPart = buildString(capacity = ALIAS_LENGTH) {
            repeat(ALIAS_LENGTH) {
                append(ALIAS_CHARS[cryptoGenerator.random(ALIAS_CHARS.indices)])
            }
        }
        val address = "$localPart@$domain"
        createAlias(apiUrl, apiKey, address, destinationEmail)
        address
    }

    private fun String.toApiUrl(): String {
        val authority = substringAfter("://", "").substringBefore('/')
        // Ktor supplies localhost when an otherwise valid URL has no host.
        if (authority.isBlank() || authority.startsWith(':')) {
            throw MailcowException(TextHolder.Res(Res.string.error_invalid_url))
        }
        val url = runCatchingNonFatal { Url(this) }.getOrNull()
        val valid = (startsWith("https://", true) || startsWith("http://", true)) &&
                url != null && url.host.isNotBlank() &&
                (url.protocol == URLProtocol.HTTPS || url.protocol == URLProtocol.HTTP) &&
                url.user == null && url.password == null && '?' !in this && '#' !in this
        if (!valid) {
            throw MailcowException(TextHolder.Res(Res.string.error_invalid_url))
        }
        return requireNotNull(url).toString().trimEnd('/') + "/api/v1/add/alias"
    }

    private suspend fun createAlias(apiUrl: String, apiKey: String, address: String, destinationEmail: String) {
        // Custom API-key headers must not be forwarded to a redirected host.
        httpClient.config {
            followRedirects = false
            expectSuccess = false
        }.use { client ->
            val response = client.post(apiUrl) {
                header("X-API-Key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(MailcowRequest(address = address, destination = destinationEmail, active = true))
            }
            val result = runCatchingNonFatal { response.body<JsonElement>() }.getOrNull()
            requireAliasCreated(response.status, result, address)
        }
    }

    private fun requireAliasCreated(status: HttpStatusCode, result: JsonElement?, address: String) {
        val entries = when (result) {
            is JsonObject -> listOf(result)
            is JsonArray -> result.mapNotNull { it as? JsonObject }.takeIf { it.size == result.size }.orEmpty()
            else -> emptyList()
        }
        val failure = entries.firstOrNull { it["type"].stringOrNull() != "success" }
        if (!status.isSuccess() || failure != null) {
            val message = failure?.get("msg").messageOrNull()
                ?: status.description.takeUnless { status.isSuccess() }
            throw MailcowException(
                title = TextHolder.Res(Res.string.error_failed_unknown),
                text = message?.let(TextHolder::Value),
            )
        }
        val confirmed = entries.any { entry ->
            val message = entry["msg"] as? JsonArray
            message?.getOrNull(0).stringOrNull() == "alias_added" &&
                    message?.getOrNull(1).stringOrNull() == address
        }
        if (!confirmed) {
            throw MailcowException(TextHolder.Res(Res.string.error_failed_unknown))
        }
    }

    private fun ImmutableMap<String, String>.requireValue(key: String): String =
        this[key]?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MailcowException(
                title = schema.getValue(key).title,
                text = TextHolder.Res(Res.string.error_must_not_be_blank),
            )

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonElement?.messageOrNull(): String? = when (this) {
        is JsonArray -> mapNotNull { it.stringOrNull() }.joinToString(": ").takeIf { it.isNotBlank() }
        else -> stringOrNull()
    }

    @Serializable
    private data class MailcowRequest(
        val address: String,
        @SerialName("goto") val destination: String,
        val active: Boolean,
    )

    private class MailcowException(
        override val title: TextHolder,
        override val text: TextHolder? = null,
    ) : IllegalArgumentException("mailcow email alias request failed"), Readable
}
