package com.artemchep.keyguard.util.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmlEntitiesTest {
    @Test
    fun `named and numeric entities decode once including supplementary characters`() {
        val root = XmlParser.parse(
            "<root>&amp;&lt;&gt;&quot;&apos;&#233;&#xE9;&#128512;&#x1F600;&#9;&#10;&#13;&amp;lt;</root>",
        )

        assertEquals("&<>\"'éé😀😀\t\n\r&lt;", root.directTextContent)
    }

    @Test
    fun `invalid entities are rejected in text and attributes in either mode`() {
        val entities = listOf(
            "&unknown;", "&unfinished", "&#0;", "&#xD800;", "&#xDFFF;", "&#8;", "&#xFFFE;",
            "&#x110000;", "&#+10;", "&#-1;", "&#x+41;", "&#X41;", "&#xZZ;", "&#;", "&#x;",
        )
        for (entity in entities) {
            for (xml in listOf("<root>$entity</root>", "<root value='$entity'/>")) {
                for (requireComplete in listOf(true, false)) {
                    assertFailsWith<IllegalArgumentException>(xml) { XmlParser.parse(xml, requireComplete) }
                }
            }
        }
    }

    @Test
    fun `invalid literal characters are rejected throughout the document in either mode`() {
        for (char in listOf("\u0000", "\u0008", "\uFFFF", "\uD800", "\uDC00")) {
            for (xml in listOf("<root>$char</root>", "<root><![CDATA[$char]]></root>", "<root value='$char'/>")) {
                for (requireComplete in listOf(true, false)) {
                    assertFailsWith<IllegalArgumentException>(xml) { XmlParser.parse(xml, requireComplete) }
                }
            }
        }
        assertEquals("😀𝄞�", XmlParser.parse("<root>😀𝄞�</root>").directTextContent)
    }
}
