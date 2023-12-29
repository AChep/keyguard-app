package com.artemchep.keyguard.provider.bitwarden.api.builder

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.provider.bitwarden.entity.ErrorEntity
import com.artemchep.keyguard.provider.bitwarden.entity.toException
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.plusAssign
import kotlin.collections.set

suspend inline fun <reified T : Any> HttpResponse.bodyOrApiException(): T = kotlin.run {
    logCrashlyticsRoute()

    val b = this.body<HttpResponse>()
    // No need to parse a response if we want
    // unit type back.
    if (Unit is T) {
        return@run Unit
    }
    try {
        b.body<T>()
    } catch (e: Exception) {
        e.printStackTrace()

        val newStatus = HttpStatusCode.fromValue(status.value)
        val apiException = try {
            b.body<ErrorEntity>()
                .toException(
                    exception = e,
                    code = newStatus,
                )
        } catch (f: Exception) {
            f.printStackTrace()

            // Try to parse an unknown exception, it might still contain
            // the message content.
            val json = runCatching {
                b.body<JsonObject>()
            }.getOrNull()
                ?: run {
                    // This might happen if a user miss-types a server URL, so we
                    // instead get default HTML template saying that this page
                    // can not be found.
                    if (newStatus != HttpStatusCode.NotFound) {
                        val loggedException =
                            RuntimeException("Failed request: body is not a JSON!")
                        recordException(loggedException)
                    }
                    // Generic exception
                    throw HttpException(
                        statusCode = newStatus,
                        m = newStatus.description,
                        e = e,
                    )
                }

            val responseBodySanitizedJson = Json.encodeToString(json.sanitize())
            println("Sanitized response JSON:")
            println(responseBodySanitizedJson)

            val message = json.tryGetErrorMessageBitwardenApi()
                ?: json.tryGetErrorMessageCloudflareApi()
            if (
                message == null ||
                // This response is expected when we have to
                // refresh the access token, no need to log it.
                newStatus != HttpStatusCode.Unauthorized
            ) {
                val loggedException =
                    RuntimeException("Failed request! Body: $responseBodySanitizedJson")
                recordLog(responseBodySanitizedJson)
                recordException(loggedException)
            }
            HttpException(
                statusCode = newStatus,
                m = message
                    ?: newStatus.description,
                e = e,
            )
        }
        throw apiException
    }
}

fun HttpResponse.logCrashlyticsRoute() {
    val route = this.call.attributes.getOrNull(routeAttribute)
        ?: "route-unknown"
    recordLog("${status.value} @ $route")
}

fun JsonObject.tryGetErrorMessageBitwardenApi(): String? {
    val error = getIgnoreCase("error") as? JsonObject
        ?: return null
    val message = error.getIgnoreCase("description") as? JsonPrimitive
        ?: return null
    return message.contentOrNull
}

fun JsonObject.tryGetErrorMessageCloudflareApi(): String? {
    val message = getIgnoreCase("message") as? JsonPrimitive
        ?: return null
    return message.contentOrNull
}

private fun JsonObject.getIgnoreCase(key: String) = keys
    .firstOrNull { k ->
        k.equals(key, ignoreCase = true)
    }
    ?.let { this[it] }

/**
 * Sanitize all of the primitives from the json object,
 * making the response safe to be send to the crashlytics.
 */
fun JsonElement.sanitize(): JsonElement = when (this) {
    is JsonObject -> {
        val map = mutableMapOf<String, JsonElement>()
        entries.forEach { (key, child) ->
            val sanitizedChild = child.sanitize()
            map[key] = sanitizedChild
        }
        JsonObject(map)
    }

    is JsonArray -> {
        val list = mutableListOf<JsonElement>()
        forEach { child ->
            val sanitizedChild = child.sanitize()
            list += sanitizedChild
        }
        JsonArray(list)
    }

    is JsonNull -> this

    is JsonPrimitive -> {
        val value = when {
            isString -> "string"
            booleanOrNull != null -> "bool"
            doubleOrNull != null -> "number"
            else -> "other"
        }
        JsonPrimitive(value)
    }
}
