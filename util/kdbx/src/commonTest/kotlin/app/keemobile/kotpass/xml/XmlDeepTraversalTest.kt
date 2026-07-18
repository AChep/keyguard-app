package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.models.XmlExtensionContent
import kotlin.test.Test
import kotlin.test.assertEquals

class XmlDeepTraversalTest {
    @Test
    fun deeplyNestedExtensionsParseAndWriteIteratively() {
        val depth = 2_000
        val extension = buildString {
            append("<x:Node xmlns:x=\"urn:deep\">")
            repeat(depth - 1) { append("<x:Node>") }
            append("leaf")
            repeat(depth) { append("</x:Node>") }
        }

        val parsed = parse(document(metaExtra = extension))
        assertExtensionDepth(depth, parsed.meta.extensions.single())

        val encoded = DefaultXmlContentParser.marshalContent(plainContext(), parsed)
        val reparsed = parse(encoded)
        assertExtensionDepth(depth, reparsed.meta.extensions.single())
    }

    @Test
    fun deeplyNestedEntryHistoryParsesAndWritesIteratively() {
        val depth = 1_500
        val entry = buildString {
            append("<Entry><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>")
            repeat(depth - 1) {
                append("<History><Entry><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>")
            }
            repeat(depth - 1) { append("</Entry></History>") }
            append("</Entry>")
        }

        val parsed = parse(document(groupExtra = entry))
        assertEntryHistoryDepth(depth, parsed.group.entries.single())

        val encoded = DefaultXmlContentParser.marshalContent(plainContext(), parsed)
        val reparsed = parse(encoded)
        assertEntryHistoryDepth(depth, reparsed.group.entries.single())
    }

    @Test
    fun entryHistoryAndExtensionsKeepCipherStreamOrder() {
        val logical = parse(
            document(
                groupExtra = """
                    <Entry>
                      <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                      <String>
                        <Key>RootSecret</Key>
                        <Value ProtectInMemory="True">root-secret</Value>
                      </String>
                      <History>
                        <Entry>
                          <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                          <String>
                            <Key>HistorySecret</Key>
                            <Value ProtectInMemory="True">history-secret</Value>
                          </String>
                          <x:Secret xmlns:x="urn:test" ProtectInMemory="True">
                            history-extension
                          </x:Secret>
                        </Entry>
                      </History>
                      <x:Secret xmlns:x="urn:test" ProtectInMemory="True">
                        root-extension
                      </x:Secret>
                    </Entry>
                """.trimIndent(),
            )
        )
        val key = ByteArray(32) { it.toByte() }

        val encrypted = DefaultXmlContentParser.marshalContent(
            XmlContext.Encode.Encrypted(
                version = FormatVersion(4, 1),
                binaries = emptyMap(),
                innerEncryption = EncryptionSaltGenerator.ChaCha20(key),
            ),
            logical,
        )
        val innerEncryption = EncryptionSaltGenerator.ChaCha20(key)
        val decoded = DefaultXmlContentParser.unmarshalContent(
            encrypted.encodeToByteArray(),
            innerEncryption,
        ) {
            decodeContext(innerEncryption)
        }

        val root = decoded.group.entries.single()
        val history = root.history.single()
        assertEquals("root-secret", root.fields["RootSecret"]?.content)
        assertEquals("history-secret", history.fields["HistorySecret"]?.content)
        assertEquals("history-extension", history.extensions.single().protectedText())
        assertEquals("root-extension", root.extensions.single().protectedText())
    }

    private fun assertExtensionDepth(expected: Int, root: XmlExtension) {
        var actual = 1
        var current = root
        while (true) {
            val child = current.content
                .filterIsInstance<XmlExtensionContent.Element>()
                .singleOrNull()
                ?: break
            current = child.value
            actual++
        }
        assertEquals(expected, actual)
        assertEquals(
            "leaf",
            current.content.filterIsInstance<XmlExtensionContent.Text>()
                .joinToString("") { it.value.content },
        )
    }

    private fun assertEntryHistoryDepth(expected: Int, root: Entry) {
        var actual = 1
        var current = root
        while (current.history.isNotEmpty()) {
            current = current.history.single()
            actual++
        }
        assertEquals(expected, actual)
    }

    private fun XmlExtension.protectedText(): String =
        (content.single() as XmlExtensionContent.Text).value.content.trim()

    private fun parse(xml: String): DatabaseContent {
        val innerEncryption = EncryptionSaltGenerator.ChaCha20(ByteArray(32))
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
}
