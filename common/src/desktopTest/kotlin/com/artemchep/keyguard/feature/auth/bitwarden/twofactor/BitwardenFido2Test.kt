package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.util.fido2.Fido2AssertionRequest
import com.artemchep.keyguard.util.fido2.Fido2AssertionResult
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class BitwardenFido2Test {
    private val vault = "https://vault.example.com:8443/bitwarden"
    private val origin = "https://vault.example.com:8443"
    private val appId = "$vault/app-id.json"
    private val credential = byteArrayOf(1, 2, 3)
    private val challenge = ByteArray(32) { 7 }
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    @Test
    fun respectsConfiguredVaultOriginAndRejectsForeignCredentialScopes() {
        val request = request()
        assertEquals(origin, request.origin)
        assertEquals(appId, request.appId)
        assertFails { request(rp = "bitwarden.com") }
        assertFails { request(appId = "https://foreign.example/app-id.json") }
        assertFails { bitwardenFido2Request(options(), "http://vault.example.com") }
        assertFails { bitwardenFido2Request(options(), "https://user@vault.example.com") }
    }

    @Test
    fun serializesBitwardenTokenAndPreservesExactClientData() {
        val request = request()
        val result = result(request)
        val token = bitwardenFido2Token(request, result)
        val root = Json.parseToJsonElement(token).jsonObject
        assertEquals("public-key", root.getValue("type").jsonPrimitive.content)
        assertEquals(base64.encode(credential), root.getValue("rawId").jsonPrimitive.content)
        assertEquals(
            base64.encode(result.clientDataJson),
            root.getValue("response").jsonObject.getValue("clientDataJson").jsonPrimitive.content,
        )
        val browserResult = bitwardenFido2BrowserResult(token)
        assertContentEquals(result.signature, browserResult.signature)
        assertEquals(token, bitwardenFido2Token(request, browserResult))
    }

    @Test
    fun rejectsStaleChallengesWrongOriginsAndMissingPresenceOrVerification() {
        val request = request()
        assertFails { bitwardenFido2Token(request, result(request, flags = 0)) }
        assertFails { bitwardenFido2Token(request(verification = "required"), result(request, flags = 1)) }
        assertFails { bitwardenFido2Token(request, result(request, rp = "other.example")) }
        assertFails { bitwardenFido2Token(request, result(request, credential = byteArrayOf(9))) }
        for (field in listOf("challenge", "origin", "type")) {
            val clientData = Json.parseToJsonElement(request.clientDataJson.decodeToString()).jsonObject.toMutableMap()
            clientData[field] = JsonPrimitive("wrong")
            assertFails {
                val changed = JsonObject(clientData).toString().encodeToByteArray()
                bitwardenFido2Token(request, result(request, clientData = changed))
            }
        }
    }

    @Test
    fun migratedU2fCredentialsRequireTheValidatedAppIdAndExtensionOutput() {
        val request = request()
        bitwardenFido2Token(request, result(request, rp = appId, appIdUsed = true))
        assertFails { bitwardenFido2Token(request, result(request, rp = appId)) }
        assertFails { bitwardenFido2Token(request, result(request, appIdUsed = true)) }
    }

    private fun request(
        rp: String = "vault.example.com",
        appId: String = this.appId,
        verification: String = "discouraged",
    ) =
        bitwardenFido2Request(options(rp, appId, verification), vault)

    private fun options(
        rp: String = "vault.example.com",
        appId: String = this.appId,
        verification: String = "discouraged",
    ) = buildJsonObject {
        put("rpId", rp)
        put("challenge", base64.encode(challenge))
        put("userVerification", verification)
        putJsonArray("allowCredentials") {
            addJsonObject {
                put("type", "public-key")
                put("id", base64.encode(credential))
            }
        }
        putJsonObject("extensions") {
            put("appid", appId)
            put("uvm", true)
        }
    }

    private fun result(
        request: Fido2AssertionRequest,
        flags: Int = 1,
        rp: String = request.rpId,
        credential: ByteArray = this.credential,
        appIdUsed: Boolean = false,
        clientData: ByteArray = request.clientDataJson,
    ) = Fido2AssertionResult(
        credentialId = credential,
        authenticatorData = NativeCrypto.primitives.sha256(rp.encodeToByteArray()) +
            byteArrayOf(flags.toByte(), 0, 0, 0, 1),
        signature = byteArrayOf(48, 1, 2),
        userHandle = null,
        appIdUsed = appIdUsed,
        clientDataJson = clientData,
    )
}
