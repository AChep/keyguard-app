package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pins the Bitwarden transport fields with hand-written samples. Installed
 * versions read these fields, so a failure here is a compatibility break.
 */
class BitwardenPasskeyFieldCodecTest {
    private val document = """{"version":1,"credentials":[{"credentialId":"cred-1","prfSecret":"c2VjcmV0"}]}"""

    @Test
    fun `sample decodes onto its passkey and encodes to the same field`() {
        val ordinary = BitwardenCipher.Field(name = "ordinary", value = "kept", type = BitwardenCipher.Field.Type.Text)
        val decoded = assertNotNull(
            BitwardenPasskeyFieldCodec.decode(
                fields = listOf(ordinary, hidden(FIELD_NAME, document)),
                credentials = listOf(passkey("cred-1"), passkey("cred-2")),
            ),
        )
        assertEquals(listOf("c2VjcmV0", null), decoded.credentials.map { it.prfSecret })
        assertEquals(listOf(ordinary), decoded.remainingFields)
        assertEquals(
            listOf(hidden(FIELD_NAME, document)),
            BitwardenPasskeyFieldCodec.encode(listOf(secrets("cred-1", "c2VjcmV0")), fields = emptyList()),
        )
    }

    @Test
    fun `a secret without user verification round-trips`() {
        val document = "{\"version\":1,\"credentials\":[{\"credentialId\":\"cred-1\"," +
            "\"prfSecret\":\"c2VjcmV0\",\"prfSecretWithoutUv\":\"b3RoZXI\"}]}"
        val decoded = assertNotNull(
            BitwardenPasskeyFieldCodec.decode(
                fields = listOf(hidden(FIELD_NAME, document)),
                credentials = listOf(passkey("cred-1")),
            ),
        )
        val credential = decoded.credentials.single()
        assertEquals("c2VjcmV0", credential.prfSecret)
        assertEquals("b3RoZXI", credential.prfSecretWithoutUv)
        assertEquals(
            listOf(hidden(FIELD_NAME, document)),
            BitwardenPasskeyFieldCodec.encode(listOf(secrets("cred-1", "c2VjcmV0", "b3RoZXI")), fields = emptyList()),
        )
    }

    @Test
    fun `entries of removed passkeys are dropped`() {
        val decoded = assertNotNull(
            BitwardenPasskeyFieldCodec.decode(
                fields = listOf(hidden(FIELD_NAME, document)),
                credentials = listOf(passkey("cred-2")),
            ),
        )
        assertEquals(listOf(passkey("cred-2")), decoded.credentials)
        assertTrue(decoded.remainingFields.isEmpty())
    }

    @Test
    fun `passkeys without secrets or a credential ID write no fields`() {
        assertTrue(BitwardenPasskeyFieldCodec.encode(emptyList(), fields = emptyList()).isEmpty())
        assertTrue(
            BitwardenPasskeyFieldCodec
                .encode(listOf(secrets("cred-1", null), secrets(null, "c2VjcmV0")), fields = emptyList())
                .isEmpty(),
        )
    }

    @Test
    fun `passkeys without secrets keep those of the existing document`() {
        val existing = "{\"version\":1,\"credentials\":[{\"credentialId\":\"cred-1\",\"prfSecret\":\"b2xk\"}," +
            "{\"credentialId\":\"cred-2\",\"prfSecret\":\"b2xk\"},{\"credentialId\":\"cred-3\",\"prfSecret\":\"b2xk\"}]}"
        val fields = BitwardenPasskeyFieldCodec.encode(
            passkeys = listOf(secrets("cred-1", null), secrets("cred-2", "bmV3")),
            fields = listOf(hidden(FIELD_NAME, existing)),
        )
        // The local secret wins, and the entry of a passkey that is gone is dropped.
        val document = "{\"version\":1,\"credentials\":[{\"credentialId\":\"cred-1\",\"prfSecret\":\"b2xk\"}," +
            "{\"credentialId\":\"cred-2\",\"prfSecret\":\"bmV3\"}]}"
        assertEquals(listOf(hidden(FIELD_NAME, document)), fields)
    }

    @Test
    fun `a large document is chunked`() {
        val secrets = (1..50).map { secrets("credential-$it", "c2VjcmV0".repeat(6)) }
        val fields = BitwardenPasskeyFieldCodec.encode(secrets, fields = emptyList())
        assertEquals(
            listOf(
                gpgChunkPartName(FIELD_NAME, 1),
                gpgChunkPartName(FIELD_NAME, 2),
                gpgChunkHashName(FIELD_NAME),
            ),
            fields.map { it.name },
        )

        val decoded = assertNotNull(
            BitwardenPasskeyFieldCodec.decode(
                fields = fields,
                credentials = secrets.map { passkey(requireNotNull(it.credentialId)) },
            ),
        )
        assertEquals(secrets.map { it.prfSecret }, decoded.credentials.map { it.prfSecret })
    }

    @Test
    fun `damaged or unknown documents are not passkey data`() {
        val credentials = listOf(passkey("cred-1"))
        listOf(
            listOf(hidden(FIELD_NAME, """{"version":2,"credentials":[]}""")),
            listOf(hidden(FIELD_NAME, "not json")),
            listOf(hidden(FIELD_NAME, document).copy(type = BitwardenCipher.Field.Type.Text)),
            listOf(hidden(FIELD_NAME, document), hidden(gpgChunkHashName(FIELD_NAME), "0".repeat(64))),
        ).forEach { fields ->
            assertNull(BitwardenPasskeyFieldCodec.decode(fields, credentials), fields.toString())
        }
    }

    private fun secrets(
        credentialId: String?,
        prfSecret: String?,
        prfSecretWithoutUv: String? = null,
    ) = BitwardenPasskeyFieldCodec.Secrets(
        credentialId = credentialId,
        prfSecret = prfSecret,
        prfSecretWithoutUv = prfSecretWithoutUv,
    )

    private fun hidden(name: String, value: String) = BitwardenCipher.Field(
        name = name,
        value = value,
        type = BitwardenCipher.Field.Type.Hidden,
    )

    private fun passkey(credentialId: String) = BitwardenCipher.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "AQID",
        rpId = "example.com",
        rpName = null,
        counter = "0",
        discoverable = "true",
        creationDate = Instant.fromEpochSeconds(0),
    )

    private companion object {
        // Spelled out independently of the production constant, so that
        // a change of the field name breaks the tests.
        const val FIELD_NAME = "keyguard.passkeys"
    }
}
