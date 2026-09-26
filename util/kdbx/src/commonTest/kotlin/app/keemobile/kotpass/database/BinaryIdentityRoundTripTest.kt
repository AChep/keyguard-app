package app.keemobile.kotpass.database

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.XmlContentParser
import okio.Buffer
import okio.BufferedSink
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class BinaryIdentityRoundTripTest {
    private val credentials = Credentials.from(
        EncryptedValue.fromString("binary-identity-test-password"),
    )
    private val text = "attachment".encodeToByteArray()
    // Both representations store these exact bytes, but only one inflates them.
    private val gzip = "H4sIAAAAAAAC/0ssKUlMzshNzSsBALvZX3kKAAAA".decodeBase64()!!
    private val expected = mapOf(
        "text.txt" to text,
        "raw.gz" to gzip.toByteArray(),
    )

    @Test
    fun preservesMixedProtectionInKdbxAndContentsInPlainXmlRoundTrips() {
        for (protectedFirst in listOf(false, true)) {
            for (inline in listOf(false, true)) {
                for (compressed in listOf(false, true)) {
                    val flags = listOf(protectedFirst, !protectedFirst)
                    val payload = if (compressed) gzip else text.toByteString()
                    val pool = flags.mapIndexed { id, protected ->
                        """<Binary ID="$id" ProtectInMemory="$protected" Compressed="$compressed">""" +
                            "${payload.base64()}</Binary>"
                    }.joinToString("")
                    val references = flags.mapIndexed { id, protected ->
                        val value = if (inline) {
                            """<Value ProtectInMemory="$protected" Compressed="$compressed">${payload.base64()}</Value>"""
                        } else {
                            """<Value Ref="$id"/>"""
                        }
                        """<Binary><Key>$protected</Key>$value</Binary>"""
                    }.joinToString("")
                    val xml = """
                        <KeePassFile>
                            <Meta><Binaries>${if (inline) "" else pool}</Binaries></Meta>
                            <Root><Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                                <Entry><UUID>AQAAAAAAAAAAAAAAAAAAAA==</UUID>$references</Entry>
                            </Group></Root>
                        </KeePassFile>
                    """.trimIndent()
                    val loaded = KeePassDatabase.decodeFromXml(xml.encodeToByteArray(), credentials)
                    assertMixedProtection(loaded)
                    assertPlainXmlProtection(loaded)
                    assertMixedProtection(loaded)
                    val v3 = KeePassDatabase.Ver3x.create("Root", Meta(), credentials).let { base ->
                        base.copy(
                            header = base.header.copy(transformRounds = 1U),
                            content = loaded.content.copy(
                                meta = loaded.content.meta.copy(binaries = loaded.binaries),
                            ),
                        )
                    }
                    val v4 = assertIs<KeePassDatabase.Ver4x>(loaded).let { base ->
                        base.copy(
                            header = base.header.copy(
                                kdfParameters = KdfParameters.Aes(
                                    rounds = 1U,
                                    seed = ByteArray(32) { it.toByte() }.toByteString(),
                                ),
                            ),
                        )
                    }
                    for (database in listOf(v3, v4)) {
                        val reopened = KeePassDatabase.decode(database.encode(), credentials)
                        assertMixedProtection(reopened)
                        assertMixedProtection(KeePassDatabase.decode(reopened.encode(), credentials))
                    }
                }
            }
        }
    }

    private fun assertPlainXmlProtection(loaded: KeePassDatabase) {
        val fromXml = KeePassDatabase.decodeFromXml(
            loaded.encodeAsXml().encodeToByteArray(),
            credentials,
        )
        // Plain XML preserves attachment bytes and references, but
        // cannot portably preserve binary memory-protection flags.
        assertEquals(1, fromXml.binaries.size)
        val exportedReferences = fromXml.content.group.entries.single().binaries
        assertEquals(setOf("true", "false"), exportedReferences.map { it.name }.toSet())
        assertEquals(2, exportedReferences.size)
        for (reference in exportedReferences) {
            val binary = fromXml.binaries.getValue(reference.hash)
            assertFalse(binary.memoryProtection)
            assertContentEquals(text, binary.getContent())
        }
    }

    private fun assertMixedProtection(database: KeePassDatabase) {
        assertEquals(2, database.binaries.size)
        val references = database.content.group.entries.single().binaries
        assertEquals(setOf("true", "false"), references.map { it.name }.toSet())
        for (reference in references) {
            val binary = database.binaries.getValue(reference.hash)
            assertEquals(reference.name.toBooleanStrict(), binary.memoryProtection)
            assertContentEquals(text, binary.getContent())
        }
    }

    @Test
    fun preservesSparsePooledReferencesAndHistoryThroughXmlRoundTrip() {
        assertXmlRoundTrips(inline = false)
    }

    @Test
    fun preservesInlineBinariesAndHistoryThroughXmlRoundTrip() {
        assertXmlRoundTrips(inline = true)
    }

    @Test
    fun preservesBothContentsThroughEncryptedKdbx3AndKdbx4RoundTrips() {
        for (inline in listOf(false, true)) {
            for (compressedFirst in listOf(false, true)) {
                for (compression in DatabaseHeader.Compression.entries) {
                    val base = KeePassDatabase.Ver3x.create(
                        rootName = "Root",
                        meta = Meta(),
                        credentials = credentials,
                    ).let { database ->
                        database.copy(
                            header = database.header.copy(
                                transformRounds = 1U,
                                compression = compression,
                            ),
                        )
                    }
                    // Write the fixture before model construction can deduplicate it,
                    // using real KDBX encryption and a valid header hash.
                    val writer = object : XmlContentParser by DefaultXmlContentParser {
                        override fun marshalContentTo(
                            context: XmlContext.Encode,
                            content: DatabaseContent,
                            sink: BufferedSink,
                            pretty: Boolean,
                        ) {
                            sink.writeUtf8(
                                document(
                                    compressedFirst = compressedFirst,
                                    inline = inline,
                                    headerHash = content.meta.headerHash!!.base64(),
                                ),
                            )
                        }
                    }
                    val original = base.encode(contentParser = writer)
                    // Inline history repeats the physical records. The scanner must
                    // still visit these duplicates even though the model shares them.
                    assertScannedContents(original, copies = if (inline) 2 else 1)
                    val loaded = KeePassDatabase.decode(original, credentials)
                    assertAttachmentContents(loaded)

                    val savedV3 = loaded.encode()
                    assertAttachmentContents(KeePassDatabase.decode(savedV3, credentials))
                    assertScannedContents(savedV3)

                    val converted = assertIs<KeePassDatabase.Ver4x>(
                        KeePassDatabase.decodeFromXml(
                            loaded.encodeAsXml().encodeToByteArray(),
                            credentials,
                        ),
                    ).let { database ->
                        database.copy(
                            header = database.header.copy(
                                compression = compression,
                                kdfParameters = KdfParameters.Aes(
                                    rounds = 1U,
                                    seed = ByteArray(32) { it.toByte() }.toByteString(),
                                ),
                            ),
                        )
                    }
                    assertAttachmentContents(converted)
                    val savedV4 = converted.encode()
                    val reopenedV4 = KeePassDatabase.decode(savedV4, credentials)
                    assertAttachmentContents(reopenedV4)
                    assertScannedContents(savedV4)
                    // KDBX 4 writes logical bytes into its inner header. Save again
                    // after that representation change to exercise rebuilt references.
                    val resavedV4 = reopenedV4.encode()
                    assertAttachmentContents(KeePassDatabase.decode(resavedV4, credentials))
                    assertScannedContents(resavedV4)
                }
            }
        }
    }

    private fun assertXmlRoundTrips(inline: Boolean) {
        for (compressedFirst in listOf(false, true)) {
            val loaded = KeePassDatabase.decodeFromXml(
                document(compressedFirst, inline).encodeToByteArray(),
                credentials,
            )
            assertAttachmentContents(loaded)
            val reopened = KeePassDatabase.decodeFromXml(
                loaded.encodeAsXml().encodeToByteArray(),
                credentials,
            )
            assertAttachmentContents(reopened)
        }
    }

    private fun assertAttachmentContents(database: KeePassDatabase) {
        assertEquals(2, database.binaries.size)
        val entry = database.content.group.entries.single()
        for (revision in listOf(entry, entry.history.single())) {
            assertEquals(expected.keys, revision.binaries.map { it.name }.toSet())
            assertEquals(2, revision.binaries.size)
            for (reference in revision.binaries) {
                assertContentEquals(
                    expected.getValue(reference.name),
                    database.binaries.getValue(reference.hash).getContent(),
                    reference.name,
                )
            }
        }
    }

    private fun assertScannedContents(encoded: ByteArray, copies: Int = 1) {
        val visited = mutableListOf<okio.ByteString>()
        KeePassDatabase.visitBinaryContents(
            source = kotlinx.io.Buffer().apply { write(encoded) },
            credentials = credentials,
            visitor = KdbxBinaryContentVisitor { source, _ ->
                val output = Buffer()
                while (source.read(output, 64 * 1024L) != -1L) {
                    // Read the full candidate, including physical duplicates.
                }
                visited += output.readByteString()
            },
        )
        assertEquals(2 * copies, visited.size)
        for (content in expected.values) {
            assertEquals(copies, visited.count { it == content.toByteString() })
        }
    }

    private fun document(
        compressedFirst: Boolean,
        inline: Boolean,
        headerHash: String? = null,
    ): String {
        val representations = if (compressedFirst) listOf(true, false) else listOf(false, true)
        val records = representations.mapIndexed { index, compressed ->
            val id = if (index == 0) 2 else 7
            val flag = if (compressed) "True" else "False"
            val name = if (compressed) "text.txt" else "raw.gz"
            Triple(id, flag, name)
        }
        val pool = if (inline) "" else records.joinToString("") { (id, flag, _) ->
            """<Binary ID="$id" Compressed="$flag">${gzip.base64()}</Binary>"""
        }
        val attachments = records.joinToString("") { (id, flag, name) ->
            val value = if (inline) {
                """<Value Compressed="$flag">${gzip.base64()}</Value>"""
            } else {
                """<Value Ref="$id"/>"""
            }
            """<Binary><Key>$name</Key>$value</Binary>"""
        }
        val hash = headerHash?.let { "<HeaderHash>$it</HeaderHash>" }.orEmpty()
        return """
            <KeePassFile>
                <Meta>$hash<Binaries>$pool</Binaries></Meta>
                <Root>
                    <Group>
                        <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                        <Entry>
                            <UUID>AQAAAAAAAAAAAAAAAAAAAA==</UUID>
                            $attachments
                            <History>
                                <Entry>
                                    <UUID>AQAAAAAAAAAAAAAAAAAAAA==</UUID>
                                    $attachments
                                </Entry>
                            </History>
                        </Entry>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()
    }
}
