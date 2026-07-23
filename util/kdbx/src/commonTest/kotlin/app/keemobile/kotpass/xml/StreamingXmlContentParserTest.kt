package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import nl.adaptivity.xmlutil.XmlException
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamingXmlContentParserTest {
    @Test
    fun malformedAndTruncatedDocumentsBecomeInvalidXml() {
        val cases = listOf(
            "",
            "<KeePassFile>",
            "<KeePassFile><Meta/></KeePassFile>",
            "<KeePassFile><Meta/><Root><Group></Root></KeePassFile>",
            "<KeePassFile><Meta/><Root><Group><UUID>bad</UUID></Group></Root></KeePassFile>",
        )
        cases.forEach { xml ->
            assertFailsWith<FormatError.InvalidXml>(xml) { parse(xml) }
        }
    }

    @Test
    fun trailingDocumentContentIsRejected() {
        val valid = document()

        assertFailsWith<FormatError.InvalidXml> { parse("$valid<Extra/>") }
        assertFailsWith<FormatError.InvalidXml> { parse("${valid}garbage") }
    }

    @Test
    fun unknownNamespacedAndProtectedExtensionsSurviveRoundTrip() {
        val xml = document(
            metaExtra = """
                <p:MetaExtra xmlns:p="urn:test" p:flag="yes">
                    <p:Child>text &amp; more</p:Child>
                </p:MetaExtra>
            """.trimIndent(),
            groupExtra = """
                <p:Secret xmlns:p="urn:test" ProtectInMemory="True">sensitive</p:Secret>
            """.trimIndent(),
        )
        val parsed = parse(xml)
        assertEquals("MetaExtra", parsed.meta.extensions.single().name.localName)
        assertEquals("urn:test", parsed.meta.extensions.single().name.namespaceUri)
        val protectedText = parsed.group.extensions.single().content.single()
            as app.keemobile.kotpass.models.XmlExtensionContent.Text
        assertTrue(protectedText.value is EntryValue.Encrypted)
        assertEquals("sensitive", protectedText.value.content)

        val encoded = DefaultXmlContentParser.marshalContent(
            plainContext(),
            parsed,
            pretty = true,
        )
        val reparsed = parse(encoded)
        val child = reparsed.meta.extensions.single().content
            .filterIsInstance<app.keemobile.kotpass.models.XmlExtensionContent.Element>()
            .single()
        assertEquals(
            "text & more",
            child.value.content
                .filterIsInstance<app.keemobile.kotpass.models.XmlExtensionContent.Text>()
                .joinToString("") { it.value.content },
        )
        assertEquals(
            "sensitive",
            (reparsed.group.extensions.single().content.single()
                as app.keemobile.kotpass.models.XmlExtensionContent.Text).value.content,
        )
    }

    @Test
    fun deeplyNestedGroupsParseAndWriteIteratively() {
        val depth = 1_200
        val groups = buildString {
            repeat(depth) { append("<Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><Name>g$it</Name>") }
            repeat(depth) { append("</Group>") }
        }
        val parsed = parse(
            "<KeePassFile><Meta><Generator>T</Generator></Meta><Root>" +
                groups + "<DeletedObjects/></Root></KeePassFile>"
        )
        var count = 1
        var current = parsed.group
        while (current.groups.isNotEmpty()) {
            current = current.groups.single()
            count++
        }
        assertEquals(depth, count)

        val encoded = DefaultXmlContentParser.marshalContent(plainContext(), parsed)
        assertTrue(encoded.length > groups.length)
        assertEquals(depth, Regex("<Group>").findAll(encoded).count())
    }

    @Test
    fun protectedFieldsAndExtensionsKeepCipherStreamOrder() {
        val plain = document(
            groupExtra = """
                <Entry>
                  <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                  <String><Key>Password</Key><Value ProtectInMemory="True">entry-secret</Value></String>
                </Entry>
                <p:Secret xmlns:p="urn:test" ProtectInMemory="True">extension-secret</p:Secret>
            """.trimIndent(),
        )
        val logical = parse(plain)
        val key = ByteArray(32) { it.toByte() }
        val encrypted = DefaultXmlContentParser.marshalContent(
            XmlContext.Encode.Encrypted(
                version = FormatVersion(4, 1),
                binaries = emptyMap(),
                innerEncryption = EncryptionSaltGenerator.ChaCha20(key),
            ),
            logical,
        )
        assertTrue("Protected=\"True\"" in encrypted)

        val innerEncryption = EncryptionSaltGenerator.ChaCha20(key)
        val decoded = DefaultXmlContentParser.unmarshalContent(
            encrypted.encodeToByteArray(),
            innerEncryption,
        ) {
            decodeContext(innerEncryption)
        }
        assertEquals("entry-secret", decoded.group.entries.single().fields.password?.content)
        assertEquals(
            "extension-secret",
            (decoded.group.extensions.single().content.single()
                as app.keemobile.kotpass.models.XmlExtensionContent.Text).value.content,
        )
    }

    @Test
    fun discardedProtectedValuesInsideKnownContainersKeepCipherStreamOrder() {
        val key = ByteArray(32) { index -> (index * 19).toByte() }
        val encryption = EncryptionSaltGenerator.ChaCha20(key)
        val metaDiscarded = encrypt(encryption, "meta discarded")
        val groupDiscarded = encrypt(encryption, "group discarded")
        val autoTypeDiscarded = encrypt(encryption, "auto type discarded")
        val fieldDiscarded = encrypt(encryption, "field discarded")
        val password = encrypt(encryption, "entry secret")
        val xml = """
            <KeePassFile>
              <Meta>
                <Generator>Test</Generator>
                <MemoryProtection>
                  <Unknown><Secret Protected="True">$metaDiscarded</Secret></Unknown>
                </MemoryProtection>
              </Meta>
              <Root>
                <Group>
                  <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                  <Name>Root</Name>
                  <Times>
                    <Unknown><Secret Protected="True">$groupDiscarded</Secret></Unknown>
                  </Times>
                  <Entry>
                    <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                    <AutoType>
                      <Unknown><Secret Protected="True">$autoTypeDiscarded</Secret></Unknown>
                    </AutoType>
                    <String>
                      <Unknown><Secret Protected="True">$fieldDiscarded</Secret></Unknown>
                      <Key>Password</Key>
                      <Value Protected="True">$password</Value>
                    </String>
                  </Entry>
                </Group>
                <DeletedObjects/>
              </Root>
            </KeePassFile>
        """.trimIndent()
        val innerEncryption = EncryptionSaltGenerator.ChaCha20(key)

        val decoded = DefaultXmlContentParser.unmarshalContent(
            xml.encodeToByteArray(),
            innerEncryption,
        ) { decodeContext(innerEncryption) }

        assertEquals("entry secret", decoded.group.entries.single().fields.password?.content)
    }

    @Test
    fun utf8CodePointsCanCrossInputChunkBoundaries() {
        val description = "Zażółć gęślą jaźń — 漢字 — 😀"
        val xml = document(
            metaExtra = "<DatabaseDescription>$description</DatabaseDescription>",
        ).encodeToByteArray()
        val source = OneByteSource(xml).buffer()

        val innerEncryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())
        val parsed = DefaultXmlContentParser.unmarshalContent(source, innerEncryption) {
            decodeContext(innerEncryption)
        }

        assertEquals(description, parsed.meta.description)
    }

    @Test
    fun malformedUtf8BecomesInvalidXml() {
        val prefix = "<KeePassFile><Meta><DatabaseDescription>".encodeToByteArray()
        val suffix = document().substringAfter("</Meta>")
            .let { "</DatabaseDescription></Meta>$it" }
            .encodeToByteArray()
        val xml = prefix + byteArrayOf(0xC0.toByte(), 0xAF.toByte()) + suffix

        assertFailsWith<FormatError.InvalidXml> {
            val innerEncryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())
            DefaultXmlContentParser.unmarshalContent(Buffer().write(xml), innerEncryption) {
                decodeContext(innerEncryption)
            }
        }
    }

    @Test
    fun sourceCancellationIsRethrownUnchanged() {
        val expected = CancellationException("cancelled")
        val source = ThrowingSource(expected).buffer()
        val innerEncryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())

        val actual = assertFailsWith<CancellationException> {
            DefaultXmlContentParser.unmarshalContent(source, innerEncryption) {
                decodeContext(innerEncryption)
            }
        }

        assertSame(expected, actual)
    }

    @Test
    fun xmlFailureBecomesInvalidXmlWithCause() {
        val actual = assertFailsWith<FormatError.InvalidXml> {
            parse("<KeePassFile><Meta></KeePassFile>")
        }

        assertTrue(actual.cause is XmlException)
    }

    @Test
    fun sourceFailureBecomesInvalidXmlWithCause() {
        val expected = IllegalStateException("source failure")
        val source = ThrowingSource(expected).buffer()
        val innerEncryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())

        val actual = assertFailsWith<FormatError.InvalidXml> {
            DefaultXmlContentParser.unmarshalContent(source, innerEncryption) {
                decodeContext(innerEncryption)
            }
        }

        assertSame(expected, actual.cause)
    }

    private fun parse(xml: String): DatabaseContent {
        val innerEncryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())
        return DefaultXmlContentParser.unmarshalContent(
            xml.encodeToByteArray(),
            innerEncryption,
        ) { decodeContext(innerEncryption) }
    }

    private fun decodeContext(
        innerEncryption: EncryptionSaltGenerator,
    ) = XmlContext.Decode(
        version = FormatVersion(4, 1),
        encryption = innerEncryption,
        binaries = emptyMap(),
    )

    private fun plainContext() = XmlContext.Encode.Plain(
        version = FormatVersion(4, 1),
        binaries = emptyMap(),
        memoryProtectionFlags = emptySet(),
    )

    private fun encrypt(
        encryption: EncryptionSaltGenerator,
        value: String,
    ): String {
        val plaintext = value.encodeToByteArray()
        val ciphertext = try {
            encryption.processBytes(plaintext)
        } finally {
            plaintext.fill(0)
        }
        return try {
            ciphertext.encodeBase64()
        } finally {
            ciphertext.fill(0)
        }
    }

    private fun document(metaExtra: String = "", groupExtra: String = "") = """
        <KeePassFile>
          <Meta><Generator>Test</Generator>$metaExtra</Meta>
          <Root>
            <Group>
              <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
              <Name>Root</Name>
              $groupExtra
            </Group>
            <DeletedObjects/>
          </Root>
        </KeePassFile>
    """.trimIndent()

    private class OneByteSource(bytes: ByteArray) : Source {
        private val source = Buffer().write(bytes)

        override fun read(sink: Buffer, byteCount: Long): Long =
            source.read(sink, minOf(byteCount, 1L))

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    private class ThrowingSource(
        private val failure: Throwable,
    ) : Source {
        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long = throw failure

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
}
