package com.artemchep.keyguard.common.service.relays.api.duckduckgo

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.service.relays.api.EmailRelay
import com.artemchep.keyguard.common.service.relays.api.EmailRelaySchema
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DuckDuckGoEmailRelay(
    private val httpClient: HttpClient,
) : EmailRelay {
    companion object {
        private const val ENDPOINT = "https://quack.duckduckgo.com/api/email/addresses"

        private const val KEY_API_KEY = "apiKey"
        private const val HINT_API_KEY =
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    }

    override val type = "DuckDuckGo"

    override val name = "DuckDuckGo"

    override val docUrl =
        "https://bitwarden.com/help/generator/#tab-duckduckgo-3Uj911RtQsJD9OAhUuoKrz"

    override val schema = persistentMapOf(
        KEY_API_KEY to EmailRelaySchema(
            title = TextHolder.Res(Res.strings.api_key),
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
        val apiKey = requireNotNull(config[KEY_API_KEY]) {
            "API key is required for creating an email alias."
        }
        // https://duckduckgo.com/email/
        val response = httpClient
            .post(ENDPOINT) {
                header("Authorization", "Bearer $apiKey")
            }
        require(response.status.isSuccess()) {
            HttpStatusCode.fromValue(response.status.value).description
        }
        val result = response
            .body<JsonObject>()
        val aliasPrefix = result["address"]?.jsonPrimitive?.content
        requireNotNull(aliasPrefix) {
            "Email alias is missing from the response. " +
                    "Please report this to the developer."
        }
        "$aliasPrefix@duck.com"
    }
}
