package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.test.gpgMetadata
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun `decrypt consumes only the first valid custom icon field`() {
        val secondIconField = customIconField("Question")
        val decrypted = cipher(
            fields = listOf(
                customIconField("Warning"),
                secondIconField,
            ),
        ).decryptForTest()

        assertEquals(KeePassIcon.Warning, decrypted.customIcon)
        assertEquals(listOf(secondIconField), decrypted.fields)
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

    @Test
    fun `encrypt adds gpg custom fields for secure note`() {
        val gpgKey = gpgKey()

        val encrypted = cipher(
            type = BitwardenCipher.Type.GpgKey,
            gpgKey = gpgKey,
        )
            .encryptForTest()

        assertEquals(BitwardenCipher.Type.GpgKey, encrypted.type)
        assertNull(encrypted.gpgKey)
        assertEquals(3, encrypted.fields.size)
        val fields = encrypted.fields.associateBy { it.name }
        assertGpgField(
            fields = fields,
            name = GpgAgentFields.PRIVATE_KEY_ARMORED,
            value = PRIVATE_KEY_ARMORED,
            type = BitwardenCipher.Field.Type.Hidden,
        )
        assertGpgField(
            fields = fields,
            name = GpgAgentFields.PUBLIC_KEY_ARMORED,
            value = PUBLIC_KEY_ARMORED,
            type = BitwardenCipher.Field.Type.Text,
        )
        assertGpgField(
            fields = fields,
            name = GpgAgentFields.FINGERPRINT,
            value = FINGERPRINT,
            type = BitwardenCipher.Field.Type.Text,
        )

        assertNull(fields[GPG_METADATA_FIELD])
    }

    @Test
    fun `encrypt-decrypt preserves public-only gpg custom fields`() {
        val gpgKey = gpgKey().copy(privateKeyArmored = null)

        val encrypted = cipher(
            type = BitwardenCipher.Type.GpgKey,
            gpgKey = gpgKey,
        ).encryptForTest()

        assertEquals(BitwardenCipher.Type.GpgKey, encrypted.type)
        assertNull(encrypted.gpgKey)
        assertNull(encrypted.fields.firstOrNull { it.name == GpgAgentFields.PRIVATE_KEY_ARMORED })
        assertEquals(
            setOf(
                GpgAgentFields.PUBLIC_KEY_ARMORED,
                GpgAgentFields.FINGERPRINT,
            ),
            encrypted.fields.mapNotNull { it.name }.toSet(),
        )

        val decrypted = encrypted.decryptForTest()
        assertEquals(BitwardenCipher.Type.GpgKey, decrypted.type)
        assertEquals(gpgKey.copy(metadata = null), decrypted.gpgKey)
        assertTrue(decrypted.fields.isEmpty())
    }

    @Test
    fun `decrypt converts valid gpg custom fields to typed key`() {
        val gpgKey = gpgKey()

        val decrypted = cipher(fields = gpgFields(gpgKey))
            .decryptForTest()

        assertEquals(BitwardenCipher.Type.GpgKey, decrypted.type)
        assertEquals(gpgKey.copy(metadata = null), decrypted.gpgKey)
        assertEquals(gpgFields(gpgKey).last(), decrypted.fields.single())
    }

    @Test
    fun `decrypt leaves gpg metadata as regular custom field`() {
        val metadataField = BitwardenCipher.Field(
            name = GPG_METADATA_FIELD,
            value = "{not json",
            type = BitwardenCipher.Field.Type.Hidden,
        )

        val decrypted = cipher(
            fields = listOf(
                BitwardenCipher.Field(
                    name = GpgAgentFields.PRIVATE_KEY_ARMORED,
                    value = PRIVATE_KEY_ARMORED,
                    type = BitwardenCipher.Field.Type.Hidden,
                ),
                metadataField,
            ),
        ).decryptForTest()

        assertEquals(BitwardenCipher.Type.GpgKey, decrypted.type)
        assertEquals(
            BitwardenCipher.GpgKey(privateKeyArmored = PRIVATE_KEY_ARMORED),
            decrypted.gpgKey,
        )
        assertEquals(listOf(metadataField), decrypted.fields)
    }

    @Test
    fun `decrypt keeps gpg fields when they do not produce a key`() {
        val emptyPrivateKeyField = BitwardenCipher.Field(
            name = GpgAgentFields.PRIVATE_KEY_ARMORED,
            value = " ",
            type = BitwardenCipher.Field.Type.Hidden,
        )

        val decrypted = cipher(fields = listOf(emptyPrivateKeyField))
            .decryptForTest()

        assertEquals(BitwardenCipher.Type.SecureNote, decrypted.type)
        assertNull(decrypted.gpgKey)
        assertEquals(listOf(emptyPrivateKeyField), decrypted.fields)
    }

    @Test
    fun `decrypt keeps gpg fields on unsupported cipher type`() {
        val privateKeyField = BitwardenCipher.Field(
            name = GpgAgentFields.PRIVATE_KEY_ARMORED,
            value = PRIVATE_KEY_ARMORED,
            type = BitwardenCipher.Field.Type.Hidden,
        )

        val decrypted = cipher(
            type = BitwardenCipher.Type.Login,
            fields = listOf(privateKeyField),
        ).decryptForTest()

        assertEquals(BitwardenCipher.Type.Login, decrypted.type)
        assertNull(decrypted.gpgKey)
        assertEquals(listOf(privateKeyField), decrypted.fields)
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
    gpgKey: BitwardenCipher.GpgKey? = null,
    fields: List<BitwardenCipher.Field> = emptyList(),
    type: BitwardenCipher.Type = BitwardenCipher.Type.SecureNote,
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
    gpgKey = gpgKey,
    reprompt = BitwardenCipher.RepromptType.None,
    type = type,
    secureNote = BitwardenCipher.SecureNote(),
)

private fun assertGpgField(
    fields: Map<String?, BitwardenCipher.Field>,
    name: String,
    value: String,
    type: BitwardenCipher.Field.Type,
) {
    val field = assertNotNull(fields[name])
    assertEquals(value, field.value)
    assertEquals(type, field.type)
}

private fun gpgFields(
    gpgKey: BitwardenCipher.GpgKey,
) = listOf(
    BitwardenCipher.Field(
        name = GpgAgentFields.PRIVATE_KEY_ARMORED,
        value = gpgKey.privateKeyArmored,
        type = BitwardenCipher.Field.Type.Hidden,
    ),
    BitwardenCipher.Field(
        name = GpgAgentFields.PUBLIC_KEY_ARMORED,
        value = gpgKey.publicKeyArmored,
        type = BitwardenCipher.Field.Type.Text,
    ),
    BitwardenCipher.Field(
        name = GpgAgentFields.FINGERPRINT,
        value = gpgKey.fingerprint,
        type = BitwardenCipher.Field.Type.Text,
    ),
    BitwardenCipher.Field(
        name = GPG_METADATA_FIELD,
        value = gpgKey.metadata?.let { Json.encodeToString(it) },
        type = BitwardenCipher.Field.Type.Hidden,
    ),
)

private const val GPG_METADATA_FIELD = "keyguard.gpg.metadata"

private fun gpgKey() = BitwardenCipher.GpgKey(
    privateKeyArmored = PRIVATE_KEY_ARMORED,
    publicKeyArmored = PUBLIC_KEY_ARMORED,
    fingerprint = FINGERPRINT,
    metadata = gpgMetadata(
        GpgAgentKeyMetadataKey(
                keygrip = "keygrip-1",
                fingerprint = FINGERPRINT,
                algorithm = "rsa4096",
                capabilities = setOf("sign", "decrypt"),
        ),
    ),
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

private const val PRIVATE_KEY_ARMORED = "-----BEGIN PGP PRIVATE KEY BLOCK-----"
private const val PUBLIC_KEY_ARMORED = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
private const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
