package com.artemchep.keyguard.util.xml

import kotlin.test.Test
import kotlin.test.assertEquals

class XmlParserTest {
    @Test
    fun `namespace declarations apply to their element and descendants`() {
        val root = XmlParser.parse(
            """
            <root xmlns="urn:default" xmlns:p="urn:outer">
              <p:branch xmlns:p="urn:inner"><p:leaf/><leaf/></p:branch>
              <p:sibling/>
              <branch xmlns="urn:other"><leaf/></branch>
              <leaf/>
            </root>
            """.trimIndent(),
        )

        assertEquals(XmlName("urn:default", "root"), root.name)
        val branch = root.children[0]
        assertEquals(XmlName("urn:inner", "branch"), branch.name)
        assertEquals(
            listOf(XmlName("urn:inner", "leaf"), XmlName("urn:default", "leaf")),
            branch.children.map { it.name },
        )
        assertEquals(XmlName("urn:outer", "sibling"), root.children[1].name)
        assertEquals(XmlName("urn:other", "leaf"), root.children[2].children.single().name)
        assertEquals(XmlName("urn:default", "leaf"), root.children[3].name)
    }

    @Test
    fun `unqualified and unresolved prefixed names retain their local name`() {
        val root = XmlParser.parse("<root><p:child/></root>")

        assertEquals(XmlName(null, "root"), root.name)
        assertEquals(XmlName(null, "child"), root.children.single().name)
    }

    @Test
    fun `namespace attributes decode entities and quoted tag delimiters`() {
        val root = XmlParser.parse("<p:root xmlns:p='urn:a&gt;b&amp;c' other=\"a>b\"/>")

        assertEquals(XmlName("urn:a>b&c", "root"), root.name)
    }

    @Test
    fun `text fragments ignore comments and processing instructions but preserve CDATA`() {
        val root = XmlParser.parse(
            "\uFEFF<?xml version='1.0'?><!--before--><root>" +
                "a<!--split-->&amp;<?part value?><![CDATA[<b>&amp;]]>c</root><!--after-->",
        )

        assertEquals("a&<b>&amp;c", root.directTextContent)
    }

    @Test
    fun `valid comments and tag whitespace preserve names and text in either mode`() {
        for (requireComplete in listOf(true, false)) {
            val root = XmlParser.parse(
                "<!----><p:root \t\nxmlns:p \t= \n'urn:p'\tother='value' >" +
                    "a<!---leading-hyphen-->b<!--single-hyphen -->c<p:child \t\n/>" +
                    "</p:root \t\n><!--after-->",
                requireComplete,
            )

            assertEquals(XmlName("urn:p", "root"), root.name)
            assertEquals(XmlName("urn:p", "child"), root.children.single().name)
            assertEquals("abc", root.directTextContent)
        }
    }

    @Test
    fun `direct text excludes descendants and recursive text retains the existing order`() {
        val root = XmlParser.parse("<root>a<child>b<leaf>c</leaf>d</child>e<empty/></root>")

        assertEquals("ae", root.directTextContent)
        assertEquals("bd", root.children.first().directTextContent)
        assertEquals("aebdc", root.textContent)
        assertEquals("", root.children.last().textContent)
    }

    @Test
    fun `literal line endings normalize before entity expansion including CDATA and attributes`() {
        val root = XmlParser.parse(
            "<root xmlns='a\r\nb\rc&#13;'>a\r\nb\rc<![CDATA[d\r\ne\rf]]>&#13;g</root>",
        )

        assertEquals("a\nb\nc\r", root.name.namespace)
        assertEquals("a\nb\ncd\ne\nf\rg", root.directTextContent)
    }
}
