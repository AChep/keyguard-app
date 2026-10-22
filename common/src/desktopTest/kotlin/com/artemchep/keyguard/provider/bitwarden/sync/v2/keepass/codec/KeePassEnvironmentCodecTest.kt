package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.feature.environments.EnvironmentDefinition
import com.artemchep.keyguard.feature.environments.editableDefinition
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pins the KDBX representation with hand-written entries. Other KeePass apps
 * and installed versions read these entries, so a failure here is a compatibility break.
 */
@Suppress("TooManyFunctions")
class KeePassEnvironmentCodecTest {
    private val document = """{"version":1,"variables":[""" +
        """{"name":"TOKEN","source":{"kind":"literal","value":"secret"}}]}"""

    @Test
    fun `environment is written as one protected field`() = runTest {
        val codec = createTestCipherCodec()
        val original = environmentCipher(BitwardenCipher.Environment(document = document))
        val encoded = codec.encode(original, null, emptyMap())
        val value = assertIs<EntryValue.Encrypted>(encoded.entry.fields[FIELD])
        assertEquals(document, value.content)
        assertTrue(encoded.entry.fields.keys.none { it.startsWith("keyguard.environment.") })
    }

    @Test
    fun `sample entries decode with or without protection`() = runTest {
        listOf(concealed(document), plain(document)).forEach { value ->
            val decoded = decode(entryOf("Keyguard: Entry Type" to plain("Environment"), FIELD to value))
            assertEquals(BitwardenCipher.Type.Environment, decoded.type)
            assertEquals(BitwardenCipher.Environment(document = document), decoded.environment)
            assertTrue(decoded.fields.none { it.name == FIELD })
        }
    }

    @Test
    fun `a secure note with the field becomes an environment`() = runTest {
        val decoded = decode(entryOf("Keyguard: Entry Type" to plain("Note"), FIELD to concealed(document)))
        assertEquals(BitwardenCipher.Type.Environment, decoded.type)
        assertEquals(document, decoded.environment?.document)
    }

    @Test
    fun `an environment wins over GPG fields, which stay custom fields`() = runTest {
        val codec = createTestCipherCodec()
        val gpgFields = arrayOf(
            KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED to concealed("private"),
            KeePassFieldKey.GPG_PUBLIC_KEY_ARMORED to plain("public"),
        )
        listOf(
            // A secure note.
            entryOf("Keyguard: Entry Type" to plain("Note"), FIELD to concealed(document), *gpgFields),
            // An entry without a type marker.
            entryOf(FIELD to concealed(document), *gpgFields),
        ).forEach { entry ->
            val decoded = decode(entry)
            assertEquals(BitwardenCipher.Type.Environment, decoded.type)
            assertEquals(document, decoded.environment?.document)
            assertNull(decoded.gpgKey)
            assertEquals(
                setOf(
                    BitwardenCipher.Field(
                        name = KeePassFieldKey.GPG_PRIVATE_KEY_ARMORED,
                        value = "private",
                        type = BitwardenCipher.Field.Type.Hidden,
                    ),
                    BitwardenCipher.Field(
                        name = KeePassFieldKey.GPG_PUBLIC_KEY_ARMORED,
                        value = "public",
                        type = BitwardenCipher.Field.Type.Text,
                    ),
                ),
                decoded.fields.toSet(),
            )

            // The GPG fields are written back as they were read.
            val written = codec.encode(decoded, entry, emptyMap()).entry.fields
            assertEquals(document, written[FIELD]?.content)
            gpgFields.forEach { (key, value) ->
                assertEquals(value.content, written[key]?.content)
                assertEquals(value.isConcealed(), written[key]?.isConcealed())
            }
        }
    }

    @Test
    fun `entries keep their value after a write`() = runTest {
        val codec = createTestCipherCodec()
        listOf(
            // A document of an unknown version.
            concealed("""{"version":2,"future":true}"""),
            // Values that other custom fields would read as a boolean or a linked field.
            plain("true"),
            plain("linkedId|Password|value"),
        ).forEach { value ->
            val entry = entryOf("Keyguard: Entry Type" to plain("Environment"), FIELD to value)
            val decoded = decode(entry)
            assertEquals(BitwardenCipher.Environment(document = value.content), decoded.environment)
            val encoded = codec.encode(decoded, entry, emptyMap())
            assertEquals(value.content, encoded.entry.fields[FIELD]?.content)
            assertEquals(true, encoded.entry.fields[FIELD]?.isConcealed())
        }
    }

