package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfSecretMapper
import com.artemchep.keyguard.common.service.credentialexchange.impl.MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS
import com.artemchep.keyguard.common.service.credentialexchange.impl.MAX_PASSKEY_RP_ID_CHARS
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapCredentialId
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapKey
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapRpId
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapUserHandle
import com.artemchep.keyguard.common.service.webauthn.PasskeyBase64
import com.artemchep.keyguard.crypto.NativePasskeyCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The three passkey binary mappers, and every way `mapSshKey` can refuse a key.
 *
 * `mapCredentialId` and `mapUserHandle` normalize base64 while `mapKey`
 * additionally requires a validated EC P-256 PKCS#8 key. That decides whether
 * a synced passkey survives the export or is skipped.
 */
class CxfExportBinaryNormalizationTest {
    private val fixedDer = byteArrayOf(1, 2, 3, 4, 5, 6)

    private val mapper = CxfSecretMapper(
        sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(fixedDer),
    )

    private data class Case(
        val input: String,
        val expected: String?,
    )

    // region mapCredentialId — the strict one

    private val credentialIdCases = listOf(
        // A UUID becomes its 16 raw bytes.
        Case("e8d88789-e916-e196-3cbd-81dafae71bbc", "6NiHiekW4ZY8vYHa-ucbvA"),
        // Upper case decodes to the same bytes.
        Case("E8D88789-E916-E196-3CBD-81DAFAE71BBC", "6NiHiekW4ZY8vYHa-ucbvA"),
        Case("AAECAwQFBg", "AAECAwQFBg"),
        Case("-w", "-w"),
        // The asymmetry: this mapper decodes with padding ABSENT, so a padded
        // value its two siblings would accept is refused here.
        Case("AAECAwQFBg==", null),
        // The standard alphabet is likewise refused.
        Case("+w==", null),
        Case("###", null),
        Case("{e8d88789-e916-e196-3cbd-81dafae71bbc}", null),
        // Decodes cleanly, to nothing — refused all the same.
        Case("", null),
    )

    @Test
    fun `the credential id mapper rejects padding and the standard alphabet`() {
        credentialIdCases.forEach { case ->
            assertEquals(case.expected, mapCredentialId(case.input), "input: ${case.input}")
        }
    }

    // endregion

    // region mapUserHandle — url-safe only

    private val userHandleCases = listOf(
        Case("AAECAwQFBg", "AAECAwQFBg"),
        // Padding is optional here, unlike the credential id.
        Case("AAECAwQFBg==", "AAECAwQFBg"),
        // But the standard alphabet is not accepted, unlike mapKey.
        Case("+w==", null),
        Case("a/b+", null),
        Case(" AAEC", null),
        Case("###", null),
        Case("", null),
    )

    @Test
    fun `the user handle mapper accepts padding but not the standard alphabet`() {
        userHandleCases.forEach { case ->
            assertEquals(case.expected, mapUserHandle(case.input), "input: ${case.input}")
        }
    }

    @Test
    fun `the user handle mapper enforces the WebAuthn byte limit`() {
        val atLimit = PasskeyBase64.encodeToString(ByteArray(64) { it.toByte() })
        val overLimit = PasskeyBase64.encodeToString(ByteArray(65) { it.toByte() })

        assertEquals(atLimit, mapUserHandle(atLimit))
        assertNull(mapUserHandle(overLimit))
    }

    // endregion

    // region mapKey — the lenient one

    private val keyCases = listOf(
        Case(CXF_TEST_PASSKEY_KEY_URL, CXF_TEST_PASSKEY_KEY_URL),
        Case(CXF_TEST_PASSKEY_KEY_STANDARD, CXF_TEST_PASSKEY_KEY_URL),
        // Decodable bytes are not enough: the value must be EC P-256 PKCS#8.
        Case("AAECAwQFBg", null),
        Case("AAECAwQFBg==", null),
        Case("-w", null),
        Case("+w==", null),
        // A mix of the two is valid in neither.
        Case("+w-_", null),
        Case("A", null),
        Case("###", null),
        Case("", null),
    )

    @Test
    fun `the key mapper accepts both alphabets only for valid P-256 PKCS8`() {
        keyCases.forEach { case ->
            assertEquals(
                case.expected,
                mapKey(case.input, NativePasskeyCrypto),
                "input: ${case.input}",
            )
        }
    }

