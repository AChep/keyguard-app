package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.KdbxBinaryContentVisitor
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.visitBinaryContents
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.io.gzip
import app.keemobile.kotpass.database.BinaryIndex
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ProtectedPooledBinaryReviewTest {
    private val streamKey = ByteArray(32) { it.toByte() }
    private val attachment = "protected pooled attachment".encodeToByteArray()
    private val password = "password after pooled attachment"

    @Test
    fun unprotectedPoolKeepsPasswordStreamAligned() {
        val decoded = decode(protectedPool = false)

        assertEquals(password, decoded.group.entries.single().fields.password?.content)
        assertContentEquals(attachment, decoded.meta.binaries.values.single().getContent())
    }

    @Test
    fun decryptsProtectedPoolAndKeepsPasswordStreamAligned() {
        val decoded = decode()

        assertEquals(password, decoded.group.entries.single().fields.password?.content)
        assertContentEquals(attachment, decoded.meta.binaries.values.single().getContent())
    }

    @Test
    fun decryptsProtectedPoolContent() {
        assertContentEquals(attachment, decode().meta.binaries.values.single().getContent())
    }

    @Test
    fun preservesProtectedPoolMemoryProtection() {
        assertTrue(decode().meta.binaries.values.single().memoryProtection)
    }

    @Test
    fun streamsProtectedPoolAsPlaintext() {
        val contents = mutableListOf<ByteArray>()
        val decryptor = EncryptionSaltGenerator.Salsa20(streamKey)
        visitXmlBinaryContents(
            source = Buffer().writeUtf8(document()),
            innerEncryption = decryptor,
            visitor = XmlBinaryContentVisitor { source, declaredLength ->
                assertNull(declaredLength)
                val output = Buffer()
                while (source.read(output, 8192L) != -1L) {
                    // Consume the attachment through the streaming API.
                }
                contents += output.readByteArray()
            },
        )

        assertContentEquals(attachment, contents.single())
        val expectedStream = EncryptionSaltGenerator.Salsa20(streamKey)
        expectedStream.getSalt(attachment.size + password.encodeToByteArray().size)
        assertContentEquals(expectedStream.getSalt(32), decryptor.getSalt(32))
    }

    @Test
    fun preservesContentsThroughXmlWritesAndProtectionInEncryptedXml() {
        for (compressed in listOf(false, true)) {
            val original = decode(xml = document(compressed = compressed))
            for (plain in listOf(false, true)) {
                val context = if (plain) {
                    XmlContext.Encode.Plain(
                        version = FormatVersion(3, 1),
                        binaries = original.meta.binaries,
                        memoryProtectionFlags = emptySet(),
                    )
                } else {
                    XmlContext.Encode.Encrypted(
                        version = FormatVersion(3, 1),
                        binaries = original.meta.binaries,
                        innerEncryption = EncryptionSaltGenerator.Salsa20(streamKey),
                    )
                }
                val xml = DefaultXmlContentParser.marshalContent(context, original)
                val poolItem = xml.substringAfter("<Binaries>").substringBefore("</Binaries>")
                assertFalse(poolItem.contains("ProtectInMemory="))
                if (plain) {
                    assertFalse(poolItem.contains("Protected="))
                } else {
                    assertTrue(poolItem.contains("Protected=\"True\""))
                    assertFalse(poolItem.contains("Compressed="))
                    assertFalse(poolItem.contains(attachment.encodeBase64()))
                }
                val reopened = decode(xml = xml)
                val entry = reopened.group.entries.single()
                val binary = reopened.meta.binaries.getValue(entry.binaries.single().hash)
                assertEquals(password, entry.fields.password?.content)
                assertEquals(!plain, binary.memoryProtection)
                assertContentEquals(attachment, binary.getContent())
                // Writing must not wipe the model's backing bytes.
                assertContentEquals(attachment, original.meta.binaries.values.single().getContent())
                assertTrue(original.meta.binaries.values.single().memoryProtection)
            }
        }
    }

    @Test
    fun savesAndReopensProtectedAttachmentsInKdbx3() {
        val credentials = Credentials.from(EncryptedValue.fromString("test master password"))
        for (compression in DatabaseHeader.Compression.entries) {
            val content = decode(xml = document(compressed = true))
            val base = KeePassDatabase.Ver3x.create("Root", content.meta, credentials)
            val database = base.copy(
                content = content,
                header = base.header.copy(transformRounds = 1U, compression = compression),
            )
            val encoded = database.encode()
            val reopened = KeePassDatabase.decode(encoded, credentials).content
            val entry = reopened.group.entries.single()
            val binary = reopened.meta.binaries.getValue(entry.binaries.single().hash)
            assertEquals(password, entry.fields.password?.content)
            assertContentEquals(attachment, binary.getContent())
            assertTrue(binary.memoryProtection)

            val visited = mutableListOf<ByteArray>()
            KeePassDatabase.visitBinaryContents(
                source = kotlinx.io.Buffer().apply { write(encoded) },
                credentials = credentials,
                visitor = KdbxBinaryContentVisitor { source, _ ->
                    val output = Buffer()
                    while (source.read(output, 8192L) != -1L) {
                        // Read through the authenticated container and XML visitor.
                    }
                    visited += output.readByteArray()
                },
            )
            assertContentEquals(attachment, visited.single())
        }
    }

    @Test
    fun consumesMixedSparseDuplicateAndEmptyPoolItemsInDocumentOrder() {
        val encryptor = EncryptionSaltGenerator.Salsa20(streamKey)
        val first = encryptor.processBytes(attachment).encodeBase64()
        val duplicate = encryptor.processBytes(attachment).encodeBase64()
        val finalContent = "last attachment".encodeToByteArray()
        val last = encryptor.processBytes(finalContent.gzip()).encodeBase64()
        val secret = encryptor.processBytes(password.encodeToByteArray()).encodeBase64()
        val pool = """
            <Binary ID="9" Protected="True">$first</Binary>
            <Binary ID="3">${"plain".encodeToByteArray().encodeBase64()}</Binary>
            <Binary ID="1" Protected="True">$duplicate</Binary>
            <Binary ID="2" Protected="True"/>
            <Binary ID="0" Protected="True" Compressed="True">$last</Binary>
        """.trimIndent()
        val xml = document().replace(
            Regex("<Binaries>[\\s\\S]*?</Binaries>"),
            "<Binaries>$pool</Binaries>",
        ).replace(
            Regex("<Value Protected=\"True\">.*?</Value>"),
            "<Value Protected=\"True\">$secret</Value>",
        )
        val decoded = decode(xml = xml)
        assertEquals(password, decoded.group.entries.single().fields.password?.content)
        val index = BinaryIndex(decoded.meta.binaries)
        assertContentEquals(attachment, index.getByRef(9)!!.data.getContent())
        assertContentEquals(attachment, index.getByRef(1)!!.data.getContent())
        assertContentEquals(byteArrayOf(), index.getByRef(2)!!.data.getContent())
        assertContentEquals(finalContent, index.getByRef(0)!!.data.getContent())

        // Partial callbacks must still consume the entire physical pool, including duplicates.
        val decryptor = EncryptionSaltGenerator.Salsa20(streamKey)
        var visits = 0
        visitXmlBinaryContents(
            Buffer().writeUtf8(xml),
            decryptor,
            XmlBinaryContentVisitor { source, _ ->
                source.read(Buffer(), 1)
                visits++
            },
        )
        assertEquals(5, visits)
        assertContentEquals(encryptor.getSalt(32), decryptor.getSalt(32))
    }

    private fun decode(
        protectedPool: Boolean = true,
        xml: String = document(protectedPool),
    ): DatabaseContent {
        val decryptor = EncryptionSaltGenerator.Salsa20(streamKey)
        return DefaultXmlContentParser.unmarshalContent(
            Buffer().writeUtf8(xml),
            decryptor,
        ) { meta ->
            XmlContext.Decode(
                version = FormatVersion(3, 1),
                encryption = decryptor,
                binaries = meta.binaries,
            )
        }
    }

    private fun document(protectedPool: Boolean = true, compressed: Boolean = false): String {
        val encryptor = EncryptionSaltGenerator.Salsa20(streamKey)
        val stored = if (compressed) attachment.gzip() else attachment
        val binaryCiphertext = if (protectedPool) {
            encryptor.processBytes(stored)
        } else {
            stored
        }.encodeBase64()
        val protection = if (protectedPool) "True" else "False"
        val passwordCiphertext = encryptor.processBytes(password.encodeToByteArray()).encodeBase64()
        return """
            <KeePassFile>
                <Meta>
                    <Binaries>
                        <Binary ID="0" Protected="$protection" Compressed="$compressed">$binaryCiphertext</Binary>
                    </Binaries>
                </Meta>
                <Root>
                    <Group>
                        <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                        <Name>Root</Name>
                        <Entry>
                            <UUID>AQAAAAAAAAAAAAAAAAAAAA==</UUID>
                            <String>
                                <Key>Password</Key>
                                <Value Protected="True">$passwordCiphertext</Value>
                            </String>
                            <Binary>
                                <Key>attachment.bin</Key>
                                <Value Ref="0"/>
                            </Binary>
                        </Entry>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()
    }
}
