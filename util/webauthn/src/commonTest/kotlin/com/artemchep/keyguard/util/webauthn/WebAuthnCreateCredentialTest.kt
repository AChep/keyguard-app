package com.artemchep.keyguard.util.webauthn

import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskey
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPublicKeyCredentialDescriptor
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyRelyingParty
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyUser
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * WebAuthn Level 3 registration coverage for creation-option JSON,
 * excludeCredentials descriptor matching, malformed base64url IDs, and the
 * InvalidStateError path for duplicate credential sources.
 */
class WebAuthnCreateCredentialTest {
    // Spec coverage: Section 5.1.8 parseCreationOptionsFromJSON maps JSON
    // base64url strings to BufferSource fields; Section 5.8.3 defines
    // PublicKeyCredentialDescriptor as type/id plus optional transports.
    @Test
    fun `creation options decode excluded credentials`() {
        val request = json.decodeFromString<CreatePasskey>(
            """
            {
              "challenge": "YQ",
              "excludeCredentials": [
                {
                  "type": "public-key",
                  "id": "Y3JlZGVudGlhbA",
                  "transports": ["internal"]
                }
              ],
              "pubKeyCredParams": [
                {"alg": -7, "type": "public-key"}
              ],
              "rp": {"id": "example.com", "name": "Example"},
              "user": {
                "id": "dXNlcg",
                "name": "alice@example.com",
                "displayName": "Alice"
              }
            }
            """.trimIndent(),
        )

        val descriptor = request.excludeCredentials.single()
        assertEquals("public-key", descriptor.type)
        assertEquals("Y3JlZGVudGlhbA", descriptor.idBase64)
        assertEquals(listOf("internal"), descriptor.transports)
    }

    // Spec coverage: Section 5.4 PublicKeyCredentialCreationOptions defaults
    // excludeCredentials to an empty sequence.
    @Test
    fun `creation options default excluded credentials to empty`() {
        val request = createRequest()

        assertEquals(emptyList(), request.excludeCredentials)
    }

    // Spec coverage: WebAuthn create() defaults an empty
    // pkOptions.pubKeyCredParams list to public-key ES256 and RS256 before
    // deciding whether the authenticator supports any allowed algorithm.
    @Test
    fun `empty public key credential params use webauthn default algorithms`() {
        val request = createRequest(
            pubKeyCredParams = emptyList(),
        )

        assertEquals(
            listOf(
                CreatePasskeyPubKeyCredParams(
                    alg = -7.0,
                    type = "public-key",
                ),
                CreatePasskeyPubKeyCredParams(
                    alg = -257.0,
                    type = "public-key",
                ),
            ),
            request.pubKeyCredParamsOrDefaults(),
        )
        assertEquals(
            PasskeySignatureAlgorithm.ES256,
            findPasskeyAlgorithmOrNull(
                data = request,
                supportedAlgorithms = setOf(PasskeySignatureAlgorithm.ES256),
            ),
        )
    }

    // Spec coverage: WebAuthn create() skips unsupported
    // PublicKeyCredentialType entries before considering `alg`; if no
    // pubKeyCredParams pairs remain, the create request fails with
    // NotSupportedError.
    @Test
    fun `unsupported public key credential type with supported alg throws not supported`() {
        val request = createRequest(
            pubKeyCredParams = listOf(
                CreatePasskeyPubKeyCredParams(
                    alg = -7.0,
                    type = "not-public-key",
                ),
            ),
        )

        assertNull(
            findPasskeyAlgorithmOrNull(
                data = request,
                supportedAlgorithms = setOf(PasskeySignatureAlgorithm.ES256),
            ),
        )
        val error = assertFailsWith<WebAuthnException> {
            requirePasskeyAlgorithm(
                data = request,
                supportedAlgorithms = setOf(PasskeySignatureAlgorithm.ES256),
            )
        }

        assertIs<WebAuthnNotSupportedException>(error)
        assertNotNull(error.message)
    }

    // Spec coverage: Section 6.3.2 authenticatorMakeCredential checks
    // excludeCredentialDescriptorList against credential sources for the same
    // RP ID, and Section 5.8.3 requires descriptor matching by type and id.
    @Test
    fun `excluded credential matches same rp id`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"
        val credential = credential(
            credentialId = credentialId,
            rpId = "example.com",
        )
        val request = createRequest(
            excludeCredentials = listOf(
                descriptor(credentialId),
            ),
        )

        val result = findExcludedPasskeyCredentialOrNull(
            data = request,
            rpId = "example.com",
            credentials = listOf(credential),
        )

