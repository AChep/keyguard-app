package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CipherCryptoSshCertificateTest {
    @Test
    fun `certificate crosses the transport as a custom field after the user fields`() {
        val cipher = cipher(CERTIFICATE).copy(fields = listOf(userField))
        val encrypted = cipher.transform(identityEncrypt, identityEncrypt)
        assertNull(encrypted.sshKey?.certificate)
        assertEquals(
            listOf(
                userField,
                BitwardenCipher.Field(
                    name = FIELD_NAME,
                    value = CERTIFICATE,
                    type = BitwardenCipher.Field.Type.Text,
                ),
            ),
            encrypted.fields,
        )

        val decrypted = encrypted.transform(identityDecrypt, identityDecrypt)
        assertEquals(cipher.sshKey, decrypted.sshKey)
        assertEquals(listOf(userField), decrypted.fields)
    }

    @Test
    fun `a certificate over the field limit is chunked`() {
        val certificate = "ssh-ed25519-cert-v01@openssh.com " + "A".repeat(GPG_CHUNK_BYTES * 2)
        val encrypted = cipher(certificate).transform(identityEncrypt, identityEncrypt)
        assertEquals(
            listOf(
                gpgChunkPartName(FIELD_NAME, 1),
                gpgChunkPartName(FIELD_NAME, 2),
                gpgChunkPartName(FIELD_NAME, 3),
                gpgChunkHashName(FIELD_NAME),
            ),
            encrypted.fields.map { it.name },
        )

        val decrypted = encrypted.transform(identityDecrypt, identityDecrypt)
        assertEquals(certificate, decrypted.sshKey?.certificate)
        assertTrue(decrypted.fields.isEmpty())
    }

    @Test
    fun `a key without a certificate writes no field`() {
        val encrypted = cipher(certificate = null).transform(identityEncrypt, identityEncrypt)
        assertTrue(encrypted.fields.isEmpty())
        val decrypted = encrypted.transform(identityDecrypt, identityDecrypt)
        assertNull(decrypted.sshKey?.certificate)
    }

    @Test
    fun `an edited certificate replaces the old fields`() {
        val stale = BitwardenCipher.Field(
            name = FIELD_NAME,
            value = "stale",
            type = BitwardenCipher.Field.Type.Text,
        )
        val encrypted = cipher(CERTIFICATE).copy(fields = listOf(stale))
            .transform(identityEncrypt, identityEncrypt)
        assertEquals(listOf(CERTIFICATE), encrypted.fields.map { it.value })
    }

    @Test
    fun `a malformed chunk group stays custom fields`() {
        val certificate = "ssh-ed25519-cert-v01@openssh.com " + "A".repeat(GPG_CHUNK_BYTES * 2)
        val encrypted = cipher(certificate).transform(identityEncrypt, identityEncrypt)
        val broken = encrypted.fields.dropLast(1)
        val decrypted = encrypted.copy(fields = broken)
            .transform(identityDecrypt, identityDecrypt)
        assertNull(decrypted.sshKey?.certificate)
        assertEquals(broken, decrypted.fields)
    }

    @Test
    fun `a field of another kind under the certificate name stays a custom field`() {
        val hidden = BitwardenCipher.Field(
            name = FIELD_NAME,
            value = CERTIFICATE,
            type = BitwardenCipher.Field.Type.Hidden,
        )
        val decrypted = cipher(certificate = null).copy(fields = listOf(hidden))
            .transform(identityDecrypt, identityDecrypt)
        assertNull(decrypted.sshKey?.certificate)
        assertEquals(listOf(hidden), decrypted.fields)
    }

    @Test
    fun `other cipher types neither write nor read certificate fields`() {
        val field = BitwardenCipher.Field(
            name = FIELD_NAME,
            value = CERTIFICATE,
            type = BitwardenCipher.Field.Type.Text,
        )
        val login = cipher(CERTIFICATE).copy(
            type = BitwardenCipher.Type.Login,
            login = BitwardenCipher.Login(uris = emptyList()),
            sshKey = null,
            fields = listOf(field),
        )
        val decrypted = login
            .transform(identityEncrypt, identityEncrypt)
            .transform(identityDecrypt, identityDecrypt)
        assertNull(decrypted.sshKey)
        assertEquals(listOf(field), decrypted.fields)
    }

    private val userField = BitwardenCipher.Field(
        name = "Host",
        value = "example.com",
        type = BitwardenCipher.Field.Type.Text,
    )

    private fun cipher(certificate: String?) = BitwardenCipher(
        accountId = "account", cipherId = "ssh", revisionDate = Instant.fromEpochSeconds(0),
        service = BitwardenService(), name = "SSH key", notes = null, favorite = false,
        reprompt = BitwardenCipher.RepromptType.None, type = BitwardenCipher.Type.SshKey,
        sshKey = BitwardenCipher.SshKey(
            privateKey = "private",
            publicKey = "ssh-ed25519 AAAA",
            fingerprint = "SHA256:fingerprint",
            certificate = certificate,
        ),
    )

    private companion object {
        // Spelled out independently of the production constant, so that
        // a change of the field name breaks the tests.
        const val FIELD_NAME = "keyguard.ssh.certificate"

        const val CERTIFICATE = "ssh-ed25519-cert-v01@openssh.com AAAA user@example.com"
    }
}
