package app.keemobile.kotpass.xml

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class XmlLineEndingTest {
    @Test
    fun normalizesLiteralTextAndCdataAccordingToTheDeclaredVersion() {
        for (version in listOf(null, "1.0", "1.1")) {
            val expected = normalized(version)
            for (cdata in listOf(false, true)) {
                val content = if (cdata) "<![CDATA[$literal]]>" else literal
                forEachReader(document(version, "<Root>$content</Root>")) { reader ->
                    reader.enterDocumentRoot()
                    assertEquals(expected, reader.readElementText(), "version=$version cdata=$cdata")
                }
            }
        }
    }

    @Test
    fun normalizesLiteralAttributeWhitespaceButPreservesCharacterReferences() {
        for (version in listOf(null, "1.0", "1.1")) {
            val xml = document(version, "<Root literal=\"$literal\" refs=\"$references\"/>")
            forEachReader(xml) { reader ->
                reader.enterDocumentRoot()
                assertEquals(normalized(version).replace('\n', ' '), reader.attributeOrNull("literal"))
                assertEquals(referencedValue, reader.attributeOrNull("refs"))
            }
        }
    }

    @Test
    fun characterReferencesBypassLineEndingNormalization() {
        for (version in listOf(null, "1.0", "1.1")) {
            forEachReader(document(version, "<Root>$references</Root>")) { reader ->
                reader.enterDocumentRoot()
                assertEquals(referencedValue, reader.readElementText())
            }
        }
    }

    @Test
    fun streamingChunksPreserveVersionSemanticsAcrossEntitiesAndCdata() {
        for (version in listOf(null, "1.0", "1.1")) {
            val xml = document(version, "<Root>$literal$references<![CDATA[$literal]]></Root>")
            forEachReader(xml) { reader ->
                reader.enterDocumentRoot()
                val result = StringBuilder()
                while (true) {
                    val chunk = reader.readStreamingTextChunk(1)
                    if (chunk != null) {
                        result.append(chunk)
                    } else {
                        when (reader.next()) {
                            EventType.TEXT,
                            EventType.CDSECT,
                            EventType.ENTITY_REF,
                            EventType.IGNORABLE_WHITESPACE,
                            -> result.append(reader.text)

                            EventType.END_ELEMENT -> break
                            EventType.END_DOCUMENT -> error("Unexpected end of document")
                            else -> Unit
                        }
                    }
                }
                assertEquals(normalized(version) + referencedValue + normalized(version), result.toString())
            }
        }
    }

    @Test
    fun handlesCrPairsAndMultibyteSeparatorsAcrossInputBoundaries() {
        for (version in listOf(null, "1.0", "1.1")) {
            for (separator in listOf("\r\n", "\r\u0085", "\u0085", "\u2028")) {
                // Position every byte of these sequences around the decoder's
                // 16 KiB boundary, including the middle of a UTF-8 character.
                val prefix = document(version, "<Root>")
                for (offset in -2..1) {
                    val padding = "a".repeat(16 * 1024 - prefix.encodeToByteArray().size + offset)
                    val xml = "$prefix$padding${separator}b</Root>"
                    val normalizedSeparator = when {
                        version == "1.1" -> "\n"
                        separator == "\r\n" -> "\n"
                        separator == "\r\u0085" -> "\n\u0085"
                        else -> separator
                    }
                    forEachReader(xml) { reader ->
                        reader.enterDocumentRoot()
                        assertEquals(padding + normalizedSeparator + "b", reader.readElementText())
                    }
                }
            }
        }
    }

    @Test
    fun nextTagAppliesTheVersionBeforeReadingRootAttributesAndContent() {
        for (version in listOf(null, "1.0", "1.1")) {
            forEachReader(document(version, "<Root value=\"$literal\">$literal</Root>")) { reader ->
                assertEquals(EventType.START_ELEMENT, reader.nextTag())
                assertEquals(normalized(version).replace('\n', ' '), reader.attributeOrNull("value"))
                assertEquals(normalized(version), reader.readElementText())
            }
        }
    }

    @Test
    fun declarationDoesNotAcceptXml11SeparatorsAsWhitespace() {
        for (version in listOf("1.0", "1.1")) {
            for (separator in listOf("\u0085", "\u2028")) {
                for (xml in listOf(
                    "<?xml${separator}version=\"$version\"?><Root/>",
                    "<?xml version=\"$version\"${separator}encoding=\"utf-8\"?><Root/>",
                )) {
                    forEachReader(xml) { reader ->
                        assertFails("version=$version separator=U+${separator.single().code.toString(16)} xml=$xml") {
                            reader.enterDocumentRoot()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun markupAcceptsNelAndLineSeparatorAsWhitespaceOnlyInXml11() {
        for (version in listOf(null, "1.0", "1.1")) {
            for (separator in listOf("\u0085", "\u2028")) {
                val xml = document(version, "<Root${separator}value=\"ok\"/>")
                forEachReader(xml) { reader ->
                    if (version == "1.1") {
                        assertEquals("Root", reader.enterDocumentRoot())
                        assertEquals("ok", reader.attributeOrNull("value"))
                    } else {
                        assertFails("version=$version separator=U+${separator.single().code.toString(16)}") {
                            reader.enterDocumentRoot()
                        }
                    }
                }
            }
        }
    }

    private fun forEachReader(xml: String, block: (XmlReader) -> Unit) {
        block(xmlReader(xml))
        block(xmlReader(xml.encodeToByteArray()))
        val source = OneByteSource(xml.encodeToByteArray()).buffer()
        try {
            block(xmlReader(source))
        } finally {
            source.close()
        }
    }

    private fun document(version: String?, content: String): String =
        if (version == null) content else "<?xml version=\"$version\"?>$content"

    private fun normalized(version: String?): String =
        if (version == "1.1") "a\nb\nc\nd\ne\nf" else "a\u0085b\u2028c\n\u0085d\ne\nf"

    private class OneByteSource(bytes: ByteArray) : Source {
        private val source = Buffer().write(bytes)

        override fun read(sink: Buffer, byteCount: Long): Long =
            source.read(sink, minOf(byteCount, 1L))

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    private companion object {
        const val literal = "a\u0085b\u2028c\r\u0085d\r\ne\rf"
        const val references = "a&#x85;b&#x2028;c&#xD;&#x85;d&#xD;&#xA;e&#xD;f"
        const val referencedValue = "a\u0085b\u2028c\r\u0085d\r\ne\rf"
    }
}