        assertSame(credential, result)
    }

    @Test
    fun `excluded credential ignores different rp id`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"
        val request = createRequest(
            excludeCredentials = listOf(
                descriptor(credentialId),
            ),
        )

        val result = findExcludedPasskeyCredentialOrNull(
            data = request,
            rpId = "login.example.com",
            credentials = listOf(
                credential(
                    credentialId = credentialId,
                    rpId = "example.com",
                ),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `excluded credential ignores non public key descriptor`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"
        val request = createRequest(
            excludeCredentials = listOf(
                descriptor(
                    credentialId = credentialId,
                    type = "password",
                ),
            ),
        )

        val result = findExcludedPasskeyCredentialOrNull(
            data = request,
            rpId = "example.com",
            credentials = listOf(
                credential(
                    credentialId = credentialId,
                    rpId = "example.com",
                ),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `excluded credential malformed descriptor id throws encoding dom exception`() {
        val request = createRequest(
            excludeCredentials = listOf(
                CreatePasskeyPublicKeyCredentialDescriptor(
                    type = "public-key",
                    idBase64 = "%%%not-base64%%%",
                ),
            ),
        )

        val error = assertFailsWith<WebAuthnException> {
            findExcludedPasskeyCredentialOrNull(
                data = request,
                rpId = "example.com",
                credentials = listOf(
                    credential(
                        credentialId = "123e4567-e89b-12d3-a456-426614174000",
                        rpId = "example.com",
                    ),
                ),
            )
        }

        assertIs<WebAuthnEncodingException>(error)
        assertNotNull(error.message)
    }

    // Spec coverage: Section 5.1.8 treats any incompatible JSON buffer-source
    // value as EncodingError, even if other descriptors are syntactically valid.
    @Test
    fun `excluded credential malformed descriptor id throws even with valid descriptors`() {
        val request = createRequest(
            excludeCredentials = listOf(
                descriptor("123e4567-e89b-12d3-a456-426614174001"),
                CreatePasskeyPublicKeyCredentialDescriptor(
                    type = "public-key",
                    idBase64 = "%%%not-base64%%%",
                ),
            ),
        )

        val error = assertFailsWith<WebAuthnException> {
            findExcludedPasskeyCredentialOrNull(
                data = request,
                rpId = "example.com",
                credentials = listOf(
                    credential(
                        credentialId = "123e4567-e89b-12d3-a456-426614174000",
                        rpId = "example.com",
                    ),
                ),
            )
        }

        assertIs<WebAuthnEncodingException>(error)
        assertNotNull(error.message)
    }

    // Spec coverage: Section 5.1.8 decodes every JSON BufferSource field
    // before unsupported descriptor types can be ignored.
    @Test
    fun `excluded credential malformed non public key descriptor id still throws`() {
        val request = createRequest(
            excludeCredentials = listOf(
                CreatePasskeyPublicKeyCredentialDescriptor(
                    type = "unknown",
                    idBase64 = "%%%not-base64%%%",
                ),
            ),
        )

        val error = assertFailsWith<WebAuthnException> {
            findExcludedPasskeyCredentialOrNull(
                data = request,
                rpId = "example.com",
                credentials = listOf(
                    credential(
                        credentialId = "123e4567-e89b-12d3-a456-426614174000",
                        rpId = "example.com",
                    ),
                ),
            )
        }

        assertIs<WebAuthnEncodingException>(error)
        assertNotNull(error.message)
    }

    // Spec coverage: Section 5.1.3.1 Create Request Exceptions uses
    // InvalidStateError when the authenticator recognizes a matching
    // excludeCredentials descriptor after the user consented to create.
    // Without user consent, NotAllowedError remains possible.
    @Test
    fun `matching excluded credential throws invalid state dom exception`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"
        val request = createRequest(
            excludeCredentials = listOf(
                descriptor(credentialId),
            ),
        )

        val error = assertFailsWith<WebAuthnException> {
            requireNoExcludedPasskeyCredential(
                data = request,
                rpId = "example.com",
                credentials = listOf(
                    credential(
                        credentialId = credentialId,
                        rpId = "example.com",
                    ),
                ),
            )
        }

        assertIs<WebAuthnInvalidStateException>(error)
        assertNotNull(error.message)
    }

    private fun createRequest(
        excludeCredentials: List<CreatePasskeyPublicKeyCredentialDescriptor> = emptyList(),
        pubKeyCredParams: List<CreatePasskeyPubKeyCredParams> = listOf(
            CreatePasskeyPubKeyCredParams(
                alg = -7.0,
                type = "public-key",
            ),
        ),
    ) = CreatePasskey(
        challenge = "YQ",
        excludeCredentials = excludeCredentials,
        pubKeyCredParams = pubKeyCredParams,
        rp = CreatePasskeyRelyingParty(
            id = "example.com",
            name = "Example",
        ),
        user = CreatePasskeyUser(
            id = "dXNlcg",
            name = "alice@example.com",
            displayName = "Alice",
        ),
    )

    private fun descriptor(
        credentialId: String,
        type: String = "public-key",
    ) = CreatePasskeyPublicKeyCredentialDescriptor(
        type = type,
        idBase64 = PasskeyBase64.encodeToString(
            PasskeyCredentialId.encode(credentialId),
        ),
    )

    private fun credential(
        credentialId: String,
        rpId: String,
    ) = WebAuthnCredential(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "private-key",
        rpId = rpId,
        rpName = "Example",
        counter = 0,
        userHandle = "dXNlcg",
        userName = "alice@example.com",
        userDisplayName = "Alice",
        discoverable = true,

    )

    private companion object {

        private val json = Json {
            ignoreUnknownKeys = true
        }
    }
}
