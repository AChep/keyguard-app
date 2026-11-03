package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class KeePassSshKeyCodecTest {
    private val codec = KeePassSshKeyCodec()

    @Test
    fun `encode writes the certificate as plain text`() {
        val writes = codec.encode(sshKey())

        val value = assertNotNull(writes.firstOrNull { it.key == "ssh_certificate" }?.value)
        assertEquals(CERTIFICATE, value.content)
        assertTrue(value is EntryValue.Plain)
    }

    @Test
    fun `encode skips a missing certificate`() {
        val writes = codec.encode(sshKey().copy(certificate = null))

        assertNull(writes.firstOrNull { it.key == "ssh_certificate" })
    }

    @Test
    fun `encode-decode preserves the key and its certificate`() {
        val writes = codec.encode(sshKey())
        val entry = Entry(
            uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            fields = EntryFields.of(*writes.map { it.key to it.value }.toTypedArray()),
        )
        val scope = DecodeToCipherScope(entry)

        assertEquals(sshKey(), codec.decode(scope))
        assertTrue(scope.getAvailableFields().isEmpty())
        assertTrue(codec.detects(entry))
    }

    @Test
    fun `a certificate alone selects the SSH key parser`() {
        val entry = Entry(
            uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            fields = EntryFields.of(
                "ssh_certificate" to EntryValue.Plain(CERTIFICATE),
                "Other" to EntryValue.Encrypted(EncryptedValue.fromString("secret")),
            ),
        )

        assertTrue(codec.detects(entry))
        assertEquals(
            BitwardenCipher.SshKey(certificate = CERTIFICATE),
            codec.decode(DecodeToCipherScope(entry)),
        )
    }

    private fun sshKey() = BitwardenCipher.SshKey(
        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----",
        publicKey = "ssh-ed25519 AAAA",
        fingerprint = "SHA256:fingerprint",
        certificate = CERTIFICATE,
    )

    private companion object {
        const val CERTIFICATE = "ssh-ed25519-cert-v01@openssh.com AAAA user@example.com"
    }
}
