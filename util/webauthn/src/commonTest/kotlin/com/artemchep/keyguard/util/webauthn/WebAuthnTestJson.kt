package com.artemchep.keyguard.util.webauthn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun String.webAuthnResponse(): JsonObject = Json.parseToJsonElement(this)
    .jsonObject.getValue("response").jsonObject

internal fun JsonObject.decodeBytes(key: String): ByteArray =
    PasskeyBase64.decode(getValue(key).jsonPrimitive.content)
