package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class CipherCryptoSshBookmarkTest {
    @Test
    fun `bookmarks cross the transport as text fields after the user fields`() {
        val cipher = cipher(
            bookmarks = listOf(github, nameless),
            certificate = CERTIFICATE,
        ).copy(fields = listOf(userField))
        val encrypted = cipher.transform(identityEncrypt, identityEncrypt)
        assertEquals(emptyList(), encrypted.sshKey?.bookmarks)
        assertEquals(
            listOf(
                userField,
                text(name = "GitHub", value = "ssh://git@github.com"),
                text(name = null, value = "ssh://example.com"),
                text(name = CERTIFICATE_FIELD_NAME, value = CERTIFICATE),
            ),
            encrypted.fields,
        )

        val decrypted = encrypted.transform(identityDecrypt, identityDecrypt)
        assertEquals(cipher.sshKey, decrypted.sshKey)
        assertEquals(listOf(userField), decrypted.fields)
    }

    @Test
    fun `any ssh text field of an SSH key is a bookmark`() {
        val hidden = BitwardenCipher.Field(
            name = "Secret host",
            value = "ssh://secret.example.com",
            type = BitwardenCipher.Field.Type.Hidden,
        )
        val decrypted = cipher(bookmarks = emptyList())
            .copy(
                fields = listOf(
                    userField,
                    text(name = "Prod", value = " SSH://deploy@prod.example.com:2222 "),
                    hidden,
                ),
            )
            .transform(identityDecrypt, identityDecrypt)
        assertEquals(
            listOf(BitwardenCipher.SshKey.Bookmark(name = "Prod", url = " SSH://deploy@prod.example.com:2222 ")),
            decrypted.sshKey?.bookmarks,
        )
        assertEquals(listOf(userField, hidden), decrypted.fields)
    }

    @Test
    fun `other cipher types keep their ssh text fields`() {
        val field = text(name = "GitHub", value = "ssh://git@github.com")
        val login = cipher(bookmarks = emptyList()).copy(
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

    private val userField = text(name = "Host", value = "example.com")

    private val github = BitwardenCipher.SshKey.Bookmark(name = "GitHub", url = "ssh://git@github.com")

    private val nameless = BitwardenCipher.SshKey.Bookmark(name = null, url = "ssh://example.com")

    private fun text(name: String?, value: String) = BitwardenCipher.Field(
        name = name,
        value = value,
        type = BitwardenCipher.Field.Type.Text,
    )

    private fun cipher(
        bookmarks: List<BitwardenCipher.SshKey.Bookmark>,
        certificate: String? = null,
    ) = BitwardenCipher(
        accountId = "account", cipherId = "ssh", revisionDate = Instant.fromEpochSeconds(0),
        service = BitwardenService(), name = "SSH key", notes = null, favorite = false,
        reprompt = BitwardenCipher.RepromptType.None, type = BitwardenCipher.Type.SshKey,
        sshKey = BitwardenCipher.SshKey(
            privateKey = "private",
            publicKey = "ssh-ed25519 AAAA",
            fingerprint = "SHA256:fingerprint",
            certificate = certificate,
            bookmarks = bookmarks,
        ),
    )

    private companion object {
        const val CERTIFICATE_FIELD_NAME = "keyguard.ssh.certificate"

        const val CERTIFICATE = "ssh-ed25519-cert-v01@openssh.com AAAA user@example.com"
    }
}
