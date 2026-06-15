package com.artemchep.keyguard.android

import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.webauthn.PasskeyCredentialId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * WebAuthn Level 3 authentication coverage for exact RP ID scoping,
 * allowCredentials descriptor filtering, unknown descriptor types, and
 * discoverable-credential fallback.
 */
class PasskeyProviderGetRequestTest {
    // Spec coverage: Sections 5.1.4.2 and 6.3.3 bind
    // authenticatorGetAssertion to exactly one request RP ID. Section 6.1
    // defines rpIdHash inside authenticator data; Section 6.3.3 signs
    // authenticatorData || clientDataHash.
    @Test
    fun `assertion credential scope accepts exact rp id`() {
        requireCredentialRpIdMatchesRequest(
            credential = credential(
                rpId = "example.com",
            ),
            rpId = "example.com",
        )
    }

    @Test
    fun `assertion credential scope rejects different rp id`() {
        assertFailsWith<IllegalArgumentException> {
            requireCredentialRpIdMatchesRequest(
                credential = credential(
                    rpId = "example.com",
                ),
                rpId = "login.example.com",
            )
        }
    }

    // Spec coverage: Section 5.1.4.2 filters allowCredentials by rpId, id, and
    // type before issuing authenticatorGetAssertion.
    @Test
    fun `assertion request options accept matching allow credential`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"

        requireCredentialAllowedByRequestOptions(
            credential = credential(
                rpId = "example.com",
                credentialId = credentialId,
            ),
            requestJson = requestJson(
                allowCredentials = listOf(
                    descriptor(credentialId),
                ),
            ),
            json = json,
            decodeCredentialId = ::decodeCredentialId,
        )
    }

    // Spec coverage: Section 5.1.9 says JSON parsing issues for encoded
    // BufferSource fields must raise EncodingError before get() proceeds.
    @Test
    fun `assertion request options malformed descriptor id throws encoding dom exception`() {
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            requireCredentialAllowedByRequestOptions(
                credential = credential(
                    rpId = "example.com",
                    credentialId = "123e4567-e89b-12d3-a456-426614174000",
                ),
                requestJson = requestJson(
                    allowCredentials = listOf(
                        TestCredentialDescriptor(
                            type = "public-key",
                            idBase64 = "%%%not-base64%%%",
                        ),
                    ),
                ),
                json = json,
                decodeCredentialId = ::decodeCredentialId,
            )
        }

        assertIs<EncodingError>(error.domError)
        assertNotNull(error.message)
    }

    // Spec coverage: Section 5.1.9 parses every JSON BufferSource value before
    // descriptor type filtering, so one malformed id fails the whole request.
    @Test
    fun `assertion request options malformed descriptor id throws even with valid descriptors`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"

        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            requireCredentialAllowedByRequestOptions(
                credential = credential(
                    rpId = "example.com",
                    credentialId = credentialId,
                ),
                requestJson = requestJson(
                    allowCredentials = listOf(
                        descriptor(credentialId),
                        TestCredentialDescriptor(
                            type = "unknown",
                            idBase64 = "%%%not-base64%%%",
                        ),
                    ),
                ),
                json = json,
                decodeCredentialId = ::decodeCredentialId,
            )
        }

        assertIs<EncodingError>(error.domError)
        assertNotNull(error.message)
    }

    @Test
    fun `assertion request options reject credential missing from allow credentials`() {
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            requireCredentialAllowedByRequestOptions(
                credential = credential(
                    rpId = "example.com",
                    credentialId = "123e4567-e89b-12d3-a456-426614174000",
                ),
                requestJson = requestJson(
                    allowCredentials = listOf(
                        descriptor("123e4567-e89b-12d3-a456-426614174001"),
                    ),
                ),
                json = json,
                decodeCredentialId = ::decodeCredentialId,
            )
        }

        assertIs<NotAllowedError>(error.domError)
        assertNotNull(error.message)
    }

    // Spec coverage: Section 5.8.3 says clients must ignore unknown descriptor
    // types, but if all supplied descriptors are ignored, the request errors
    // instead of behaving like an empty allowCredentials list.
    @Test
    fun `assertion request options reject allow descriptor with different type`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"

        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            requireCredentialAllowedByRequestOptions(
                credential = credential(
                    rpId = "example.com",
                    credentialId = credentialId,
                ),
                requestJson = requestJson(
                    allowCredentials = listOf(
                        descriptor(
                            credentialId = credentialId,
                            type = "password",
                        ),
                    ),
                ),
                json = json,
                decodeCredentialId = ::decodeCredentialId,
            )
        }

        assertIs<NotAllowedError>(error.domError)
        assertNotNull(error.message)
    }

    @Test
    fun `assertion request options ignore unknown descriptor type but keep matching public key descriptor`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"

        requireCredentialAllowedByRequestOptions(
            credential = credential(
                rpId = "example.com",
                credentialId = credentialId,
            ),
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
            decodeCredentialId = ::decodeCredentialId,
        )
    }

    // Spec coverage: Section 5.1.4.2 treats missing or empty allowCredentials
    // as no credential ID filter, but without a supplied server-side credential
    // ID the request can only use discoverable credentials scoped to the RP ID.
    @Test
    fun `assertion request options accept discoverable credential without allow credentials`() {
        requireCredentialAllowedByRequestOptions(
            credential = credential(
                rpId = "example.com",
                discoverable = true,
            ),
            requestJson = requestJson(),
            json = json,
            decodeCredentialId = ::decodeCredentialId,
        )
    }

    @Test
    fun `assertion request options accept discoverable credential with empty allow credentials`() {
        requireCredentialAllowedByRequestOptions(
            credential = credential(
                rpId = "example.com",
                discoverable = true,
            ),
            requestJson = requestJson(
                allowCredentials = emptyList(),
            ),
            json = json,
            decodeCredentialId = ::decodeCredentialId,
        )
    }

    @Test
    fun `assertion request options reject non discoverable credential with empty allow credentials`() {
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            requireCredentialAllowedByRequestOptions(
                credential = credential(
                    rpId = "example.com",
                    discoverable = false,
                ),
                requestJson = requestJson(
                    allowCredentials = emptyList(),
                ),
                json = json,
                decodeCredentialId = ::decodeCredentialId,
            )
        }

        assertIs<NotAllowedError>(error.domError)
        assertNotNull(error.message)
    }

    @Test
    fun `assertion request options reject non discoverable credential without allow credentials`() {
        val error = assertFailsWith<GetPublicKeyCredentialDomException> {
            requireCredentialAllowedByRequestOptions(
                credential = credential(
                    rpId = "example.com",
                    discoverable = false,
                ),
                requestJson = requestJson(),
                json = json,
                decodeCredentialId = ::decodeCredentialId,
            )
        }

        assertIs<NotAllowedError>(error.domError)
        assertNotNull(error.message)
    }

    private fun credential(
        rpId: String,
        credentialId: String = "credential-id",
        discoverable: Boolean = true,
        keyType: String = "public-key",
    ) = DSecret.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = keyType,
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "key-value",
        rpId = rpId,
        rpName = null,
        counter = 0,
        userHandle = "user-handle",
        userName = "user-name",
        userDisplayName = "User Name",
        discoverable = discoverable,
        creationDate = Instant.fromEpochMilliseconds(0),
    )

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
        idBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
            PasskeyCredentialId.encode(credentialId),
        ),
    )

    private fun decodeCredentialId(
        idBase64: String,
    ): ByteArray = Base64.getUrlDecoder().decode(idBase64)

    private data class TestCredentialDescriptor(
        val type: String,
        val idBase64: String,
    )

    private companion object {
        private val json = Json
    }
}
