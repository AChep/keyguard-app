package com.artemchep.keyguard.common.service.relays.api.fastmail

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.relays.api.EmailRelaySchema
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.compose.resources.DrawableResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

class FastmailEmailRelay(
    private val httpClient: HttpClient,
) : EmailRelay {
    companion object {
        private const val ENDPOINT_SESSION = "https://api.fastmail.com/jmap/session"

        private const val CAPABILITY_JMAP_CORE = "urn:ietf:params:jmap:core"
        private const val CAPABILITY_MASKED_EMAIL = "https://www.fastmail.com/dev/maskedemail"
        private const val CREATE_ID = "new-masked-email"

        private const val KEY_API_KEY = "apiKey"
        private const val HINT_API_KEY =
            "xxxx-xxxxxxxx-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx-x-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    }

    override val type = "Fastmail"

    override val name = "Fastmail"

    override val iconRes: DrawableResource
        get() = Res.drawable.ic_logo_email_relay_fastmail

    override val docUrl =
        "https://bitwarden.com/help/generator/#tab-fastmail-3Uj911RtQsJD9OAhUuoKrz"

    override val schema = persistentMapOf(
        KEY_API_KEY to EmailRelaySchema(
            title = TextHolder.Res(Res.string.api_key),
            hint = TextHolder.Value(HINT_API_KEY),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Token,
            canBeEmpty = false,
        ),
    )

    constructor(directDI: DirectDI) : this(
        httpClient = directDI.instance(),
    )

    override fun generate(
        context: GeneratorContext,
        config: ImmutableMap<String, String>,
    ): IO<String> = ioEffect {
        val apiKey = requireNotNull(
            config[KEY_API_KEY]
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
        ) {
            "API key is required for creating an email alias."
        }

        val sessionResponse = httpClient
            .get(ENDPOINT_SESSION) {
                header("Authorization", "Bearer $apiKey")
            }
        sessionResponse.requireFastmailSuccess()

        val session = sessionResponse
            .body<JsonObject>()
        val apiUrl = session["apiUrl"]
            .stringOrNull()
            ?.takeIf { it.isNotEmpty() }
        requireNotNull(apiUrl) {
            "Fastmail JMAP API URL is missing from the session response. " +
                    "Please report this to the developer."
        }
        val accountId = session["primaryAccounts"]
            .jsonObjectOrNull()
            ?.get(CAPABILITY_MASKED_EMAIL)
            .stringOrNull()
            ?.takeIf { it.isNotEmpty() }
        requireNotNull(accountId) {
            "Fastmail Masked Email account ID is missing. " +
                    "Make sure your API token has Masked Email access."
        }

        val response = httpClient
            .post(apiUrl) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(
                    createMaskedEmailRequest(
                        accountId = accountId,
                        forDomain = context.host.toForDomain(),
                    ),
                )
            }
        response.requireFastmailSuccess()

        val result = response
            .body<JsonObject>()
        val methodResponse = result["methodResponses"]
            .jsonArrayOrNull()
            ?.firstOrNull()
            .jsonArrayOrNull()
        val methodResponseName = methodResponse
            ?.getOrNull(0)
            .stringOrNull()
        val methodResponsePayload = methodResponse
            ?.getOrNull(1)
            .jsonObjectOrNull()
        when (methodResponseName) {
            "MaskedEmail/set" -> {
                val alias = methodResponsePayload
                    ?.get("created")
                    .jsonObjectOrNull()
                    ?.get(CREATE_ID)
                    .jsonObjectOrNull()
                    ?.get("email")
                    .stringOrNull()
                if (alias != null) {
                    return@ioEffect alias
                }

                val errorDescription = methodResponsePayload
                    ?.get("notCreated")
                    .jsonObjectOrNull()
                    ?.get(CREATE_ID)
                    .jsonObjectOrNull()
                    ?.get("description")
                    .stringOrNull()
                if (errorDescription != null) {
                    throw IllegalArgumentException(errorDescription)
                }
                throw IllegalStateException(
                    "Email alias is missing from the response. " +
                            "Please report this to the developer.",
                )
            }

            "error" -> {
                val errorDescription = methodResponsePayload
                    ?.get("description")
                    .stringOrNull()
                    ?: "Fastmail failed to create an email alias."
                throw IllegalArgumentException(errorDescription)
            }

            else -> throw IllegalStateException(
                "Email alias is missing from the response. " +
                        "Please report this to the developer.",
            )
        }
    }

    private fun createMaskedEmailRequest(
        accountId: String,
        forDomain: String,
    ) = JsonObject(
        mapOf(
            "using" to JsonArray(
                listOf(
                    JsonPrimitive(CAPABILITY_MASKED_EMAIL),
                    JsonPrimitive(CAPABILITY_JMAP_CORE),
                ),
            ),
            "methodCalls" to JsonArray(
                listOf(
                    JsonArray(
                        listOf(
                            JsonPrimitive("MaskedEmail/set"),
                            JsonObject(
                                mapOf(
                                    "accountId" to JsonPrimitive(accountId),
                                    "create" to JsonObject(
                                        mapOf(
                                            CREATE_ID to JsonObject(
                                                mapOf(
                                                    "state" to JsonPrimitive("enabled"),
                                                    "description" to JsonPrimitive(""),
                                                    "forDomain" to JsonPrimitive(forDomain),
                                                    "url" to JsonNull,
                                                    "emailPrefix" to JsonNull,
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            JsonPrimitive("0"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private suspend fun io.ktor.client.statement.HttpResponse.requireFastmailSuccess() {
        if (status == HttpStatusCode.Unauthorized) {
            throw IllegalArgumentException("Invalid Fastmail API token.")
        }
        if (!status.isSuccess()) {
            val message = errorMessageOrNull()
                ?: HttpStatusCode.fromValue(status.value).description
            throw IllegalArgumentException(message)
        }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.errorMessageOrNull() = runCatching {
        val result = body<JsonObject>()
        result["description"].stringOrNull()
            ?: result["detail"].stringOrNull()
            ?: result["message"].stringOrNull()
            ?: result["error"].stringOrNull()
    }.getOrNull()

    private fun String?.toForDomain(): String {
        val host = this
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return ""
        return when {
            host.startsWith("http://", ignoreCase = true) ||
                    host.startsWith("https://", ignoreCase = true) -> host

            else -> "https://$host"
        }
    }

    private fun JsonElement?.jsonObjectOrNull() = this as? JsonObject

    private fun JsonElement?.jsonArrayOrNull() = this as? JsonArray

    private fun JsonElement?.stringOrNull() = (this as? JsonPrimitive)?.content
}