    @Test
    fun `the rp id mapper rejects only blanks`() {
        // Never trimmed and never case-folded: normalizing here would rewrite
        // stored data on its way out.
        assertNull(mapRpId(""))
        assertNull(mapRpId("   "))
        assertNull(mapRpId("\t"))
        assertEquals("example.com", mapRpId("example.com"))
        assertEquals(" example.com ", mapRpId(" example.com "))
    }

    @Test
    fun `the credential id and rp id mappers refuse a value no real one can be`() {
        // CTAP 2.1 caps a credential id at 1023 bytes and RFC 1035 a domain at
        // 253 characters; both caps sit well above that, so only a payload
        // built to be a payload is refused. The `atLimit` values prove the
        // headroom is real rather than the cap being a round number.
        val idAtLimit = "A".repeat(MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS)
        // Still a multiple of four, so only the cap can refuse it.
        val idOverLimit = "A".repeat(MAX_ENCODED_PASSKEY_CREDENTIAL_ID_CHARS + 4)
        assertEquals(idAtLimit, mapCredentialId(idAtLimit))
        assertNull(mapCredentialId(idOverLimit))

        val rpAtLimit = "a".repeat(MAX_PASSKEY_RP_ID_CHARS)
        assertEquals(rpAtLimit, mapRpId(rpAtLimit))
        assertNull(mapRpId(rpAtLimit + "a"))
    }

    @Test
    fun `a fatal passkey backend failure is not absorbed by the passkey seam`() {
        // The export input is the user's own vault, so a blown heap is the
        // process breaking, not a hostile member: `runCatchingNonFatal` must
        // let it through where it folds an IllegalStateException into a skip.
        assertFailsWith<OutOfMemoryError> {
            CxfSecretMapper(
                passkeyCrypto = ThrowingPasskeyCrypto(OutOfMemoryError("heap")),
                sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(fixedDer),
            ).mapPasskey(cxfFido2Credential())
        }
    }

    // endregion

    // region mapPasskey — the combinations

    private data class PasskeyCase(
        val name: String,
        val credential: DSecret.Login.Fido2Credentials,
        val exported: Boolean,
    )

    private val passkeyCases = listOf(
        PasskeyCase("a canonical passkey", cxfFido2Credential(), exported = true),
        PasskeyCase(
            name = "an absent counter is treated as zero",
            credential = cxfFido2Credential(counter = null),
            exported = true,
        ),
        PasskeyCase(
            // CXF v1.0 §3.3.12: a passkey using a non-zero signature counter
            // MUST be excluded from the export.
            name = "a non-zero counter is excluded by the spec",
            credential = cxfFido2Credential(counter = 1),
            exported = false,
        ),
        PasskeyCase(
            name = "a negative counter is also non-zero",
            credential = cxfFido2Credential(counter = -1),
            exported = false,
        ),
        PasskeyCase(
            name = "an undecodable credential id",
            credential = cxfFido2Credential(credentialId = "###"),
            exported = false,
        ),
        PasskeyCase(
            name = "an undecodable user handle",
            credential = cxfFido2Credential(userHandle = "###"),
            exported = false,
        ),
        PasskeyCase(
            name = "an undecodable key",
            credential = cxfFido2Credential(keyValue = "###"),
            exported = false,
        ),
        PasskeyCase(
            name = "decodable bytes that are not PKCS8",
            credential = cxfFido2Credential(keyValue = "AAECAwQFBg=="),
            exported = false,
        ),
        PasskeyCase(
            name = "metadata that disagrees with the P-256 key",
            credential = cxfFido2Credential(keyCurve = "P-384"),
            exported = false,
        ),
        PasskeyCase(
            name = "all three undecodable",
            credential = cxfFido2Credential(
                credentialId = "###",
                userHandle = "###",
                keyValue = "###",
            ),
            exported = false,
        ),
        // One row per field rather than one all-empty row, so a change that
        // fixes one gate and not the others fails loudly here.
        PasskeyCase(
            name = "an empty credential id",
            credential = cxfFido2Credential(credentialId = ""),
            exported = false,
        ),
        PasskeyCase(
            name = "an empty user handle",
            credential = cxfFido2Credential(userHandle = ""),
            exported = false,
        ),
        PasskeyCase(
            name = "an empty key",
            credential = cxfFido2Credential(keyValue = ""),
            exported = false,
        ),
        PasskeyCase(
            // CXF v1.0 has no way to spell "this credential has no user
            // handle", so a stored `null` is a counted skip rather than an
            // invalid document. The import direction reads `""` the other way
            // round on purpose — see `CxfImportUserHandle`.
            name = "an absent user handle",
            credential = cxfFido2Credential(userHandle = null),
            exported = false,
        ),
        PasskeyCase(
            name = "an empty rp id",
            credential = cxfFido2Credential(rpId = ""),
            exported = false,
        ),
        PasskeyCase(
            name = "a whitespace-only rp id",
            credential = cxfFido2Credential(rpId = "   "),
            exported = false,
        ),
    )

