package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlExtension
import app.keemobile.kotpass.models.XmlExtensionContent
import app.keemobile.kotpass.models.XmlNamespace
import app.keemobile.kotpass.models.XmlQualifiedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmlNamespaceTest {
    @Test
    fun schemaNamesOnlyMatchUnqualifiedElementsAndRetainNamespacedExtensions() {
        val content = parse(
            """
            <KeePassFile xmlns:p="urn:test" xmlns:a="urn:attributes">
              <Meta>
                <p:Generator>Vendor</p:Generator>
                <Generator>Test</Generator>
              </Meta>
              <Root>
                <Group>
                  <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                  <Name>Root</Name>
                  <p:Name a:flag="yes">Extension name</p:Name>
                  <p:Group><p:Child>value</p:Child></p:Group>
                </Group>
                <DeletedObjects/>
              </Root>
            </KeePassFile>
            """.trimIndent(),
        )

        assertEquals("Test", content.meta.generator)
        assertEquals("Root", content.group.name)
        assertTrue(content.group.groups.isEmpty())
        assertEquals(listOf("Name", "Group"), content.group.extensions.map { it.name.localName })
        assertTrue(content.group.extensions.all { it.name.namespaceUri == "urn:test" })
        assertEquals("Generator", content.meta.extensions.single().name.localName)
        assertEquals("urn:test", content.meta.extensions.single().name.namespaceUri)

        val encoded = DefaultXmlContentParser.marshalContent(plainContext(), content)
        val reparsed = parse(encoded)

        assertEquals("Root", reparsed.group.name)
        assertEquals(listOf("Name", "Group"), reparsed.group.extensions.map { it.name.localName })
        assertTrue(reparsed.group.extensions.all { it.name.namespaceUri == "urn:test" })
        val nameExtension = reparsed.group.extensions.first { it.name.localName == "Name" }
        val flag = nameExtension.attributes.single { it.name.localName == "flag" }
        assertEquals("urn:attributes", flag.name.namespaceUri)
        assertEquals("yes", flag.value)
    }

    @Test
    fun namespacedDocumentRootIsRejected() {
        val xml = """
            <p:KeePassFile xmlns:p="urn:test">
              <p:Meta/>
              <p:Root/>
            </p:KeePassFile>
        """.trimIndent()

        assertFailsWith<FormatError.InvalidXml> { parse(xml) }
    }

    @Test
    fun schemaAttributesOnlyMatchTheEmptyNamespace() {
        val reader = xmlReader(
            """
            <Root xmlns:p="urn:test">
              <Value p:Flag="vendor" Flag="schema"/>
              <NamespacedOnly p:Flag="vendor"/>
            </Root>
            """.trimIndent(),
        )
        reader.enterDocumentRoot()

        var index = 0
        reader.forEachChildElement {
            when (index++) {
                0 -> assertEquals("schema", reader.attributeOrNull("Flag"))
                1 -> assertNull(reader.attributeOrNull("Flag"))
            }
            reader.skipElement()
        }
    }

    @Test
    fun unqualifiedExtensionChildClearsAnInheritedDefaultNamespace() {
        val base = parse(
            """
            <KeePassFile>
              <Meta><Generator>Test</Generator></Meta>
              <Root>
                <Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><Name>Root</Name></Group>
                <DeletedObjects/>
              </Root>
            </KeePassFile>
            """.trimIndent(),
        )
        val parent = XmlExtension(
            name = XmlQualifiedName(localName = "Parent", namespaceUri = "urn:parent"),
            namespaces = listOf(XmlNamespace(prefix = "", namespaceUri = "urn:parent")),
            content = listOf(
                XmlExtensionContent.Element(
                    XmlExtension(name = XmlQualifiedName(localName = "Child"))
                )
            ),
        )
        val content = base.copy(
            group = base.group.copy(extensions = listOf(parent)),
        )

        val encoded = DefaultXmlContentParser.marshalContent(plainContext(), content)
        val child = parse(encoded).group.extensions.single().content.single()
            as XmlExtensionContent.Element

        assertEquals("", child.value.name.namespaceUri)
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
}
