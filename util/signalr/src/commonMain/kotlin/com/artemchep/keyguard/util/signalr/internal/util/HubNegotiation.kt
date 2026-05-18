package com.artemchep.keyguard.util.signalr.internal.util

import com.artemchep.keyguard.util.signalr.TransferFormat
import com.artemchep.keyguard.util.signalr.internal.HubConnectionOptions
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val NEGOTIATE_VERSION = 1
private const val MAX_NEGOTIATE_ATTEMPTS = 100

internal suspend fun negotiate(
    options: HubConnectionOptions,
): HubNegotiation {
    val headersWithAccessToken = options.accessTokenProvider
        ?.invoke()
        ?.let { accessToken ->
            options.headers.toMutableMap().apply {
                this["Authorization"] = "Bearer $accessToken"
            }
        }
        ?: options.headers

    return if (!options.skipNegotiate) {
        startNegotiate(
            options = options,
            url = options.baseUrl,
            headers = headersWithAccessToken,
            negotiateAttempts = 0,
        )
    } else {
        HubNegotiation(
            url = options.baseUrl,
            headers = headersWithAccessToken,
            connectionId = null,
        )
    }
}

private suspend fun startNegotiate(
    options: HubConnectionOptions,
    url: String,
    headers: Map<String, String>,
    negotiateAttempts: Int,
): HubNegotiation {
    val response = handleNegotiate(
        options = options,
        url = url,
        headers = headers,
    )
    response.error?.let { error ->
        throw RuntimeException(error)
    }

    response.url?.let { redirectUrl ->
        if (negotiateAttempts >= MAX_NEGOTIATE_ATTEMPTS) {
            throw RuntimeException("Negotiate redirection limit exceeded.")
        }

        val newHeaders = response.accessToken
            ?.let { token ->
                headers.toMutableMap().apply {
                    put("Authorization", "Bearer $token")
                }
            }
            ?: headers

        return startNegotiate(
            options = options,
            url = redirectUrl,
            headers = newHeaders,
            negotiateAttempts = negotiateAttempts + 1,
        )
    }

    val hasCompatibleTransport = response.availableTransports
        .any { availableTransport ->
            availableTransport.transferFormats.contains(
                options.protocol.transferFormat,
            )
        }
    if (!hasCompatibleTransport) {
        throw RuntimeException("There were no compatible transports on the server.")
    }

    if (response.connectionId == null) {
        throw RuntimeException("Missing required property 'connectionId'.")
    }
    if (response.negotiateVersion > 0 && response.connectionToken == null) {
        throw RuntimeException("Missing required property 'connectionToken'.")
    }

    val id = if (response.negotiateVersion > 0) {
        response.connectionToken
    } else {
        response.connectionId
    }
    val connectionId = response.connectionId

    val finalUrl = if (id != null) {
        URLBuilder(url)
            .apply { parameters.append("id", id) }
            .buildString()
    } else {
        url
    }

    return HubNegotiation(
        url = finalUrl,
        headers = headers,
        connectionId = connectionId,
    )
}

private suspend fun handleNegotiate(
    options: HubConnectionOptions,
    url: String,
    headers: Map<String, String>,
): NegotiateResponse {
    val response = options.httpClient.post(resolveNegotiateUrl(url)) {
        headers {
            headers.forEach { (key, value) ->
                append(key, value)
            }
        }
    }

    if (response.status != HttpStatusCode.OK) {
        throw RuntimeException(
            "Unexpected status code returned from negotiate: ${response.status} ${response.status.description}.",
        )
    }

    val body = response.bodyAsText()
    val jsonObject = options.json
        .parseToJsonElement(body)
        .jsonObject
    if ("ProtocolVersion" in jsonObject) {
        throw RuntimeException(
            "Detected an ASP.NET SignalR Server. This client only supports connecting to an ASP.NET Core SignalR Server.",
        )
    }

    val availableTransports = jsonObject["availableTransports"]
        ?.jsonArray
        ?.mapNotNull { transport ->
            val transportObject = transport.jsonObject
            transportObject["transport"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf(::isWebSocketTransport)
                ?: return@mapNotNull null
            val transferFormats = transportObject["transferFormats"]
                ?.jsonArray
                ?.mapNotNull { transferFormat ->
                    transferFormat.jsonPrimitive
                        .contentOrNull
                        ?.let(::transferFormatOrNull)
                }
                .orEmpty()
            AvailableTransport(
                transferFormats = transferFormats,
            )
        }
        .orEmpty()

    return NegotiateResponse(
        url = jsonObject["url"]?.jsonPrimitive?.contentOrNull,
        accessToken = jsonObject["accessToken"]?.jsonPrimitive?.contentOrNull,
        error = jsonObject["error"]?.jsonPrimitive?.contentOrNull,
        connectionId = jsonObject["connectionId"]?.jsonPrimitive?.contentOrNull,
        connectionToken = jsonObject["connectionToken"]?.jsonPrimitive?.contentOrNull,
        negotiateVersion = jsonObject["negotiateVersion"]?.jsonPrimitive?.intOrNull ?: 0,
        availableTransports = availableTransports,
    )
}

private fun resolveNegotiateUrl(
    url: String,
): String = URLBuilder(url)
    .apply {
        appendPathSegments("negotiate")
        if (!parameters.names().contains("negotiateVersion")) {
            parameters.append("negotiateVersion", NEGOTIATE_VERSION.toString())
        }
    }
    .buildString()

private fun isWebSocketTransport(
    value: String,
): Boolean = value == "WebSockets"

private fun transferFormatOrNull(
    value: String,
): TransferFormat? = when (value) {
    "Text" -> TransferFormat.Text
    "Binary" -> TransferFormat.Binary
    else -> null
}

private data class AvailableTransport(
    val transferFormats: List<TransferFormat>,
)

private data class NegotiateResponse(
    val url: String?,
    val accessToken: String?,
    val error: String?,
    val connectionId: String?,
    val connectionToken: String?,
    val negotiateVersion: Int,
    val availableTransports: List<AvailableTransport>,
)

internal data class HubNegotiation(
    val url: String,
    val headers: Map<String, String>,
    val connectionId: String?,
)
