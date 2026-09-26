package app.keemobile.kotpass.xml

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encodeTo
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
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class UnicodeSinkBoundaryTest {
    @Test
    fun appendOverloadsPreservePairsSplitAcrossCalls() {
        val appenders: List<BufferedSinkWriter.(String) -> Unit> = listOf(
            { value -> value.forEach { append(it) } },
            { value -> append(value) },
            { value -> append("[$value]", 1, value.length + 1) },
        )
        val failures = mutableListOf<String>()
        appenders.forEachIndexed { index, append ->
            val sink = okio.Buffer()
            val writer = BufferedSinkWriter(sink)
            val prefix = "a".repeat(8_191)
            // Repeat to exercise carry-over into subsequent threshold flushes.
            repeat(3) {
                append(writer, prefix + "\uD83D")
                append(writer, "\uDD11")
            }
            writer.flush()
            if (sink.readUtf8() != (prefix + "🔑").repeat(3)) {
                failures += "append overload $index"
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun finalFlushPreservesExistingUnpairedSurrogateEncoding() {
        for (length in listOf(0, 8_191)) {
            val value = "a".repeat(length) + "\uD83D"
            val sink = okio.Buffer()
            val writer = BufferedSinkWriter(sink)
            writer.append(value)
            writer.flush()
            val expected = okio.Buffer().writeUtf8(value).readByteString()
            assertEquals(expected, sink.readByteString())
            writer.flush()
            assertEquals(0L, sink.size)
        }
    }

    @Test
    fun supplementaryTextSurvivesTheFlushBoundaryAndItsNeighbors() {
        val prefixLength = buildXmlString("Root") {
            textElement("Value", "SENTINEL")
        }.indexOf("SENTINEL")
        val failures = mutableListOf<String>()
        for (offset in -1..1) {
            val value = "a".repeat(8_191 - prefixLength + offset) + "🔑tail"
            val expected = buildXmlString("Root") { textElement("Value", value) }
            val sink = okio.Buffer()
            writeXml(sink, "Root") { textElement("Value", value) }
            val actual = sink.readUtf8()
            if (actual != expected) failures += "offset=$offset actual tail=${actual.takeLast(36)}"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun supplementaryAttributeSurvivesTheFlushBoundaryAndItsNeighbors() {
        val prefixLength = buildXmlString("Root") {
            element("Value") { attribute("Label", "SENTINEL") }
        }.indexOf("SENTINEL")
        val failures = mutableListOf<String>()
        for (offset in -1..1) {
            val value = "a".repeat(8_191 - prefixLength + offset) + "🔑tail"
            val expected = buildXmlString("Root") {
                element("Value") { attribute("Label", value) }
            }
            val sink = okio.Buffer()
            writeXml(sink, "Root") { element("Value") { attribute("Label", value) } }
            val actual = sink.readUtf8()
            if (actual != expected) failures += "offset=$offset actual tail=${actual.takeLast(36)}"
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun savedKdbxPreservesPlainNotesAlongsideProtectedPasswords() {
        val credentials = Credentials.from(EncryptedValue.fromString("unicode-boundary-test"))
        // Three UTF-16 chars per repetition visit every phase of the 8192-char boundary.
        val value = "🔑x".repeat(9_000)
        val entry = Entry(
            uuid = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            fields = EntryFields.of(
                BasicField.Notes() to EntryValue.Plain(value),
                BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString(value)),
            ),
        )
        val failures = mutableListOf<String>()
        for (version in listOf(3, 4)) {
            for (compression in DatabaseHeader.Compression.entries) {
                val original = when (version) {
                    3 -> KeePassDatabase.Ver3x.create("Root", Meta(), credentials).let { db ->
                        db.copy(
                            header = db.header.copy(compression = compression, transformRounds = 1U),
                            content = db.content.copy(group = db.content.group.copy(entries = listOf(entry))),
                        )
                    }
                    else -> KeePassDatabase.Ver4x.create("Root", Meta(), credentials).let { db ->
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
                val encoded = Buffer()
                original.encodeTo(encoded)
                val reopened = KeePassDatabase.decode(encoded, credentials)
                val fields = reopened.content.group.entries.single().fields
                assertTrue(fields.password?.content == value, "Protected value changed: v$version $compression")
                if (fields.notes?.content != value) {
                    val questionMarks = fields.notes?.content?.count { it == '?' }
                    failures += "v$version $compression: notes reopened, but contain $questionMarks question marks"
                }
            }
        }
        assertEquals(emptyList(), failures)
    }
}
