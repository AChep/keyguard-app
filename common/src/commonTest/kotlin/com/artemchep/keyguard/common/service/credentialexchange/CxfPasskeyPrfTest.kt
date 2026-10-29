package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfExportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfImportServiceImpl
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfSecretMapper
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfFido2HmacCredentials
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The PRF seeds of CXF v1.0 §3.3.12.3. Keyguard's PRF secret is `credWithUV`,
 * and the `credWithoutUV` it has no use for is derived on export, so other
 * apps that import the same passkey twice get the same seeds.
 */
class CxfPasskeyPrfTest {
    private val now = Instant.parse("2024-01-30T14:09:33Z")

    private val mapper = CxfSecretMapper(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3)),
    )

    private val exportService = CxfExportServiceImpl(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(byteArrayOf(1, 2, 3)),
    )

    private val importService = CxfImportServiceImpl(
        sshKeyImportService = FakeSshKeyImportService(),
    )

    // region Export

    @Test
    fun `export derives the seed without user verification`() {
        val passkey = mapper.mapPasskey(cxfFido2Credential().copy(prfSecret = SECRET))

        assertEquals(
            CxfFido2HmacCredentials(
                algorithm = "hmac-sha256",
                credWithUv = SECRET,
                credWithoutUv = DERIVED_SECRET_WITHOUT_UV,
            ),
            passkey?.fido2Extensions?.hmacCredentials,
        )
    }

    @Test
    fun `export keeps an imported seed without user verification`() {
        val credential = cxfFido2Credential().copy(
            prfSecret = SECRET,
            prfSecretWithoutUv = OTHER_SECRET,
        )
        val passkey = mapper.mapPasskey(credential)

        assertEquals(OTHER_SECRET, passkey?.fido2Extensions?.hmacCredentials?.credWithoutUv)
    }

    @Test
    fun `export writes a standard base64 secret as base64url`() {
        val passkey = mapper.mapPasskey(cxfFido2Credential().copy(prfSecret = "+/+/"))

        assertEquals("-_-_", passkey?.fido2Extensions?.hmacCredentials?.credWithUv)
    }

    @Test
    fun `export of a passkey without a PRF secret has no extensions`() {
        val passkey = mapper.mapPasskey(cxfFido2Credential())

        assertNotNull(passkey)
        assertNull(passkey.fido2Extensions)
    }

    @Test
    fun `export skips a passkey whose PRF secrets cannot travel`() {
        listOf(
            "" to null,
            "not base64!" to null,
            TOO_LARGE_SECRET to null,
            SECRET to "not base64!",
            SECRET to TOO_LARGE_SECRET,
            null to OTHER_SECRET,
        ).forEach { (prfSecret, prfSecretWithoutUv) ->
            val credential = cxfFido2Credential().copy(
                prfSecret = prfSecret,
                prfSecretWithoutUv = prfSecretWithoutUv,
            )

            assertNull(mapper.mapPasskey(credential), "$prfSecret, $prfSecretWithoutUv")
        }
    }

    @Test
    fun `export uses the member names of the format`() {
        val passkey = mapper.mapPasskey(cxfFido2Credential().copy(prfSecret = SECRET))
        val json = Json { explicitNulls = false }
            .encodeToJsonElement(CxfCredential.serializer(), assertNotNull(passkey))
            .jsonObject

        assertEquals(
            JsonObject(
                mapOf(
                    "hmacCredentials" to JsonObject(
                        mapOf(
                            "algorithm" to JsonPrimitive("hmac-sha256"),
                            "credWithUV" to JsonPrimitive(SECRET),
                            "credWithoutUV" to JsonPrimitive(DERIVED_SECRET_WITHOUT_UV),
                        ),
                    ),
                ),
            ),
            json["fido2Extensions"],
        )
    }

    // endregion

    // region Import

    @Test
    fun `import keeps both seeds`() {
        val passkey = importPasskey(hmacCredentials(credWithoutUv = OTHER_SECRET))

        assertEquals(SECRET, passkey.prfSecret)
        assertEquals(OTHER_SECRET, passkey.prfSecretWithoutUv)
    }

    @Test
    fun `import does not store the derived seed`() {
        val passkey = importPasskey(hmacCredentials(credWithoutUv = DERIVED_SECRET_WITHOUT_UV))

        assertEquals(SECRET, passkey.prfSecret)
        assertNull(passkey.prfSecretWithoutUv)
    }

    @Test
    fun `import keeps the seed with user verification without the other`() {
        val passkey = importPasskey("""{"algorithm": "hmac-sha256", "credWithUV": "$SECRET"}""")

        assertEquals(SECRET, passkey.prfSecret)
        assertNull(passkey.prfSecretWithoutUv)
    }

    @Test
    fun `import ignores an entry of another algorithm, but keeps the passkey`() {
        listOf(
            hmacCredentials(algorithm = "hmac-sha512"),
            """{"credWithUV": "$SECRET", "credWithoutUV": "$OTHER_SECRET"}""",
        ).forEach { hmacCredentials ->
            val passkey = importPasskey(hmacCredentials)

            assertNull(passkey.prfSecret, hmacCredentials)
            assertNull(passkey.prfSecretWithoutUv, hmacCredentials)
        }
    }

    @Test
    fun `import skips a passkey whose PRF seeds cannot be stored`() {
        listOf(
            hmacCredentials(credWithUv = ""),
            hmacCredentials(credWithUv = "not base64!"),
            hmacCredentials(credWithUv = TOO_LARGE_SECRET),
            hmacCredentials(credWithoutUv = ""),
            hmacCredentials(credWithoutUv = "not base64!"),
            hmacCredentials(credWithoutUv = TOO_LARGE_SECRET),
            """{"algorithm": "hmac-sha256", "credWithoutUV": "$OTHER_SECRET"}""",
        ).forEach { hmacCredentials ->
            val plan = importPlan(hmacCredentials)

            assertEquals(cxfImportSkips(CxfImportSkipReason.Passkey to 1), plan.skips, hmacCredentials)
            assertTrue(plan.items.isEmpty(), hmacCredentials)
        }
    }

    // endregion

    @Test
    fun `seeds survive a round trip`() {
        listOf(null, OTHER_SECRET).forEach { prfSecretWithoutUv ->
            val credential = cxfFido2Credential().copy(
                prfSecret = SECRET,
                prfSecretWithoutUv = prfSecretWithoutUv,
            )
            val passkey = roundTrip(credential)

            assertEquals(SECRET, passkey.prfSecret)
            assertEquals(prfSecretWithoutUv, passkey.prfSecretWithoutUv)
        }
    }

    @Test
    fun `seeds longer than a SHA256 block survive a round trip`() {
        listOf(65, 128, 64 * 1024).forEach { size ->
            val seed = PasskeyBase64.encodeToString(ByteArray(size) { it.toByte() })
            listOf(seed to null, SECRET to seed, seed to OTHER_SECRET).forEach { (withUv, withoutUv) ->
                val credential = cxfFido2Credential().copy(
                    prfSecret = withUv,
                    prfSecretWithoutUv = withoutUv,
                )
                val passkey = roundTrip(credential)

                assertEquals(withUv, passkey.prfSecret)
                assertEquals(withoutUv, passkey.prfSecretWithoutUv)
            }
        }
    }

    private fun hmacCredentials(
        algorithm: String = "hmac-sha256",
        credWithUv: String = SECRET,
        credWithoutUv: String = OTHER_SECRET,
    ) = """{"algorithm": "$algorithm", "credWithUV": "$credWithUv", "credWithoutUV": "$credWithoutUv"}"""

    private fun importPasskey(
        hmacCredentials: String,
    ): DSecret.Login.Fido2Credentials {
        val plan = importPlan(hmacCredentials)
        assertEquals(cxfImportSkips(), plan.skips)
        return plan.items.single().request.fido2Credentials.single()
    }

    private fun importPlan(
        hmacCredentials: String,
    ): CxfImportPlan {
        val credential = cxfPasskeyCredentialJson()
            .removeSuffix("}")
            .plus(""", "fido2Extensions": {"hmacCredentials": $hmacCredentials}}""")
        return importService.parseSuccessPlan(
            payload = documentWithItems("""{"id": "aXRlbTAx", "title": "Passkey", "credentials": [$credential]}"""),
            now = now,
        )
    }

    private fun roundTrip(
        credential: DSecret.Login.Fido2Credentials,
    ): DSecret.Login.Fido2Credentials {
        val account = exportService.buildAccount(
            profile = cxfProfile(),
            ciphers = listOf(cxfLoginSecret(login = DSecret.Login(fido2Credentials = listOf(credential)))),
            allowedTypes = CxfCredentialType.ALL,
        )
        val document = exportService.buildDocument(
            accounts = listOfNotNull(account),
            exporterRpId = "com.artemchep.keyguard",
            exporterDisplayName = "Keyguard",
            timestamp = now,
        )
        val plan = importService.parseSuccessPlan(
            payload = exportService.encode(document),
            now = now,
        )
        return plan.items.single().request.fido2Credentials.single()
    }

    private companion object {
        // The W3C test vector secret of the PRF extension.
        const val SECRET = "Q34GXnI6mLLwjznYuvfFPss8NjxeUQS9qvXVyi4CgVQ"

        // HMAC-SHA-256(SECRET, "credWithoutUV"), computed independently. Other
        // apps hold exported seeds, so a change here breaks them.
        const val DERIVED_SECRET_WITHOUT_UV = "wLuomBhnTDwJHe8FLPKyU1iCjr1bMQQ0XPwVGV0iVXk"

        const val OTHER_SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

        // One byte over the 64 KiB cap.
        val TOO_LARGE_SECRET = PasskeyBase64.encodeToString(ByteArray(64 * 1024 + 1))
    }
}