    @Test
    fun `a passkey needs every binary field and a zero counter`() {
        passkeyCases.forEach { case ->
            val passkey = mapper.mapPasskey(case.credential)
            assertEquals(case.exported, passkey != null, case.name)
        }
    }

    @Test
    fun `a non-blank rp id reaches the wire verbatim`() {
        val passkey = mapper.mapPasskey(cxfFido2Credential(rpId = " example.com "))
        assertEquals(" example.com ", passkey?.rpId)
    }

    // endregion

    // region mapSshKey

    private data class SshCase(
        val name: String,
        val privateKey: String?,
        val publicKey: String?,
        val keyType: String?,
    )

    private val sshCases = listOf(
        SshCase("a canonical key", "pem", "ssh-ed25519 AAAA comment", "ssh-ed25519"),
        SshCase(
            // No space at all: `substringBefore(' ')` returns the whole string.
            name = "a public key with no comment",
            privateKey = "pem",
            publicKey = "ssh-ed25519",
            keyType = "ssh-ed25519",
        ),
        SshCase(
            // Trimmed first, so a leading space does not become a blank type.
            name = "a public key with leading whitespace",
            privateKey = "pem",
            publicKey = "  ssh-ed25519 AAAA",
            keyType = "ssh-ed25519",
        ),
        SshCase("no public key", "pem", null, null),
        SshCase("a blank public key", "pem", "   ", null),
        SshCase("a tab-only public key", "pem", "\t", null),
        SshCase("no private key", null, "ssh-ed25519 AAAA", null),
        SshCase("a blank private key", "  ", "ssh-ed25519 AAAA", null),
    )

    @Test
    fun `an ssh key needs a key type and a private key`() {
        sshCases.forEach { case ->
            val credential = mapper.mapSshKey(
                DSecret.SshKey(privateKey = case.privateKey, publicKey = case.publicKey),
            )
            assertEquals(case.keyType, credential?.keyType, case.name)
        }
    }

    @Test
    fun `an unconvertible key is refused even with both halves present`() {
        val nullExporter = CxfSecretMapper(sshKeyPkcs8Exporter = FakeSshKeyPkcs8Exporter(null))
        assertNull(
            nullExporter.mapSshKey(
                DSecret.SshKey(privateKey = "pem", publicKey = "ssh-ed25519 AAAA"),
            ),
        )
    }

    @Test
    fun `the private key buffer is wiped on the success path`() {
        // The mapper owns the array the exporter hands it and must not leave key
        // material behind. `RecordingSshKeyPkcs8Exporter` hands out the same array
        // rather than a copy, which is the only way to observe the wipe.
        val der = byteArrayOf(1, 2, 3, 4, 5, 6)
        val recording = RecordingSshKeyPkcs8Exporter(der)
        val recordingMapper = CxfSecretMapper(sshKeyPkcs8Exporter = recording)
        val credential = recordingMapper.mapSshKey(
            DSecret.SshKey(privateKey = "pem", publicKey = "ssh-ed25519 AAAA"),
        )
        assertNotNull(credential)
        assertContentEquals(ByteArray(6), recording.handedOut, "the DER must be zeroed after use")
    }

    @Test
    fun `the private key exporter is not invoked when the public key is missing`() {
        // Pair validation requires both stored halves. Rejecting before the
        // native seam means no private DER buffer exists for the mapper to wipe.
        val der = byteArrayOf(1, 2, 3, 4, 5, 6)
        val recording = RecordingSshKeyPkcs8Exporter(der)
        val recordingMapper = CxfSecretMapper(sshKeyPkcs8Exporter = recording)
        assertNull(
            recordingMapper.mapSshKey(DSecret.SshKey(privateKey = "pem", publicKey = null)),
        )
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6),
            recording.handedOut,
            "the exporter must not hand private DER to an incomplete pair",
        )
    }

    // endregion
}
