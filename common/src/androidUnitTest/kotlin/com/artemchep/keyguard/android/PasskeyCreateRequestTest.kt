package com.artemchep.keyguard.android

import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotSupportedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskey
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyPublicKeyCredentialDescriptor
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyRelyingParty
import com.artemchep.keyguard.common.service.passkey.entity.CreatePasskeyUser
import com.artemchep.keyguard.common.service.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.common.service.webauthn.pubKeyCredParamsOrDefaults
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

/**
 * WebAuthn Level 3 registration coverage for creation-option JSON,
 * excludeCredentials descriptor matching, malformed base64url IDs, and the
 * InvalidStateError path for duplicate credential sources.
 */
class PasskeyCreateRequestTest {
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
        assertIs<PasskeyGeneratorES256>(
            findPasskeyGeneratorOrNull(
                data = request,
                passkeyGenerators = listOf(PasskeyGeneratorES256()),
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
            findPasskeyGeneratorOrNull(
                data = request,
                passkeyGenerators = listOf(PasskeyGeneratorES256()),
            ),
        )
        val error = assertFailsWith<CreatePublicKeyCredentialDomException> {
            requirePasskeyGenerator(
                data = request,
                passkeyGenerators = listOf(PasskeyGeneratorES256()),
            )
        }

        assertIs<NotSupportedError>(error.domError)
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
            ciphers = listOf(cipher(credential)),
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
            ciphers = listOf(
                cipher(
                    credential(
                        credentialId = credentialId,
                        rpId = "example.com",
                    ),
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
            ciphers = listOf(
                cipher(
                    credential(
                        credentialId = credentialId,
                        rpId = "example.com",
                    ),
                ),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `excluded credential ignores unavailable ciphers`() {
        val credentialId = "123e4567-e89b-12d3-a456-426614174000"
        val request = createRequest(
            excludeCredentials = listOf(
                descriptor(credentialId),
            ),
        )

        val archived = findExcludedPasskeyCredentialOrNull(
            data = request,
            rpId = "example.com",
            ciphers = listOf(
                cipher(
                    credential(
                        credentialId = credentialId,
                        rpId = "example.com",
                    ),
                    archived = true,
                ),
            ),
        )
        val deleted = findExcludedPasskeyCredentialOrNull(
            data = request,
            rpId = "example.com",
            ciphers = listOf(
                cipher(
                    credential(
                        credentialId = credentialId,
                        rpId = "example.com",
                    ),
                    deleted = true,
                ),
            ),
        )

        assertNull(archived)
        assertNull(deleted)
    }

    // Spec coverage: Section 5.1.8 says JSON parsing issues for encoded
    // BufferSource fields must raise EncodingError before the ceremony proceeds.
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

        val error = assertFailsWith<CreatePublicKeyCredentialDomException> {
            findExcludedPasskeyCredentialOrNull(
                data = request,
                rpId = "example.com",
                ciphers = listOf(
                    cipher(
                        credential(
                            credentialId = "123e4567-e89b-12d3-a456-426614174000",
                            rpId = "example.com",
                        ),
                    ),
                ),
            )
        }

        assertIs<EncodingError>(error.domError)
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

        val error = assertFailsWith<CreatePublicKeyCredentialDomException> {
            findExcludedPasskeyCredentialOrNull(
                data = request,
                rpId = "example.com",
                ciphers = listOf(
                    cipher(
                        credential(
                            credentialId = "123e4567-e89b-12d3-a456-426614174000",
                            rpId = "example.com",
                        ),
                    ),
                ),
            )
        }

        assertIs<EncodingError>(error.domError)
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

        val error = assertFailsWith<CreatePublicKeyCredentialDomException> {
            findExcludedPasskeyCredentialOrNull(
                data = request,
                rpId = "example.com",
                ciphers = listOf(
                    cipher(
                        credential(
                            credentialId = "123e4567-e89b-12d3-a456-426614174000",
                            rpId = "example.com",
                        ),
                    ),
                ),
            )
        }

        assertIs<EncodingError>(error.domError)
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

        val error = assertFailsWith<CreatePublicKeyCredentialDomException> {
            requireNoExcludedPasskeyCredential(
                data = request,
                rpId = "example.com",
                ciphers = listOf(
                    cipher(
                        credential(
                            credentialId = credentialId,
                            rpId = "example.com",
                        ),
                    ),
                ),
            )
        }

        assertIs<InvalidStateError>(error.domError)
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
        idBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
            PasskeyCredentialId.encode(credentialId),
        ),
    )

    private fun cipher(
        credential: DSecret.Login.Fido2Credentials,
        archived: Boolean = false,
        deleted: Boolean = false,
    ) = DSecret(
        id = "cipher-1",
        accountId = "account-1",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = TEST_INSTANT,
        createdDate = TEST_INSTANT,
        archivedDate = TEST_INSTANT.takeIf { archived },
        deletedDate = TEST_INSTANT.takeIf { deleted },
        service = BitwardenService(),
        name = "Cipher",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.Login,
        login = DSecret.Login(
            fido2Credentials = listOf(credential),
        ),
        card = null,
        identity = null,
    )

    private fun credential(
        credentialId: String,
        rpId: String,
    ) = DSecret.Login.Fido2Credentials(
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
        creationDate = TEST_INSTANT,
    )

    private companion object {
        private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
        private val json = Json {
            ignoreUnknownKeys = true
        }
    }
}
