package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.cipherlink.CipherLinkFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CipherCryptoCipherLinkTest {
    @Test
    fun `encrypt appends link fields after the user fields`() {
        val userField = textField("Note", "hello")
        val encrypted = linkCipher(
            fields = listOf(userField),
            links = listOf(
                BitwardenCipher.Link(TARGET_REMOTE_ID),
                BitwardenCipher.Link(OTHER_REMOTE_ID),
            ),
        ).encryptLinksForTest()

        assertEquals(
            listOf(
                userField,
                linkField(1, TARGET_REMOTE_ID),
                linkField(2, OTHER_REMOTE_ID),
            ),
            encrypted.fields,
        )
    }

    @Test
    fun `encrypt canonicalizes and collapses duplicate links`() {
        val encrypted = linkCipher(
            links = listOf(
                BitwardenCipher.Link(TARGET_REMOTE_ID.uppercase()),
                BitwardenCipher.Link(TARGET_REMOTE_ID),
            ),
        ).encryptLinksForTest()

        assertEquals(
            linkField(1, TARGET_REMOTE_ID),
            encrypted.fields.single(),
        )
        assertEquals(emptyList(), encrypted.links)
    }

    @Test
    fun `encrypt skips a link that can not be canonicalized`() {
        val encrypted = linkCipher(
            links = listOf(
                BitwardenCipher.Link("not-a-uuid"),
                BitwardenCipher.Link(TARGET_REMOTE_ID),
            ),
        ).encryptLinksForTest()

        // The indexes stay contiguous and one-based.
        assertEquals(
            listOf(linkField(1, TARGET_REMOTE_ID)),
            encrypted.fields,
        )
    }

    @Test
    fun `encrypt is not gated on the cipher type`() {
        val types = listOf(
            BitwardenCipher.Type.Login,
            BitwardenCipher.Type.SecureNote,
            BitwardenCipher.Type.Card,
            BitwardenCipher.Type.Identity,
            BitwardenCipher.Type.SshKey,
            BitwardenCipher.Type.GpgKey,
        )
        types.forEach { type ->
            val encrypted = linkCipher(
                type = type,
                links = listOf(BitwardenCipher.Link(TARGET_REMOTE_ID)),
            ).encryptLinksForTest()

            assertEquals(
                linkField(1, TARGET_REMOTE_ID),
                encrypted.fields.single(),
                "expected a link field for the $type cipher",
            )
        }
    }

    @Test
    fun `decrypt consumes the link fields and orders them by index`() {
        val decrypted = linkCipher(
            fields = listOf(
                linkField(3, OTHER_REMOTE_ID),
                linkField(1, TARGET_REMOTE_ID),
            ),
        ).decryptLinksForTest()

        assertEquals(
            listOf(
                BitwardenCipher.Link(TARGET_REMOTE_ID),
                BitwardenCipher.Link(OTHER_REMOTE_ID),
            ),
            decrypted.links,
        )
        assertTrue(decrypted.fields.isEmpty())
    }

    @Test
    fun `decrypt collapses duplicate links to the same target`() {
        val decrypted = linkCipher(
            fields = listOf(
                linkField(1, TARGET_REMOTE_ID),
                linkField(2, TARGET_REMOTE_ID.uppercase()),
            ),
        ).decryptLinksForTest()

        assertEquals(
            listOf(BitwardenCipher.Link(TARGET_REMOTE_ID)),
            decrypted.links,
        )
    }

    @Test
    fun `decrypt leaves an unreadable reserved field as a regular custom field`() {
        val notALink = textField(CipherLinkFields.fieldName(1), "hello")
        val hidden = BitwardenCipher.Field(
            name = CipherLinkFields.fieldName(2),
            value = link(TARGET_REMOTE_ID),
            type = BitwardenCipher.Field.Type.Hidden,
        )
        val linked = BitwardenCipher.Field(
            name = CipherLinkFields.fieldName(3),
            value = link(TARGET_REMOTE_ID),
            type = BitwardenCipher.Field.Type.Text,
            linkedId = BitwardenCipher.Field.LinkedId.Login_Username,
        )
        val zeroIndex = textField(
            name = CipherLinkFields.fieldName(0),
            value = link(TARGET_REMOTE_ID),
        )

        val decrypted = linkCipher(
            fields = listOf(notALink, hidden, linked, zeroIndex),
        ).decryptLinksForTest()

        assertEquals(emptyList(), decrypted.links)
        assertEquals(listOf(notALink, hidden, linked, zeroIndex), decrypted.fields)
    }

    @Test
    fun `decrypt leaves an ordinary field that holds a link value`() {
        val field = textField("Related", link(TARGET_REMOTE_ID))

        val decrypted = linkCipher(fields = listOf(field))
            .decryptLinksForTest()

        assertEquals(emptyList(), decrypted.links)
        assertEquals(listOf(field), decrypted.fields)
    }

    @Test
    fun `encrypt then decrypt is a fixed point`() {
        val original = linkCipher(
            fields = listOf(textField("Note", "hello")),
            links = listOf(
                BitwardenCipher.Link(TARGET_REMOTE_ID),
                BitwardenCipher.Link(OTHER_REMOTE_ID),
            ),
        )

        val roundTripped = original
            .encryptLinksForTest()
            .decryptLinksForTest()

        assertEquals(original.fields, roundTripped.fields)
        assertEquals(original.links, roundTripped.links)
    }
}

private fun link(remoteCipherId: String) = "keyguard://cipher/$remoteCipherId"

private fun linkField(
    index: Int,
    remoteCipherId: String,
) = BitwardenCipher.Field(
    name = CipherLinkFields.fieldName(index),
    value = link(remoteCipherId),
    type = BitwardenCipher.Field.Type.Text,
)

private fun textField(
    name: String,
    value: String,
) = BitwardenCipher.Field(
    name = name,
    value = value,
    type = BitwardenCipher.Field.Type.Text,
)

private fun linkCipher(
    fields: List<BitwardenCipher.Field> = emptyList(),
    links: List<BitwardenCipher.Link> = emptyList(),
    type: BitwardenCipher.Type = BitwardenCipher.Type.Login,
) = BitwardenCipher(
    accountId = "account-1",
    cipherId = "cipher-1",
    revisionDate = LINK_TEST_INSTANT,
    createdDate = LINK_TEST_INSTANT,
    service = BitwardenService(),
    name = "Cipher",
    notes = null,
    favorite = false,
    fields = fields,
    links = links,
    reprompt = BitwardenCipher.RepromptType.None,
    type = type,
)

private fun BitwardenCipher.encryptLinksForTest() =
    transform(
        itemCrypto = identityEncrypt,
        globalCrypto = identityEncrypt,
    )

private fun BitwardenCipher.decryptLinksForTest() =
    transform(
        itemCrypto = identityDecrypt,
        globalCrypto = identityDecrypt,
    )

private const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private const val OTHER_REMOTE_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"

private val LINK_TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
