package app.keemobile.kotpass.database

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import kotlinx.io.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class Xml10LineEndingRoundTripTest {
    private val value = "before\u0085middle\u2028after"
    private val credentials = Credentials.from(EncryptedValue.fromString("xml-line-ending-test"))

    @Test
    fun binaryRoundTripsPreservePlainAndInnerEncryptedValues() {
        for (version in listOf(3, 4)) {
            for (compression in DatabaseHeader.Compression.entries) {
                val original = database(version, compression)
                val encoded = Buffer()
                original.encodeTo(encoded)
                val decoded = KeePassDatabase.decode(encoded, credentials)
                val fields = decoded.content.group.entries.single().fields
                val label = "KDBX $version, $compression"

                assertIs<EntryValue.Encrypted>(fields.password, label)
                assertEquals(value, fields.password?.content, "Protected password: $label")
                assertEquals(value, fields.notes?.content, "Plain notes: $label")
                assertEquals(value, decoded.content.group.name, "Group name: $label")
            }
        }
    }

    @Test
    fun xmlExportRoundTripsPreservePlainAndProtectInMemoryValues() {
        val original = database(4, DatabaseHeader.Compression.None)
        val xml = original.encodeAsXml()
        assertTrue(xml.contains("version=\"1.0\"") || xml.contains("version='1.0'"))
        assertTrue(xml.contains("ProtectInMemory=\"True\""))
        assertTrue(xml.contains(value), "The XML must contain literal NEL and line separator")

        val decoded = KeePassDatabase.decodeFromXml(xml.encodeToByteArray(), credentials)
        val fields = decoded.content.group.entries.single().fields

        assertIs<EntryValue.Encrypted>(fields.password)
        assertEquals(value, fields.password?.content)
        assertEquals(value, fields.notes?.content)
    }

    @Test
    fun protectInMemoryImportNormalizesOnlyCrInCrNelSequence() {
        val literalValue = "before\r\u0085middle\u2028after"
        val xml = """
            <?xml version="1.0"?>
            <KeePassFile>
              <Meta/>
              <Root>
                <Group>
                  <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                  <Name>Root</Name>
                  <Entry>
                    <UUID>AQAAAAAAAAAAAAAAAAAAAA==</UUID>
                    <String>
                      <Key>Password</Key>
                      <Value ProtectInMemory="True">LITERAL_VALUE</Value>
                    </String>
                  </Entry>
                </Group>
                <DeletedObjects/>
              </Root>
            </KeePassFile>
        """.trimIndent().replace("LITERAL_VALUE", literalValue)
        val decoded = KeePassDatabase.decodeFromXml(xml.encodeToByteArray(), credentials)
        val password = decoded.content.group.entries.single().fields.password

        assertIs<EntryValue.Encrypted>(password)
        assertEquals("before\n\u0085middle\u2028after", password.content)
    }

    private fun database(
        version: Int,
        compression: DatabaseHeader.Compression,
    ): KeePassDatabase {
        val entry = Entry(
            uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            fields = EntryFields.of(
                BasicField.Notes() to EntryValue.Plain(value),
                BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString(value)),
            ),
        )
        return when (version) {
            3 -> KeePassDatabase.Ver3x.create(value, Meta(), credentials).let { db ->
                db.copy(
                    header = db.header.copy(compression = compression, transformRounds = 1U),
                    content = db.content.copy(group = db.content.group.copy(entries = listOf(entry))),
                )
            }
            else -> KeePassDatabase.Ver4x.create(value, Meta(), credentials).let { db ->
                db.copy(
                    header = db.header.copy(
                        compression = compression,
                        kdfParameters = KdfParameters.Aes(
                            rounds = 1U,
                            seed = ByteArray(32) { it.toByte() }.toByteString(),
                        ),
                    ),
                    content = db.content.copy(group = db.content.group.copy(entries = listOf(entry))),
                )
            }
        }
    }
}
