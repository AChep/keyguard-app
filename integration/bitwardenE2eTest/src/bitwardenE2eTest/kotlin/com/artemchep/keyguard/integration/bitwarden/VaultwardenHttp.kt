package com.artemchep.keyguard.integration.bitwarden

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun HttpRequestBuilder.setJsonBody(body: JsonElement) {
    setBody(TextContent(body.toString(), ContentType.Application.Json))
}

/** Returns the body, or throws with the status and the body in the message. */
internal suspend fun HttpResponse.requireSuccess(action: String): String {
    val text = bodyAsText()
    check(status.isSuccess()) {
        "$action failed: HTTP ${status.value} ${call.request.url}\n$text"
    }
    return text
}

internal suspend fun HttpResponse.requireJson(action: String): JsonElement {
    val text = requireSuccess(action)
    return Json.parseToJsonElement(text)
}

internal fun JsonObject.string(name: String): String {
    val value = get(name)?.jsonPrimitive?.contentOrNull
    return requireNotNull(value) { "Expected a string '$name' in $keys" }
}

internal fun JsonObject.stringOrNull(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
