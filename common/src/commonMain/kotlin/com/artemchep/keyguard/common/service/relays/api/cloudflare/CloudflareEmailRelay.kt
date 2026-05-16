package com.artemchep.keyguard.common.service.relays.api.cloudflare

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.relays.api.EmailRelaySchema
import com.artemchep.keyguard.feature.auth.common.util.REGEX_DOMAIN
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.compose.resources.DrawableResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

class CloudflareEmailRelay(
    private val httpClient: HttpClient,
    private val cryptoGenerator: CryptoGenerator,
) : EmailRelay {
    companion object {
        private const val ENDPOINT_BASE_URL = "https://api.cloudflare.com/client/v4/zones"

        private const val KEY_API_TOKEN = "apiToken"
        private const val HINT_API_TOKEN = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        private const val KEY_ZONE_ID = "zoneId"
        private const val HINT_ZONE_ID = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        private const val KEY_DOMAIN = "domain"
        private const val HINT_DOMAIN = "example.com"
        private const val KEY_DESTINATION_EMAIL = "destinationEmail"
        private const val HINT_DESTINATION_EMAIL = "user@example.com"

        private const val ALIAS_LENGTH = 8

        private val ALIAS_CHARS = ('a'..'z') + ('0'..'9')
    }

    override val type = "CloudflareEmailRouting"

    override val iconRes: DrawableResource
        get() = Res.drawable.ic_logo_email_relay_cloudflare

    override val name = "Cloudflare Email Routing"

    override val docUrl =
        "https://developers.cloudflare.com/email-service/configuration/email-routing-addresses/"

    override val schema = persistentMapOf(
        KEY_API_TOKEN to EmailRelaySchema(
            title = TextHolder.Res(Res.string.api_token),
            hint = TextHolder.Value(HINT_API_TOKEN),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Token,
            canBeEmpty = false,
        ),
        KEY_ZONE_ID to EmailRelaySchema(
            title = TextHolder.Res(Res.string.zone_id),
            hint = TextHolder.Value(HINT_ZONE_ID),
            canBeEmpty = false,
        ),
        KEY_DOMAIN to EmailRelaySchema(
            title = TextHolder.Res(Res.string.domain),
            hint = TextHolder.Value(HINT_DOMAIN),
            canBeEmpty = false,
        ),
        KEY_DESTINATION_EMAIL to EmailRelaySchema(
            title = TextHolder.Res(Res.string.destination_email),
            hint = TextHolder.Value(HINT_DESTINATION_EMAIL),
            canBeEmpty = false,
        ),
    )

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(),
        cryptoGenerator = directDI.instance(),
    )

    override fun generate(
        context: GeneratorContext,
        config: ImmutableMap<String, String>,
    ): IO<String> = ioEffect {
        val apiToken = config.requireValue(
            key = KEY_API_TOKEN,
            label = "API token",
        )
        val zoneId = config.requireValue(
            key = KEY_ZONE_ID,
            label = "Zone ID",
        )
        val domain = config
            .requireValue(
                key = KEY_DOMAIN,
                label = "Domain",
            )
            .trim()
            .lowercase()
            .also { value ->
                require(REGEX_DOMAIN.matches(value)) {
                    "Invalid domain."
                }
            }
        val destinationEmail = config
            .requireValue(
                key = KEY_DESTINATION_EMAIL,
                label = "Destination email",
            )
            .also { value ->
                require(REGEX_EMAIL.matches(value)) {
                    "Invalid destination email."
                }
            }

        val alias = generateAlias(domain)
        val endpoint = "$ENDPOINT_BASE_URL/$zoneId/email/routing/rules"
        val response = httpClient
            .post(endpoint) {
                header("Authorization", "Bearer $apiToken")
                contentType(ContentType.Application.Json)
                setBody(
                    CloudflareRoutingRuleRequest(
                        name = context.ruleName(),
                        enabled = true,
                        actions = listOf(
                            CloudflareRoutingRuleAction(
                                type = "forward",
                                value = listOf(destinationEmail),
                            ),
                        ),
                        matchers = listOf(
                            CloudflareRoutingRuleMatcher(
                                type = "literal",
                                field = "to",
                                value = alias,
                            ),
                        ),
                    ),
                )
            }

        val responseBody = response.jsonObjectOrNull()
        require(response.status.isSuccess()) {
            responseBody?.errorMessageOrNull()
                ?: HttpStatusCode.fromValue(response.status.value).description
        }

        val success = responseBody
            ?.get("success")
            .booleanOrNull()
        require(success == true) {
            responseBody?.errorMessageOrNull()
                ?: "Cloudflare failed to create an email routing rule."
        }

        alias
    }

    private fun generateAlias(domain: String): String {
        val localPart = buildString(capacity = ALIAS_LENGTH) {
            repeat(ALIAS_LENGTH) {
                val index = cryptoGenerator.random(0..ALIAS_CHARS.lastIndex)
                append(ALIAS_CHARS[index])
            }
        }
        return "$localPart@$domain"
    }

    private fun GeneratorContext.ruleName(): String {
        val host = host
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return if (host != null) {
            "Keyguard: $host"
        } else {
            "Generated by Keyguard"
        }
    }

    private fun ImmutableMap<String, String>.requireValue(
        key: String,
        label: String,
    ): String = requireNotNull(
        this[key]
            ?.trim()
            ?.takeIf { it.isNotEmpty() },
    ) {
        "$label is required for creating an email alias."
    }

    private suspend fun io.ktor.client.statement.HttpResponse.jsonObjectOrNull() = runCatching {
        body<JsonObject>()
    }.getOrNull()

    private fun JsonObject.errorMessageOrNull(): String? {
        val errors = this["errors"]
            .jsonArrayOrNull()
            ?.mapNotNull { it.errorEntryOrNull() }
            .orEmpty()
        if (errors.isNotEmpty()) {
            return errors.joinToString(separator = "; ")
        }

        return this["message"].stringOrNull()
            ?: this["error"].stringOrNull()
    }

    private fun JsonElement?.errorEntryOrNull(): String? {
        val obj = jsonObjectOrNull()
            ?: return stringOrNull()
        val code = obj["code"].stringOrNull()
        val message = obj["message"].stringOrNull()
        return when {
            code != null && message != null -> "$message ($code)"
            message != null -> message
            code != null -> code
            else -> null
        }
    }

    private fun JsonElement?.jsonObjectOrNull() = this as? JsonObject

    private fun JsonElement?.jsonArrayOrNull() = this as? JsonArray

    private fun JsonElement?.stringOrNull() = (this as? JsonPrimitive)?.content

    private fun JsonElement?.booleanOrNull() = (this as? JsonPrimitive)?.booleanOrNull

    @Serializable
    private data class CloudflareRoutingRuleRequest(
        val name: String,
        val enabled: Boolean,
        val actions: List<CloudflareRoutingRuleAction>,
        val matchers: List<CloudflareRoutingRuleMatcher>,
    )

    @Serializable
    private data class CloudflareRoutingRuleAction(
        val type: String,
        val value: List<String>,
    )

    @Serializable
    private data class CloudflareRoutingRuleMatcher(
        val type: String,
        val field: String,
        val value: String,
    )
}
