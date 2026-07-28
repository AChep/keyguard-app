package app.keemobile.kotpass.xml

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.extensions.addBytes
import app.keemobile.kotpass.io.encodeBase64
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlAttribute
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.models.XmlQualifiedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtectedXmlValueCodecTest {
    @Test
    fun namespacedMarkerNamesRemainExtensionAttributes() {
        val reader = xmlReader(
            "<Root><Value xmlns:v='urn:vendor' v:Protected='True' " +
                "ProtectInMemory='True'>secret</Value></Root>"
        )
        reader.enterDocumentRoot()

        reader.forEachChildElement {
            val markers = reader.readProtectedXmlValueMarkers()
            assertFalse(markers.usesInnerEncryption)
            assertTrue(markers.protectsInMemory)
            reader.skipElement()
        }

        assertTrue(
            XmlAttribute(
                name = XmlQualifiedName(FormatXml.Attributes.Protected),
                value = FormatXml.Values.True,
            ).isProtectedXmlValueMarker()
        )
        assertFalse(
            XmlAttribute(
                name = XmlQualifiedName(
                    localName = FormatXml.Attributes.Protected,
                    namespaceUri = "urn:vendor",
                    prefix = "v",
                ),
                value = FormatXml.Values.True,
            ).isProtectedXmlValueMarker()
        )
    }

    @Test
    fun innerEncryptionTakesPrecedenceOverMemoryProtection() {
        val key = ByteArray(32) { index -> (index * 7).toByte() }
        val plaintext = "secret"
        val plaintextBytes = plaintext.encodeToByteArray()
        val encryptedBytes = EncryptionSaltGenerator.ChaCha20(key).processBytes(plaintextBytes)
        plaintextBytes.fill(0)
        val xml = try {
            buildXmlString("Root") {
                element("Value") {
                    attribute(FormatXml.Attributes.Protected, FormatXml.Values.True)
                    attribute(
                        FormatXml.Attributes.ProtectedInMemPlainXml,
                        FormatXml.Values.True,
                    )
                    addBytes(encryptedBytes)
                }
            }
        } finally {
            encryptedBytes.fill(0)
        }

        val decoded = readValues(xml, EncryptionSaltGenerator.ChaCha20(key)).single()

        assertTrue(decoded is EntryValue.Encrypted)
        assertEquals(plaintext, decoded.content)
    }

    @Test
    fun writerAndReaderPreserveInnerCipherStreamOrder() {
        val key = ByteArray(32) { index -> (index * 11).toByte() }
        val expected = listOf("first secret", "second secret", "third secret")
        val encodeContext = XmlContext.Encode.Encrypted(
            version = FormatVersion(4, 1),
            binaries = emptyMap(),
            innerEncryption = EncryptionSaltGenerator.ChaCha20(key),
        )
        val xml = buildXmlString("Root") {
            expected.forEach { text ->
                element("Value") {
                    writeProtectedXmlValue(
                        context = encodeContext,
                        value = EntryValue.Encrypted(EncryptedValue.fromString(text)),
                    )
                }
            }
        }

        assertEquals(
            expected.size,
            Regex(" ${FormatXml.Attributes.Protected}=\\\"${FormatXml.Values.True}\\\"")
                .findAll(xml)
                .count(),
        )
        assertFalse(FormatXml.Attributes.ProtectedInMemPlainXml in xml)
        assertEquals(
            expected,
            readValues(xml, EncryptionSaltGenerator.ChaCha20(key)).map(EntryValue::content),
        )
    }

    @Test
    fun plainWriterUsesCanonicalMemoryProtectionMarker() {
        val value = "line one\r\nline two"
        val context = XmlContext.Encode.Plain(
            version = FormatVersion(4, 1),
            binaries = emptyMap(),
            memoryProtectionFlags = emptySet(),
        )
        val xml = buildXmlString("Root") {
            element("Value") {
                writeProtectedXmlValue(
                    context = context,
                    value = EntryValue.Plain(value),
                    protectInMemory = true,
                )
            }
        }

        assertTrue(
            " ${FormatXml.Attributes.ProtectedInMemPlainXml}=\"${FormatXml.Values.True}\"" in xml
        )
        assertFalse(" ${FormatXml.Attributes.Protected}=" in xml)
        val decoded = readValues(xml, innerEncryption = null).single()
        assertTrue(decoded is EntryValue.Encrypted)
        assertEquals(value, decoded.content)
    }

    @Test
    fun discardedProtectedDescendantsAdvanceTheSharedInnerCipher() {
        val key = ByteArray(32) { index -> (index * 13).toByte() }
        val encryption = EncryptionSaltGenerator.ChaCha20(key)
        val firstDiscarded = encrypt(encryption, "first discarded")
        val secondDiscarded = encrypt(encryption, "second discarded")
        val retained = encrypt(encryption, "retained secret")
        val xml = """
            <Root>
              <Unknown>
                <First Protected="True">$firstDiscarded</First>
                <Nested><Second Protected="True">$secondDiscarded</Second></Nested>
              </Unknown>
              <Value Protected="True">$retained</Value>
            </Root>
        """.trimIndent()
        val reader = xmlReader(xml)
        reader.enterDocumentRoot()
        val decryption = EncryptionSaltGenerator.ChaCha20(key)
        var decoded: EntryValue? = null

        reader.forEachChildElement {
            if (reader.isUnqualifiedElement("Unknown")) {
                reader.discardKdbxElement(decryption)
            } else {
                decoded = reader.readProtectedXmlValue(decryption)
            }
        }

        assertEquals("retained secret", decoded?.content)
    }

    @Test
    fun namespacedProtectedMarkerInDiscardedXmlDoesNotAdvanceTheInnerCipher() {
        val key = ByteArray(32) { index -> (index * 17).toByte() }
        val retained = encrypt(
            EncryptionSaltGenerator.ChaCha20(key),
            "retained secret",
        )
        val xml = """
            <Root>
              <Unknown xmlns:v="urn:vendor">
                <Value v:Protected="True">ordinary extension text</Value>
              </Unknown>
              <Value Protected="True">$retained</Value>
            </Root>
        """.trimIndent()
        val reader = xmlReader(xml)
        reader.enterDocumentRoot()
        val decryption = EncryptionSaltGenerator.ChaCha20(key)
        var decoded: EntryValue? = null

        reader.forEachChildElement {
            if (reader.isUnqualifiedElement("Unknown")) {
                reader.discardKdbxElement(decryption)
            } else {
                decoded = reader.readProtectedXmlValue(decryption)
            }
        }

        assertEquals("retained secret", decoded?.content)
    }

    @Test
    fun protectedDiscardedValuesRejectNestedContent() {
        val reader = xmlReader(
            "<Root><Unknown Protected=\"True\"><Nested/></Unknown></Root>"
        )
        reader.enterDocumentRoot()

        assertFailsWith<FormatError.InvalidXml> {
            reader.forEachChildElement {
                reader.discardKdbxElement(EncryptionSaltGenerator.ChaCha20(ByteArray(32)))
            }
        }
    }

    private fun readValues(
        xml: String,
        innerEncryption: EncryptionSaltGenerator?,
    ): List<EntryValue> {
        val reader = xmlReader(xml)
        reader.enterDocumentRoot()
        return buildList {
            reader.forEachChildElement {
                add(reader.readProtectedXmlValue(innerEncryption))
            }
        }
    }

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
}
