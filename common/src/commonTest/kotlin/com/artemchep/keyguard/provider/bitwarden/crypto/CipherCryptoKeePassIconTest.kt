package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CipherCryptoKeePassIconTest {
    @Test
    fun `encrypt adds custom icon name field for keepass icon`() {
        val encrypted = cipher(keepassIcon = KeePassIcon.Warning)
            .encryptForTest()

        assertEquals(
            BitwardenCipher.Field(
                name = "Custom Icon Name",
                value = "Warning",
                type = BitwardenCipher.Field.Type.Text,
            ),
            encrypted.fields.single(),
        )
    }

    @Test
    fun `decrypt converts valid custom icon name field to keepass icon`() {
        val decrypted = cipher(
            fields = listOf(customIconField("Warning")),
        ).decryptForTest()

        assertEquals(KeePassIcon.Warning, decrypted.customIcon)
        assertTrue(decrypted.fields.isEmpty())
    }

    @Test
    fun `decrypt leaves invalid custom icon name field as regular custom field`() {
        val field = customIconField("NotAnIcon")

        val decrypted = cipher(fields = listOf(field))
            .decryptForTest()

        assertNull(decrypted.customIcon)
        assertEquals(listOf(field), decrypted.fields)
    }

    @Test
    fun `decrypt ignores non-text or linked custom icon name fields`() {
        val hidden = BitwardenCipher.Field(
            name = "Custom Icon Name",
            value = "Warning",
            type = BitwardenCipher.Field.Type.Hidden,
        )
        val linked = BitwardenCipher.Field(
            name = "Custom Icon Name",
            value = "Warning",
            type = BitwardenCipher.Field.Type.Text,
            linkedId = BitwardenCipher.Field.LinkedId.Login_Username,
        )

        val decrypted = cipher(fields = listOf(hidden, linked))
            .decryptForTest()

        assertNull(decrypted.customIcon)
        assertEquals(listOf(hidden, linked), decrypted.fields)
    }

    @Test
    fun `decrypt consumes key custom icon name as default icon`() {
        val decrypted = cipher(
            keepassIcon = KeePassIcon.Warning,
            fields = listOf(customIconField("Key")),
        ).decryptForTest()

        assertNull(decrypted.customIcon)
        assertTrue(decrypted.fields.isEmpty())
    }

    @Test
    fun `encrypt disambiguates custom icon name field collision`() {
        val encrypted = cipher(
            keepassIcon = KeePassIcon.Warning,
            fields = listOf(customIconField("user value")),
        ).encryptForTest()

        assertEquals(
            listOf(
                "Custom Icon Name" to "Warning",
                "Custom Icon Name #1" to "user value",
            ),
            encrypted.fields.map { it.name to it.value },
        )
    }

    @Test
    fun `encrypt disambiguates custom icon name field collision with numbered field`() {
        val encrypted = cipher(
            keepassIcon = KeePassIcon.Warning,
            fields = listOf(
                customIconField("user value"),
                BitwardenCipher.Field(
                    name = "Custom Icon Name #1",
                    value = "existing numbered value",
                    type = BitwardenCipher.Field.Type.Text,
                ),
            ),
        ).encryptForTest()

        assertEquals(
            listOf(
                "Custom Icon Name" to "Warning",
                "Custom Icon Name #2" to "user value",
                "Custom Icon Name #1" to "existing numbered value",
            ),
            encrypted.fields.map { it.name to it.value },
        )
    }

    @Test
    fun `encrypt-decrypt round trip preserves icon and renamed custom field`() {
        val decrypted = cipher(
            keepassIcon = KeePassIcon.Warning,
            fields = listOf(customIconField("user value")),
        )
            .encryptForTest()
            .decryptForTest()

        assertEquals(KeePassIcon.Warning, decrypted.customIcon)
        assertEquals(
            listOf(
                BitwardenCipher.Field(
                    name = "Custom Icon Name #1",
                    value = "user value",
                    type = BitwardenCipher.Field.Type.Text,
                ),
            ),
            decrypted.fields,
        )
    }
}

private fun customIconField(
    value: String,
) = BitwardenCipher.Field(
    name = "Custom Icon Name",
    value = value,
    type = BitwardenCipher.Field.Type.Text,
)

private fun cipher(
    keepassIcon: KeePassIcon? = null,
    fields: List<BitwardenCipher.Field> = emptyList(),
) = BitwardenCipher(
    accountId = "account-1",
    cipherId = "cipher-1",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    service = BitwardenService(),
    name = "Cipher",
    notes = null,
    favorite = false,
    fields = fields,
    customIcon = keepassIcon,
    reprompt = BitwardenCipher.RepromptType.None,
    type = BitwardenCipher.Type.SecureNote,
    secureNote = BitwardenCipher.SecureNote(),
)

private fun BitwardenCipher.encryptForTest() =
    transform(
        itemCrypto = identityEncrypt,
        globalCrypto = identityEncrypt,
    )

private fun BitwardenCipher.decryptForTest() =
    transform(
        itemCrypto = identityDecrypt,
        globalCrypto = identityDecrypt,
    )

private val identityEnv = BitwardenCrCta.BitwardenCrCtaEnv(
    key = BitwardenCrKey.UserToken,
)

private val identityEncrypt = IdentityBitwardenCr.cta(
    env = identityEnv,
    mode = BitwardenCrCta.Mode.ENCRYPT,
)

private val identityDecrypt = IdentityBitwardenCr.cta(
    env = identityEnv,
    mode = BitwardenCrCta.Mode.DECRYPT,
)

private object IdentityBitwardenCr : BitwardenCr {
    override val base64Service: Base64Service = IdentityBase64Service

    override fun decoder(
        key: BitwardenCrKey,
    ): (String) -> DecodeResult = { cipherText ->
        DecodeResult(
            data = cipherText.encodeToByteArray(),
            type = CipherEncryptor.Type.AesCbc256_HmacSha256_B64,
        )
    }

    override fun encoder(
        key: BitwardenCrKey,
    ): (CipherEncryptor.Type, ByteArray) -> String = { _, data ->
        data.decodeToString()
    }

    override fun cta(
        env: BitwardenCrCta.BitwardenCrCtaEnv,
        mode: BitwardenCrCta.Mode,
    ): BitwardenCrCta = BitwardenCrCta(
        crypto = this,
        env = env,
        mode = mode,
    )
}

private object IdentityBase64Service : Base64Service {
    override fun encode(bytes: ByteArray): ByteArray = bytes

    override fun decode(bytes: ByteArray): ByteArray = bytes
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