    @Test
    fun `an entry without a type marker but with a document becomes an environment`() = runTest {
        val decoded = decode(entryOf("Title" to plain("Environment"), FIELD to concealed(document)))
        assertEquals(BitwardenCipher.Type.Environment, decoded.type)
        assertEquals(BitwardenCipher.Environment(document = document), decoded.environment)
        assertTrue(decoded.fields.none { it.name == FIELD })
    }

    @Test
    fun `an entry without a type marker keeps a field that is not a document`() = runTest {
        val value = """{"mine":true}"""
        val decoded = decode(entryOf("Title" to plain("Login"), FIELD to plain(value)))
        assertEquals(BitwardenCipher.Type.Login, decoded.type)
        assertNull(decoded.environment)
        assertEquals(listOf(value), decoded.fields.filter { it.name == FIELD }.map { it.value })
    }

    @Test
    fun `a secure note with a field that is not a document stays a secure note`() = runTest {
        val value = """{"mine":true}"""
        val decoded = decode(entryOf("Keyguard: Entry Type" to plain("Note"), FIELD to plain(value)))
        assertEquals(BitwardenCipher.Type.SecureNote, decoded.type)
        assertNull(decoded.environment)
        assertEquals(listOf(value), decoded.fields.filter { it.name == FIELD }.map { it.value })
    }

    @Test
    fun `a custom field with the same name does not replace the document`() = runTest {
        val codec = createTestCipherCodec()
        val own = BitwardenCipher.Field(name = FIELD, value = "mine", type = BitwardenCipher.Field.Type.Text)
        val original = environmentCipher(BitwardenCipher.Environment(document = document)).copy(fields = listOf(own))
        val encoded = codec.encode(original, null, emptyMap())
        assertEquals(document, encoded.entry.fields[FIELD]?.content)
        assertEquals("mine", encoded.entry.fields["$FIELD #1"]?.content)
    }

    @Test
    fun `an entry type without a document is an empty environment with nothing to write`() = runTest {
        val codec = createTestCipherCodec()
        listOf(
            entryOf("Keyguard: Entry Type" to plain("Environment")),
            // Other KeePass apps may clear the field instead of removing it.
            entryOf("Keyguard: Entry Type" to plain("Environment"), FIELD to concealed("")),
            entryOf("Keyguard: Entry Type" to plain("Environment"), FIELD to plain(" ")),
        ).forEach { entry ->
            val decoded = decode(entry)
            assertEquals(BitwardenCipher.Type.Environment, decoded.type)
            assertEquals(BitwardenCipher.Environment(), decoded.environment)
            assertEquals(EnvironmentDefinition(), decoded.environment?.toDomain().editableDefinition())
            assertTrue(decoded.fields.none { it.name == FIELD })
            assertNull(codec.encode(decoded, entry, emptyMap()).entry.fields[FIELD])
        }
    }

    private suspend fun decode(entry: Entry) = createTestCipherCodec().decode(
        accountId = "account", folderId = null, cipherId = "local-id",
        remote = entry, local = null, revisionDate = Instant.fromEpochSeconds(0), binaries = emptyMap(),
    )

    private fun environmentCipher(environment: BitwardenCipher.Environment) =
        testBitwardenCipher(cipherId = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12").copy(
            type = BitwardenCipher.Type.Environment, login = null,
            secureNote = BitwardenCipher.SecureNote(), environment = environment,
        )

    private fun entryOf(
        vararg fields: Pair<String, EntryValue>,
    ) = Entry(
        uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        fields = EntryFields.of(*fields),
    )

    private fun plain(content: String) = EntryValue.Plain(content)

    private fun concealed(content: String) = EntryValue.Encrypted(EncryptedValue.fromString(content))

    private companion object {
        const val FIELD = "environment_json"
    }
}
