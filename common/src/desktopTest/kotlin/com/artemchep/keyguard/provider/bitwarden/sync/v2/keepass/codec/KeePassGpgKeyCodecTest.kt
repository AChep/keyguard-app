package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.test.gpgMetadata
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class KeePassGpgKeyCodecTest {
    private val codec = KeePassGpgKeyCodec()

    @Test
    fun `encode writes gpg fields with concealment`() {
        val writes = codec.encode(gpgKey())

        assertWrite(
            writes = writes,
            key = KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED,
            content = PRIVATE_KEY_ARMORED,
            concealed = true,
        )
        assertWrite(
            writes = writes,
            key = KeePassFieldKey.GPG_PUBLIC_KEY_ARMORED,
            content = PUBLIC_KEY_ARMORED,
            concealed = false,
        )
        assertWrite(
            writes = writes,
            key = KeePassFieldKey.GPG_FINGERPRINT,
            content = FINGERPRINT,
            concealed = false,
        )
        assertNull(writes.firstOrNull { it.key == GPG_METADATA })
    }

    @Test
    fun `decode consumes canonical gpg fields`() {
        val decoded = decode(
            KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED to concealed(PRIVATE_KEY_ARMORED),
            KeePassFieldKey.GPG_PUBLIC_KEY_ARMORED to plain(PUBLIC_KEY_ARMORED),
            KeePassFieldKey.GPG_FINGERPRINT to plain(FINGERPRINT),
            GPG_METADATA to concealed("""{"keys":[]}"""),
        )

        assertEquals(gpgKey().copy(metadata = null), decoded.gpgKey)
        assertEquals("""{"keys":[]}""", decoded.availableFields[GPG_METADATA]?.content)
    }

    @Test
    fun `encode-decode preserves public-only gpg key`() {
        val gpgKey = gpgKey().copy(privateKeyArmored = null)

        val writes = codec.encode(gpgKey)
        assertNull(writes.firstOrNull { it.key == KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED })
        assertWrite(
            writes = writes,
            key = KeePassFieldKey.GPG_PUBLIC_KEY_ARMORED,
            content = PUBLIC_KEY_ARMORED,
            concealed = false,
        )
        assertWrite(
            writes = writes,
            key = KeePassFieldKey.GPG_FINGERPRINT,
            content = FINGERPRINT,
            concealed = false,
        )
        assertNull(writes.firstOrNull { it.key == GPG_METADATA })

        val decoded = decode(
            *writes.map { it.key to it.value }.toTypedArray(),
        )
        assertEquals(gpgKey.copy(metadata = null), decoded.gpgKey)
        assertTrue(decoded.availableFields.isEmpty())
    }

    @Test
    fun `decode ignores metadata as available field`() {
        val decoded = decode(
            KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED to concealed(PRIVATE_KEY_ARMORED),
            GPG_METADATA to concealed("{not json"),
        )

        assertEquals(
            BitwardenCipher.GpgKey(privateKeyArmored = PRIVATE_KEY_ARMORED),
            decoded.gpgKey,
        )
        assertEquals("{not json", decoded.availableFields[GPG_METADATA]?.content)
    }

    private fun decode(
        vararg fields: Pair<String, EntryValue>,
    ): DecodeResult {
        val entry = entryOf(*fields)
        val scope = DecodeToCipherScope(entry)
        return DecodeResult(
            gpgKey = codec.decode(scope),
            availableFields = scope.getAvailableFields(),
        )
    }

    private fun assertWrite(
        writes: List<KeePassFieldWrite>,
        key: String,
        content: String,
        concealed: Boolean,
    ) {
        val value = assertNotNull(writes.firstOrNull { it.key == key }?.value)
        assertEquals(content, value.content)
        assertEquals(concealed, value is EntryValue.Encrypted)
    }

    private data class DecodeResult(
        val gpgKey: BitwardenCipher.GpgKey?,
        val availableFields: Map<String, EntryValue>,
    )
}

private fun entryOf(
    vararg fields: Pair<String, EntryValue>,
) = Entry(
    uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
    fields = EntryFields.of(*fields),
)

private fun plain(
    content: String,
) = EntryValue.Plain(content)

private fun concealed(
    content: String,
) = EntryValue.Encrypted(EncryptedValue.fromString(content))

private fun gpgKey() = BitwardenCipher.GpgKey(
    privateKeyArmored = PRIVATE_KEY_ARMORED,
    publicKeyArmored = PUBLIC_KEY_ARMORED,
    fingerprint = FINGERPRINT,
    metadata = metadata(),
)

private fun metadata() = gpgMetadata(
    GpgAgentKeyMetadataKey(
            keygrip = "keygrip-1",
            fingerprint = FINGERPRINT,
            algorithm = "rsa4096",
            capabilities = setOf("sign", "decrypt"),
    ),
)

private const val PRIVATE_KEY_ARMORED = "-----BEGIN PGP PRIVATE KEY BLOCK-----"
private const val PUBLIC_KEY_ARMORED = "-----BEGIN PGP PUBLIC KEY BLOCK-----"
private const val FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"
private const val GPG_METADATA = "gpg_metadata"
