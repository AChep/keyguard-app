package com.artemchep.keyguard.feature.fido2

import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.util.fido2.FIDO2_RP_ID
import com.artemchep.keyguard.util.fido2.Fido2Exception
import com.artemchep.keyguard.util.fido2.Fido2Operation
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class Fido2WebAuthnTest {
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private val operation =
        Fido2Operation.Derive(byteArrayOf(1, 2), ByteArray(32), ByteArray(32) { 9 })

    @Test
    fun requiresVerifiedUserAndMatchingCredentialAndRp() {
        assertContentEquals(ByteArray(32) { 42 }, operation.webAuthnResponse(response()))
        assertFails { operation.webAuthnResponse(response(flags = 1)) }
        assertFails { operation.webAuthnResponse(response(flags = 4)) }
        assertFails { operation.webAuthnResponse(response(rp = "other.example")) }
        assertFails { operation.webAuthnResponse(response(credential = byteArrayOf(9))) }
        assertFails { operation.webAuthnResponse(response(secret = ByteArray(31))) }
        assertFails { operation.webAuthnResponse(response(prf = false)) }
    }

    @Test
    fun creationMustExplicitlyEnablePrf() {
        val registration = Fido2Operation.Register(ByteArray(32), ByteArray(32))
        assertFailsWith<Fido2Exception> { registration.webAuthnResponse(response()) }
        val request = Json.parseToJsonElement(registration.webAuthnRequest()).jsonObject
        assertEquals(
            "required",
            request
                .getValue("authenticatorSelection")
                .jsonObject
                .getValue("userVerification")
                .jsonPrimitive
                .content,
        )
        val derive = Json.parseToJsonElement(operation.webAuthnRequest()).jsonObject
        assertEquals(
            base64.encode(operation.salt),
            derive
                .getValue("extensions")
                .jsonObject
                .getValue("prf")
                .jsonObject
                .getValue("eval")
                .jsonObject
                .getValue("first")
                .jsonPrimitive
                .content,
        )
    }

    private fun response(
        flags: Int = 5,
        rp: String = FIDO2_RP_ID,
        credential: ByteArray = operation.credentialId,
        secret: ByteArray = ByteArray(32) { 42 },
        prf: Boolean = true,
    ): String {
        val authData =
            NativeCrypto.primitives.sha256(rp.encodeToByteArray()) +
                byteArrayOf(flags.toByte(), 0, 0, 0, 1)
        return buildJsonObject {
                put("rawId", base64.encode(credential))
                putJsonObject("response") { put("authenticatorData", base64.encode(authData)) }
                if (prf)
                    putJsonObject("clientExtensionResults") {
                        putJsonObject("prf") {
                            putJsonObject("results") { put("first", base64.encode(secret)) }
                        }
                    }
            }
            .toString()
    }
}
