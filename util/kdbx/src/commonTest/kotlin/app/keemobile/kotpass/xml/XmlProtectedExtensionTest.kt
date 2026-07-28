package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlAttribute
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.models.XmlExtensionContent
import app.keemobile.kotpass.models.XmlNamespace
import app.keemobile.kotpass.models.XmlQualifiedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class XmlProtectedExtensionTest {
    @Test
    fun protectedExtensionsOutsideRootShareInnerCipherInDocumentOrder() {
        val base = parse(
            document(
                groupExtra = """
                    <Entry>
                      <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                      <String>
                        <Key>Password</Key>
                        <Value ProtectInMemory="True">entry-secret</Value>
                      </String>
                    </Entry>
                """.trimIndent(),
            )
        )
        val logical = base.copy(
            meta = base.meta.copy(
                extensions = listOf(protectedExtension("MetaSecret", "meta-secret")),
            ),
            documentExtensions = listOf(
                protectedExtension("DocumentSecret", "document-secret"),
            ),
        )
        val key = ByteArray(32) { (it * 5).toByte() }

        val encoded = DefaultXmlContentParser.marshalContent(encryptedContext(key), logical)
        val decoded = parse(encoded, key)

        assertEquals("meta-secret", decoded.meta.extensions.single().text())
        assertEquals("entry-secret", decoded.group.entries.single().fields.password?.content)
        assertEquals("document-secret", decoded.documentExtensions.single().text())
    }

    @Test
    fun protectedDocumentExtensionBeforeMetaAdvancesTheInnerCipher() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val encryption = EncryptionSaltGenerator.ChaCha20(key)
        val documentSecret = encrypt(encryption, "document-secret")
        val entrySecret = encrypt(encryption, "entry-secret")
        val xml = """
            <KeePassFile>
              <DocumentSecret Protected="True">$documentSecret</DocumentSecret>
              <Meta><Generator>Test</Generator></Meta>
              <Root>
                <Group>
                  <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                  <Name>Root</Name>
                  <Entry>
                    <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                    <String><Key>Password</Key><Value Protected="True">$entrySecret</Value></String>
                  </Entry>
                </Group>
                <DeletedObjects/>
              </Root>
            </KeePassFile>
        """.trimIndent()

        val decoded = parse(xml, key)

        assertEquals("document-secret", decoded.documentExtensions.single().text())
        assertEquals("entry-secret", decoded.group.entries.single().fields.password?.content)
    }

    @Test
    fun namespacedProtectedAttributeDoesNotConsumeTheInnerCipher() {
        val logical = parse(
            document(
                metaExtra = """
                    <p:Marker xmlns:p="urn:vendor" p:Protected="True">vendor</p:Marker>
                """.trimIndent(),
                groupExtra = """
                    <Entry>
                      <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                      <String>
                        <Key>Password</Key>
                        <Value ProtectInMemory="True">secret</Value>
                      </String>
                    </Entry>
                """.trimIndent(),
            )
        )
        val key = ByteArray(32) { it.toByte() }

        val encoded = DefaultXmlContentParser.marshalContent(encryptedContext(key), logical)
        val decoded = parse(encoded, key)

        assertEquals("secret", decoded.group.entries.single().fields.password?.content)
        val marker = decoded.meta.extensions.single()
        assertEquals("vendor", marker.text())
        assertEquals("urn:vendor", marker.attributes.single().name.namespaceUri)
    }

    @Test
    fun protectedExtensionMarkersAreCanonicalizedBeforeWriting() {
        val base = parse(document())
        val extension = XmlExtension(
            name = XmlQualifiedName(
                localName = "Secret",
                namespaceUri = "urn:test",
                prefix = "p",
            ),
            namespaces = listOf(XmlNamespace(prefix = "p", namespaceUri = "urn:test")),
            attributes = listOf(
                XmlAttribute(
                    name = XmlQualifiedName(FormatXml.Attributes.Protected),
                    value = FormatXml.Values.False,
                ),
                XmlAttribute(
                    name = XmlQualifiedName(
                        localName = FormatXml.Attributes.Protected,
                        namespaceUri = "urn:vendor",
                        prefix = "v",
                    ),
                    value = "vendor",
                ),
            ),
            content = listOf(
                XmlExtensionContent.Text(
                    EntryValue.Encrypted(EncryptedValue.fromString("secret"))
                )
            ),
        )
        val logical = base.copy(
            group = base.group.copy(extensions = listOf(extension)),
        )
        val key = ByteArray(32) { (it * 3).toByte() }

        val encoded = DefaultXmlContentParser.marshalContent(encryptedContext(key), logical)

        assertFalse(" Protected=\"False\"" in encoded)
        assertEquals(1, Regex(" Protected=\\\"True\\\"").findAll(encoded).count())
        val decoded = parse(encoded, key).group.extensions.single()
        assertEquals("secret", decoded.text())
        assertEquals("urn:vendor", decoded.attributes.single().name.namespaceUri)
        assertEquals("vendor", decoded.attributes.single().value)
    }

    @Test
    fun protectedExtensionsRejectMixedContentInsteadOfWritingPlaintext() {
        assertFailsWith<FormatError.InvalidXml> {
            parse(
                document(
                    groupExtra = """
                        <p:Secret xmlns:p="urn:test" ProtectInMemory="True">
                          <!-- metadata -->secret
                        </p:Secret>
                    """.trimIndent(),
                )
            )
        }

        val base = parse(document())
        val extension = XmlExtension(
            name = XmlQualifiedName("Secret"),
            content = listOf(
                XmlExtensionContent.Comment("metadata"),
                XmlExtensionContent.Text(
                    EntryValue.Encrypted(EncryptedValue.fromString("secret"))
                ),
            ),
        )
        val logical = base.copy(
            group = base.group.copy(extensions = listOf(extension)),
        )

        assertFailsWith<FormatError.InvalidXml> {
            DefaultXmlContentParser.marshalContent(
                encryptedContext(ByteArray(32)),
                logical,
            )
        }
    }

    private fun XmlExtension.text(): String = content
        .filterIsInstance<XmlExtensionContent.Text>()
        .joinToString("") { it.value.content }

    private fun protectedExtension(name: String, value: String) = XmlExtension(
        name = XmlQualifiedName(name),
        content = listOf(
            XmlExtensionContent.Text(
                EntryValue.Encrypted(EncryptedValue.fromString(value)),
            ),
        ),
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

    private fun parse(
        xml: String,
        key: ByteArray = ByteArray(32),
    ): DatabaseContent {
        val innerEncryption = EncryptionSaltGenerator.ChaCha20(key)
        return DefaultXmlContentParser.unmarshalContent(
            xml.encodeToByteArray(),
            innerEncryption,
        ) {
            XmlContext.Decode(
                version = FormatVersion(4, 1),
                encryption = innerEncryption,
                binaries = emptyMap(),
            )
        }
    }

    private fun encryptedContext(key: ByteArray) = XmlContext.Encode.Encrypted(
        version = FormatVersion(4, 1),
        binaries = emptyMap(),
        innerEncryption = EncryptionSaltGenerator.ChaCha20(key),
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
