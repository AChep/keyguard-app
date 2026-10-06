package com.artemchep.keyguard.util.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmlValidationTest {
    @Test
    fun `complete parsing requires one root with matching closed tags`() {
        for (xml in listOf(
            "", " \n", "<!--empty-->", "<root/><other/>", "<root>",
            "<root><child></root>", "<root></other>", "<root/></root>", "</root><root/>",
            "before<root/>", "<root/>after", "<root/> <![CDATA[text]]>",
            "<p:root xmlns:p='urn:p' xmlns:q='urn:p'></q:root>",
        )) {
            assertFailsWith<IllegalArgumentException>(xml) { XmlParser.parse(xml) }
        }
    }

    @Test
    fun `malformed attributes and unterminated markers are rejected in either mode`() {
        for (xml in listOf(
            "<root a=1/>", "<root a/>", "<root a='unterminated>", "<root a='<'/>",
            "<root a='one' a='two'/>", "<root a='&unknown;'/>",
            "<root><!--unfinished", "<root><![CDATA[unfinished", "<root><?unfinished", "<root>]]></root>",
        )) {
            for (requireComplete in listOf(true, false)) {
                assertFailsWith<IllegalArgumentException>(xml) {
                    XmlParser.parse(xml, requireComplete)
                }
            }
        }
    }

    @Test
    fun `comments cannot contain double hyphens or end with a hyphen in either mode`() {
        for (xml in listOf(
            "<root>a<!--bad--comment-->b</root>",
            "<root><!--bad---></root>",
            "<!--bad--comment--><root/>",
            "<root/><!--bad--->",
        )) {
            for (requireComplete in listOf(true, false)) {
                assertFailsWith<IllegalArgumentException>(xml) {
                    XmlParser.parse(xml, requireComplete)
                }
            }
        }
    }

    @Test
    fun `whitespace before a start or end tag name is rejected in either mode`() {
        val inputs = listOf(" ", "\t", "\n", "\r").flatMap { space ->
            listOf("<${space}root/>", "<root></${space}root>")
        }
        for (xml in inputs) {
            for (requireComplete in listOf(true, false)) {
                assertFailsWith<IllegalArgumentException>(xml) {
                    XmlParser.parse(xml, requireComplete)
                }
            }
        }
    }

    @Test
    fun `adjacent attributes require a whitespace separator in either mode`() {
        for (xml in listOf(
            "<root a='1'b='2'/>",
            "<root a=\"1\"b=\"2\"></root>",
            "<p:root xmlns:p='urn:p'xmlns:q='urn:q'/>",
        )) {
            for (requireComplete in listOf(true, false)) {
                assertFailsWith<IllegalArgumentException>(xml) {
                    XmlParser.parse(xml, requireComplete)
                }
            }
        }
    }

    @Test
    fun `DOCTYPE declarations are rejected in either mode`() {
        for (xml in listOf("<!DOCTYPE root><root/>", "<!DOCTYPE root [<!ENTITY a 'b'>]><root>&a;</root>")) {
            for (requireComplete in listOf(true, false)) {
                assertFailsWith<IllegalArgumentException> { XmlParser.parse(xml, requireComplete) }
            }
        }
    }

    @Test
    fun `depth limit bounds recursive tree conversion in either mode`() {
        fun document(depth: Int) = "<x>".repeat(depth) + "leaf" + "</x>".repeat(depth)

        for (requireComplete in listOf(true, false)) {
            assertEquals("leaf", XmlParser.parse(document(256), requireComplete).textContent)
            for (depth in listOf(257, 20_000)) {
                assertFailsWith<IllegalArgumentException> {
                    XmlParser.parse(document(depth), requireComplete)
                }
            }
        }
    }

    @Test
    fun `partial parsing retains completed fields and unfinished element text`() {
        val root = XmlParser.parse("<Error><Code>AccessDenied</Code><Message>unfinished", requireComplete = false)

        assertEquals("Error", root.name.local)
        assertEquals(listOf("AccessDenied", "unfinished"), root.children.map { it.directTextContent })
    }

    @Test
    fun `partial parsing retains recovery from mismatched tags and surrounding text`() {
        val root = XmlParser.parse("before<Error><Code>AccessDenied</wrong></Error>after", requireComplete = false)

        assertEquals("Error", root.name.local)
        assertEquals("AccessDenied", root.children.single().directTextContent)
        assertEquals("#document", XmlParser.parse("<one/><two/>", requireComplete = false).name.local)
        assertEquals("#document", XmlParser.parse("", requireComplete = false).name.local)
    }
}
