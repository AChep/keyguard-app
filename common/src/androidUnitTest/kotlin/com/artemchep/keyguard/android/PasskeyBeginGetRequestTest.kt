package com.artemchep.keyguard.android

import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.common.service.webauthn.PasskeyCredentialId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PasskeyBeginGetRequestTest {
    @Test
    fun `begin get rejects all unknown allow credential descriptor types`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"

        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            parseCredentialProviderBeginGetAllowedCredentialDescriptors(
                requestJson = requestJson(
                    allowCredentials = listOf(
                        descriptor(
                            credentialId = credentialId,
                            type = "password",
                        ),
                    ),
                ),
                json = json,
            )
        }

        assertIs<NotAllowedError>(error.domError)
        assertNotNull(error.message)
    }

    @Test
    fun `begin get ignores unknown allow credential type when public key descriptor remains`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"

        val result = parseCredentialProviderBeginGetAllowedCredentialDescriptors(
            requestJson = requestJson(
                allowCredentials = listOf(
                    descriptor(
                        credentialId = "123e4567-e89b-12d3-a456-426614174001",
                        type = "unknown",
                    ),
                    descriptor(credentialId),
                ),
            ),
            json = json,
        )

        val targetCredentials = assertNotNull(result.toPasskeyTargetAllowedCredentials())
        val targetCredential = targetCredentials.single()
        assertEquals("public-key", targetCredential.type)
        assertEquals(credentialId, targetCredential.credentialId)
    }

    @Test
    fun `begin get treats empty allow credentials as discoverable fallback`() {
        val result = parseCredentialProviderBeginGetAllowedCredentialDescriptors(
            requestJson = requestJson(
                allowCredentials = emptyList(),
            ),
            json = json,
        )

        assertNull(result.toPasskeyTargetAllowedCredentials())
    }

    private fun requestJson(
        allowCredentials: List<TestCredentialDescriptor>? = null,
    ): String = buildJsonObject {
        put("challenge", "YQ")
        put("rpId", "example.com")
        if (allowCredentials != null) {
            putJsonArray("allowCredentials") {
                allowCredentials.forEach { descriptor ->
                    addJsonObject {
                        put("type", descriptor.type)
                        put("id", descriptor.idBase64)
                    }
                }
            }
        }
    }.toString()

    private fun descriptor(
        credentialId: String,
        type: String = "public-key",
    ) = TestCredentialDescriptor(
        type = type,
        idBase64 = PasskeyBase64.encodeToString(
            PasskeyCredentialId.encode(credentialId),
        ),
    )

    private data class TestCredentialDescriptor(
        val type: String,
        val idBase64: String,
    )

    private companion object {
        private val json = Json
    }
}
