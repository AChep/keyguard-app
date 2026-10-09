@file:Suppress("MagicNumber") // WebAuthn and bounded native wire values.

package com.artemchep.keyguard.util.fido2

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal val fido2Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** The caller validates the RP ID and AppID against its trusted server origin. */
class Fido2AssertionRequest(
    val rpId: String,
    val origin: String,
    val challenge: ByteArray,
    val credentials: List<Fido2AllowedCredential>,
    val userVerification: Fido2UserVerification = Fido2UserVerification.PREFERRED,
    val appId: String? = null,
    val timeoutMillis: Int = 60_000,
) {
    init {
        require(rpId.isNotEmpty() && rpId.encodeToByteArray().size <= 253 && '\u0000' !in rpId)
        require(origin.isNotEmpty() && origin.encodeToByteArray().size <= 2048 && '\u0000' !in origin)
        require(challenge.size in 1..1024)
        require(credentials.size in 1..64)
        require(appId == null || (appId.isNotEmpty() && appId.encodeToByteArray().size <= 2048 && '\u0000' !in appId))
        require(timeoutMillis in 1..120_000)
    }

    val clientDataJson: ByteArray = buildJsonObject {
        put("type", "webauthn.get")
        put("challenge", fido2Base64.encode(challenge))
        put("origin", origin)
        put("crossOrigin", false)
    }.toString().encodeToByteArray()
}

class Fido2AllowedCredential(val id: ByteArray, val transports: List<String> = emptyList()) {
    init {
        require(id.size in 1..FIDO2_MAX_CREDENTIAL_LENGTH)
    }
}

enum class Fido2UserVerification(val wireValue: Int, val webAuthnValue: String) {
    DISCOURAGED(0, "discouraged"),
    PREFERRED(1, "preferred"),
    REQUIRED(2, "required"),
}

class Fido2AssertionResult(
    val credentialId: ByteArray,
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val userHandle: ByteArray?,
    val appIdUsed: Boolean,
    val clientDataJson: ByteArray,
)

/** Also used by the Android SDK adapter to feed the shared prompt contract. */
fun Fido2AssertionResult.encode(): ByteArray = Fido2WireWriter().apply {
    int(if (appIdUsed) 1 else 0)
    bytes(credentialId)
    bytes(authenticatorData)
    bytes(signature)
    bytes(userHandle ?: byteArrayOf())
    bytes(clientDataJson)
}.result()

fun decodeFido2AssertionResult(bytes: ByteArray): Fido2AssertionResult = Fido2WireReader(bytes).run {
    require(bytes.size <= FIDO2_MAX_RESPONSE)
    val appIdUsed = int().also { require(it in 0..1) } == 1
    val result = Fido2AssertionResult(
        credentialId = bytes(1, FIDO2_MAX_CREDENTIAL_LENGTH),
        authenticatorData = bytes(37, 65536),
        signature = bytes(1, 4096),
        userHandle = bytes(0, 64).takeIf { it.isNotEmpty() },
        appIdUsed = appIdUsed,
        clientDataJson = bytes(1, 8192),
    )
    requireFinished()
    result
}

internal fun encodeAssertionRequest(request: Fido2AssertionRequest, pin: ByteArray): ByteArray =
    Fido2WireWriter().apply {
        int(request.userVerification.wireValue)
        int(request.timeoutMillis)
        bytes(request.rpId.encodeToByteArray())
        bytes(request.origin.encodeToByteArray())
        bytes(request.clientDataJson)
        bytes(request.appId?.encodeToByteArray() ?: byteArrayOf())
        int(request.credentials.size)
        request.credentials.forEach { credential ->
            bytes(credential.id)
            // Transport hints are optional; zero lets the OS discover the transport.
            int(credential.transports.fold(0) { mask, transport ->
                mask or when (transport) {
                    "usb" -> 1
                    "nfc" -> 2
                    "ble" -> 4
                    "internal" -> 16
                    "hybrid" -> 32
                    else -> 0
                }
            })
        }
        bytes(pin)
    }.result().let { byteArrayOf(3) + it }

internal class Fido2WireWriter {
    private val output = ArrayList<Byte>()
    fun int(value: Int) {
        for (shift in listOf(24, 16, 8, 0)) output.add((value ushr shift).toByte())
    }
    fun bytes(value: ByteArray) {
        int(value.size)
        value.forEach(output::add)
    }
    fun result(): ByteArray = output.toByteArray()
}

internal class Fido2WireReader(private val input: ByteArray) {
    private var offset = 0
    fun int(): Int {
        require(input.size - offset >= 4)
        var result = 0
        repeat(4) { result = (result shl 8) or (input[offset++].toInt() and 255) }
        require(result >= 0)
        return result
    }
    fun bytes(min: Int, max: Int): ByteArray {
        val length = int()
        require(length in min..max && length <= input.size - offset)
        return input.copyOfRange(offset, offset + length).also { offset += length }
    }
    fun requireFinished() = require(offset == input.size)
}

fun Fido2AssertionRequest.webAuthnJson(): String = buildJsonObject {
    put("challenge", fido2Base64.encode(challenge))
    put("rpId", rpId)
    put("timeout", timeoutMillis)
    put("userVerification", userVerification.webAuthnValue)
    putJsonArray("allowCredentials") {
        credentials.forEach { credential ->
            addJsonObject {
                put("type", "public-key")
                put("id", fido2Base64.encode(credential.id))
                putJsonArray("transports") { credential.transports.forEach { add(it) } }
            }
        }
    }
    appId?.let { putJsonObject("extensions") { put("appid", it) } }
}.toString()

fun parseFido2AssertionRequest(options: String, origin: String): Fido2AssertionRequest {
    require(options.length <= 131072)
    val root = Json.parseToJsonElement(options).jsonObject
    val verification = root["userVerification"]?.jsonPrimitive?.content ?: "preferred"
    return Fido2AssertionRequest(
        rpId = root.getValue("rpId").jsonPrimitive.content,
        origin = origin,
        challenge = fido2Base64.decode(root.getValue("challenge").jsonPrimitive.content),
        credentials = root.getValue("allowCredentials").jsonArray.map { item ->
            val credential = item.jsonObject
            require(credential.getValue("type").jsonPrimitive.content == "public-key")
            Fido2AllowedCredential(
                id = fido2Base64.decode(credential.getValue("id").jsonPrimitive.content),
                transports = credential["transports"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        },
        userVerification = Fido2UserVerification.entries.first { it.webAuthnValue == verification },
        appId = root["extensions"]?.jsonObject?.get("appid")?.jsonPrimitive?.contentOrNull,
        timeoutMillis = (root["timeout"]?.jsonPrimitive?.longOrNull ?: 60_000).coerceIn(1, 120_000).toInt(),
    )
}
