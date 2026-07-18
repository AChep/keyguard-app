package app.keemobile.kotpass.xml

import app.keemobile.kotpass.errors.FormatError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class XmlStreamTest {
    @Test
    fun readsChildElementsWithTextAttributesAndNesting() {
        val reader = xmlReader(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- comment before root -->
            <Root>
                <A>text &amp; entities</A>
                <B Attr="value &quot;quoted&quot;">
                    <Nested>inner</Nested>
                    <Skipped><Deep>deep</Deep></Skipped>
                </B>
                <Empty/>
                <Cdata><![CDATA[a < b & c]]></Cdata>
                <Multiline>line1&#10;line2</Multiline>
            </Root>
            """.trimIndent(),
        )

        assertEquals("Root", reader.enterDocumentRoot())

        var a: String? = null
        var bAttr: String? = null
        var nested: String? = null
        var empty: String? = "sentinel"
        var cdata: String? = null
        var multiline: String? = null
        reader.forEachChildElement {
            when (reader.localName) {
                "A" -> a = reader.readElementText()
                "B" -> {
                    bAttr = reader.attributeOrNull("Attr")
                    reader.forEachChildElement {
                        when (reader.localName) {
                            "Nested" -> nested = reader.readElementText()
                            else -> reader.skipElement()
                        }
                    }
                }
                "Empty" -> empty = reader.readElementTextOrNull()
                "Cdata" -> cdata = reader.readElementText()
                "Multiline" -> multiline = reader.readElementText()
                else -> reader.skipElement()
            }
        }

        assertEquals("text & entities", a)
        assertEquals("value \"quoted\"", bAttr)
        assertEquals("inner", nested)
        assertNull(empty)
        assertEquals("a < b & c", cdata)
        assertEquals("line1\nline2", multiline)
    }

    @Test
    fun missingAttributeReturnsNull() {
        val reader = xmlReader("<Root><A B=\"1\"/></Root>")
        reader.enterDocumentRoot()
        reader.forEachChildElement {
            assertEquals("1", reader.attributeOrNull("B"))
            assertNull(reader.attributeOrNull("Missing"))
            reader.skipElement()
        }
    }

    @Test
    fun preservesSignificantWhitespaceInText() {
        val reader = xmlReader("<Root><Value>  spaced  out  </Value></Root>")
        reader.enterDocumentRoot()
        var value: String? = null
        reader.forEachChildElement {
            value = reader.readElementText()
        }
        assertEquals("  spaced  out  ", value)
    }

    @Test
    fun scalarTextRejectsNestedElements() {
        val reader = xmlReader("<Root><Value>before<Nested/>after</Value></Root>")
        reader.enterDocumentRoot()

        assertFailsWith<FormatError.InvalidXml> {
            reader.forEachChildElement { reader.readElementText() }
        }
    }

    @Test
    fun writesDocumentWithPrologEscapingAndEmptyElements() {
        val xml = buildXmlString("KeePassFile") {
            element("Meta") {
                textElement("Generator", "Test & <Co> \"quotes\"")
                textElement("Empty", "")
            }
            element("Root") {
                element("Entry") {
                    attribute("Protected", "True")
                    text("emoji 🔑")
                }
            }
        }

        assertEquals(
            "<?xml version='1.0' encoding='utf-8'?>" +
                "<KeePassFile><Meta>" +
                "<Generator>Test &amp; &lt;Co&gt; \"quotes\"</Generator>" +
                "<Empty/>" +
                "</Meta><Root>" +
                "<Entry Protected=\"True\">emoji 🔑</Entry>" +
                "</Root></KeePassFile>",
            xml,
        )
    }

    @Test
    fun verbatimTextPreservesCarriageReturnsAcrossRoundTrips() {
        val value = "line1\r\nline2\rline3\nline4"
        val xml = buildXmlString("Root") {
            element("Value") { verbatimText(value) }
        }

        val reader = xmlReader(xml)
        reader.enterDocumentRoot()
        var decoded: String? = null
        reader.forEachChildElement {
            decoded = reader.readElementText()
        }
        assertEquals(value, decoded)
    }

    @Test
    fun writtenDocumentsRoundTripThroughTheReader() {
        val values = listOf(
            "plain",
            "entities & <angles> \"quotes\" 'apostrophes'",
            "emoji 🔑 and 𝔘nicode",
            "  leading and trailing  ",
            "multi\nline\ttabbed",
        )
        val xml = buildXmlString("Root") {
            values.forEach { textElement("Value", it) }
        }

        val reader = xmlReader(xml)
        reader.enterDocumentRoot()
        val decoded = mutableListOf<String>()
        reader.forEachChildElement {
            decoded += reader.readElementText()
        }
        assertEquals(values, decoded)
    }

    @Test
    fun whitespaceOnlyOptionalScalarsAreAbsent() {
        val reader = xmlReader("<Root><UUID> \n </UUID><Time>\t</Time></Root>")
        reader.enterDocumentRoot()
        reader.forEachChildElement {
            when (reader.localName) {
                "UUID" -> assertNull(reader.readUuidScalarOrNull())
                "Time" -> assertNull(reader.readInstantOrNull())
            }
        }
    }

    @Test
    fun malformedNonEmptyScalarsAreRejected() {
        val reader = xmlReader("<Root><Value>not-an-integer</Value></Root>")
        reader.enterDocumentRoot()
        assertFailsWith<FormatError.InvalidXml> {
            reader.forEachChildElement { reader.readIntOrNull() }
        }
    }

    @Test
    fun childCallbackMustConsumeItsElement() {
        val reader = xmlReader("<Root><Child/><Sibling/></Root>")
        reader.enterDocumentRoot()
        assertFailsWith<FormatError.InvalidXml> {
            reader.forEachChildElement { /* Deliberate contract violation. */ }
        }
    }

    @Test
    fun childCallbackCannotStopOnASameNamedNestedElement() {
        val reader = xmlReader("<Root><Node><Node/></Node></Root>")
        reader.enterDocumentRoot()

        assertFailsWith<FormatError.InvalidXml> {
            reader.forEachChildElement {
                reader.next()
                reader.next()
            }
        }
    }

    @Test
    fun documentTypeDeclarationsAreRejected() {
        val reader = xmlReader("<!DOCTYPE Root [<!ENTITY x 'value'>]><Root>&x;</Root>")
        assertFailsWith<FormatError.InvalidXml> { reader.enterDocumentRoot() }
    }

    @Test
    fun configuredResourceLimitsAreEnforced() {
        assertFailsWith<FormatError.InvalidXml> {
            val reader = xmlReader(
                "<R><A><B/></A></R>".encodeToByteArray(),
                XmlReadLimits(maxDepth = 2),
            )
            reader.enterDocumentRoot()
            reader.forEachChildElement {
                reader.forEachChildElement { reader.skipElement() }
            }
        }
        assertFailsWith<FormatError.InvalidXml> {
            val reader = xmlReader(
                "<R><A/><B/></R>".encodeToByteArray(),
                XmlReadLimits(maxElements = 2),
            )
            reader.enterDocumentRoot()
            reader.forEachChildElement { reader.skipElement() }
        }
        assertFailsWith<FormatError.InvalidXml> {
            val reader = xmlReader(
                "<R><A>12345</A></R>".encodeToByteArray(),
                XmlReadLimits(maxScalarChars = 4),
            )
            reader.enterDocumentRoot()
            reader.forEachChildElement { reader.readElementText() }
        }
        assertFailsWith<FormatError.InvalidXml> {
            xmlReader(
                "<R Attr='12345'/>".encodeToByteArray(),
                XmlReadLimits(maxAttributeCharsPerElement = 5),
            ).enterDocumentRoot()
        }
        assertFailsWith<FormatError.InvalidXml> {
            xmlReader(
                "<Root/>".encodeToByteArray(),
                XmlReadLimits(maxDocumentBytes = 4),
            ).enterDocumentRoot()
        }
    }

    @Test
    fun deterministicSpecialCharacterPropertyRoundTrips() {
        var state = 0x13579BDF
        repeat(200) {
            val length = (state ushr 27) + 1
            val value = buildString {
                repeat(length) {
                    state = state * 1_103_515_245 + 12_345
                    append(
                        when ((state ushr 24) and 7) {
                            0 -> '&'
                            1 -> '<'
                            2 -> '>'
                            3 -> '\r'
                            4 -> '\n'
                            5 -> '\t'
                            else -> ('a'.code + ((state ushr 16) and 25)).toChar()
                        }
                    )
                }
            }
            val xml = buildXmlString("Root") { element("Value") { verbatimText(value) } }
            val reader = xmlReader(xml.encodeToByteArray())
            reader.enterDocumentRoot()
            var decoded: String? = null
            reader.forEachChildElement { decoded = reader.readElementText() }
            assertEquals(value, decoded, "case $it")
        }
    }
}
