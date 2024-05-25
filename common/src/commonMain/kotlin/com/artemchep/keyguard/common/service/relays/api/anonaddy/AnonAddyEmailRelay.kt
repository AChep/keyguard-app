package com.artemchep.keyguard.common.service.relays.api.anonaddy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.relays.api.EmailRelaySchema
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.provider.bitwarden.api.builder.ensureSuffix
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AnonAddyEmailRelay(
    private val httpClient: HttpClient,
) : EmailRelay {
    companion object {
        private const val ENDPOINT_BASE_URL = "https://app.addy.io/"
        private const val ENDPOINT_PATH = "api/v1/aliases"

        private const val KEY_API_KEY = "apiKey"
        private const val HINT_API_KEY =
            "addy_io_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        private const val KEY_DOMAIN = "domain"
        private const val HINT_DOMAIN = "anonaddy.me"
        private const val KEY_BASE_URL = "base_url"
        private const val HINT_BASE_URL = ENDPOINT_BASE_URL
    }

    override val type = "AnonAddy"

    override val name = "addy.io"

    override val docUrl = "https://bitwarden.com/help/generator/#tab-addy.io-3Uj911RtQsJD9OAhUuoKrz"

    override val schema = persistentMapOf(
        KEY_API_KEY to EmailRelaySchema(
            title = TextHolder.Res(Res.string.api_key),
            hint = TextHolder.Value(HINT_API_KEY),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Token,
            canBeEmpty = false,
        ),
        KEY_DOMAIN to EmailRelaySchema(
            title = TextHolder.Res(Res.string.domain),
            hint = TextHolder.Value(HINT_DOMAIN),
            canBeEmpty = false,
        ),
        KEY_BASE_URL to EmailRelaySchema(
            title = TextHolder.Res(Res.string.emailrelay_base_env_server_url_label),
            hint = TextHolder.Value(HINT_BASE_URL),
            description = TextHolder.Res(Res.string.emailrelay_base_env_note),
            canBeEmpty = true,
        ),
    )

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(),
    )

    override fun generate(
        context: GeneratorContext,
        config: ImmutableMap<String, String>,
    ): IO<String> = ioEffect {
        val apiKey = requireNotNull(config[KEY_API_KEY]) {
            "API key is required for creating an email alias."
        }
        val domain = requireNotNull(config[KEY_DOMAIN]) {
            "Domain is required for creating an email alias."
        }
        val apiUrl = kotlin.run {
            val baseUrl = config[KEY_BASE_URL]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: ENDPOINT_BASE_URL
            baseUrl.ensureSuffix("/") + ENDPOINT_PATH
        }
        // https://app.anonaddy.com/docs/#aliases-POSTapi-v1-aliases
        val response = httpClient
            .post(apiUrl) {
                header("Authorization", "Bearer $apiKey")

                val body = AnonAddyRequest(
                    domain = domain,
                    description = context.host,
                )
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        require(response.status.isSuccess()) {
            // Example of an error:
            // {"message":"Unauthenticated."}
            val message = runCatching {
                val result = response
                    .body<JsonObject>()
                result["message"]?.jsonPrimitive?.content
            }.getOrNull()
            message ?: HttpStatusCode.fromValue(response.status.value).description
        }
        val result = response
            .body<JsonObject>()
        val alias = runCatching {
            result["data"]?.jsonObject?.get("email")?.jsonPrimitive?.content
        }.getOrNull()
        requireNotNull(alias) {
            "Email alias is missing from the response. " +
                    "Please report this to the developer."
        }
    }

    @Serializable
    private data class AnonAddyRequest(
        val domain: String,
        val description: String?,
    )
}
